# ADR 0008: Operational posture: migrations, readiness, shutdown and request ids

## Status
Accepted

## Context
The README asks for the project to be treated as a production application. Four operational
choices had been made implicitly and were not recorded: Liquibase runs inside the application
process at start-up with the runtime database role, and changeset 4 (`CREATE EXTENSION IF NOT
EXISTS pg_trgm`) needs a privileged role; the readiness probe reported only Spring's
`ReadinessState`; the graceful-shutdown drain (20 s) was longer than the Compose stop grace
(10 s by default), so `docker compose down` killed the container half-way through a drain; and
nothing in a response or a log line identified the request a client was asking about.

## Decision
- **In-process migrations, for now.** Liquibase keeps running at start-up with the runtime
  credentials. Every changeset except the `pg_trgm` extension needs only the privileges of the
  schema owner, and the extension is a one-off that a DBA installs with a superuser or a role
  that has `CREATE` on the database; afterwards the changeset is a no-op. The path to least
  privilege is set out but not taken: `spring.liquibase.enabled=false` in the runtime
  configuration, a migration job (the same image run with `--spring.liquibase.enabled=true` and
  the DDL role, or the Liquibase CLI against the same changelog) executed before the rollout,
  and a runtime role limited to `SELECT`, `INSERT` and `USAGE` on the sequences. It is not
  taken yet because the single-process layout is what the README describes and what the
  Compose stack and the smoke test exercise; the change is configuration and a pipeline step,
  not code.
- **Readiness includes the database.** `management.endpoint.health.group.readiness.include` is
  `readinessState,db` and the group shows component names
  (`{"status":"UP","components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}}}`), so
  a replica whose database connection is gone stops receiving traffic instead of answering
  500s. The trade-off is accepted knowingly: during a database outage every replica goes
  unready at once and the ingress returns 503 instead of the service's own 500, which is the
  honest signal for a service that cannot serve a single request without its database. The
  health endpoint itself stays detail-free; liveness stays `LivenessState` only, so a database
  outage never restarts the process.
- **Stop grace covers the drain.** `server.shutdown: graceful` with a 20 s phase timeout is
  matched by `stop_grace_period: 30s` on the Compose service, and a Kubernetes deployment
  needs `terminationGracePeriodSeconds` of at least the same (its default is 30 s). Readiness
  flips to `REFUSING_TRAFFIC` on shutdown, so an orchestrator routes new connections away
  while in-flight requests complete.
- **Request ids, not tracing.** `RequestIdFilter` runs before every other filter, echoes an
  inbound `X-Request-Id` that matches `[A-Za-z0-9._-]{1,128}` and otherwise mints a UUID; the
  value is set on the response before the chain runs and put in the MDC as `requestId` until
  the request completes. The character class and length cap keep a caller from injecting log
  content or oversized values. No tracing library (`micrometer-tracing`) is added: there is no
  collector in scope, a trace id without a backend is noise, and the header is exactly where a
  gateway's or a tracer's id attaches when one arrives.

## Consequences
- `docs/DESIGN.md`, "Operations", documents the probe bodies, the info endpoint, the request-id
  rule and the grace periods; `ActuatorIT`, `RequestIdFilterTests`, `RequestIdFilterMdcTest`
  and `RequestIdIT` pin them, and `scripts/smoke-test.sh` checks the `db` component on the
  started container.
- The runtime role still owns the schema until the migration job is split out; that split is
  the next step when a deployment target with separate roles exists.
- A client that sends its own id gets it back on every status, including 304 and 500, and can
  quote it to operations; log lines of one request share `requestId` in the ECS JSON output.
- Readiness and the database are coupled: a planned database maintenance window takes the
  service out of rotation for its duration, which is the intended behaviour and must be
  communicated as such.
