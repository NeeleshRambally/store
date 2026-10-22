# ADR 0008: Operational posture: migrations, readiness, shutdown and request ids

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record covers how the service runs in production: migrations, readiness, time limits on
database calls and shutdown, container hardening, request body limits, and what each request
leaves in the logs and metrics. It keeps the decisions and their reasons;
[Operations](../DESIGN.md#operations) and [Configuration](../DESIGN.md#configuration) give the
operating detail and the measurements.

## Status
Accepted

## Context
The [README](../../README.md) asks for the project to be treated as a production application. Four
operational choices had been made implicitly and were not recorded:
- Liquibase ran inside the application process at start-up, with the runtime database role, which
  therefore owned the schema. Changeset 4 (`CREATE EXTENSION IF NOT EXISTS pg_trgm`) needs a
  privileged role.
- The readiness probe reported only Spring's `ReadinessState`.
- The graceful-shutdown drain (20 s) was longer than the Compose stop grace (10 s by default), so
  `docker compose down` killed the container half-way through a drain.
- Nothing in a response or a log line identified the request a client was asking about.

The record also holds related choices of the same kind: time limits on database calls, hardened
Compose containers, a bound on request bodies, and the access log, creation log and latency
metrics.

## Decision
The schema is migrated by a separate job, and readiness follows the database. Database calls,
shutdown and request bodies are bounded, the containers are hardened, and every request leaves an
id and one access line.

### Migrations run as a job
Schema changes run in a one-shot job under the schema owner, and the service runs under a role
that cannot change the schema. [Start-up and migrations](../DESIGN.md#start-up-and-migrations)
describes the job as it is operated.
- **Job, then service.** In the Compose stack, the production-shaped layout, `migrate` runs the
  same image with `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, as the schema owner `admin`, with
  `LIQUIBASE_CONTEXTS=seed`. It applies every changeset, logs `Started StoreApplication` and exits
  0 in about six seconds, since with no web server nothing keeps the process alive. `store` starts
  only after that (`depends_on` with `service_completed_successfully`) and connects as
  `store_app` with `SPRING_LIQUIBASE_ENABLED=false`, under which Boot creates no Liquibase bean.
- **The role.** `scripts/postgres-init/01-roles.sql` creates `store_app`, which can connect and
  use the `public` schema. Default privileges (grants PostgreSQL applies to each object `admin`
  creates later) give it `SELECT` and `INSERT` on every table and `USAGE` and `SELECT` on every
  sequence. Liquibase's own tables get the same grant, which the runtime never uses.
- **What it cannot do.** `store_app` cannot `CREATE TABLE`
  (`permission denied for schema public`), `CREATE INDEX` (`must be owner`), `UPDATE` or `DELETE`
  (`permission denied for table`). That is exactly the API's write surface: three `POST`s, no
  update and no delete. The one exception is `UPDATE` and `DELETE` on `idempotency_key`, which
  changeset 9 grants so that the key store can complete, release and purge keys
  ([ADR 0010](0010-idempotency-keys.md)).
- **The proof.** `ddl-auto: validate` reads the schema as `store_app` at start-up, and readiness
  checks the connection. The smoke test's HTTP checks, a `POST /customer` and the 413 included,
  run against the service as `store_app`. Its `compose` mode also asserts the job's exit code and
  the role's grants on the started stack ([The smoke test](../DESIGN.md#the-smoke-test)).
- **Old volumes.** The postgres image runs the init script once, on an empty data directory, and
  default privileges cover only objects created after them. A volume from a stack started before
  the job existed therefore has no `store_app` role, so `store` fails at its first connection
  until `docker compose down -v` is run once ([Running locally](../DESIGN.md#running-locally)).
- **Kubernetes.** A rollout runs the same image as a `Job` (or an init container) ahead of the
  deployment, with the owner's credentials; the deployment carries the runtime role's.
- **Passwords.** Both Compose passwords stay inline as development values, and the `env_file`
  split is left open ([Image and runtime](../ASSUMPTIONS.md#image-and-runtime)).
- **Development.** The [README](../../README.md)'s `./gradlew bootRun` keeps in-process Liquibase
  as `admin` under the `local` profile. In production, the extension changeset's first run needs a
  privileged role: a DBA installs `pg_trgm` once, and the changeset's `CREATE EXTENSION IF NOT
  EXISTS` then does nothing.

### Readiness includes the database
A replica that cannot reach its database leaves rotation instead of answering errors; liveness
ignores the database.
- `management.endpoint.health.group.readiness.include` is `readinessState,db`, and the group shows
  its component names ([Ports, probes and metrics](../DESIGN.md#ports-probes-and-metrics)).
- The trade-off is accepted knowingly. During a database outage every replica goes unready at once
  and the ingress returns 503. That is the honest signal for a service that cannot serve a single
  request without its database.
- Requests that reach the service meanwhile get a 503 problem detail with `Retry-After: 5`:
  - `service_unavailable` when a connection cannot be obtained or is lost;
  - `database_timeout` when a statement is cancelled at its timeout, or a query comes after the
    transaction's deadline.
- `OpenAPI.yaml` declares both on every operation, so the ingress can tell overload, a lost
  database and a slow one from a bug (500).
- One loss still answers a 500: a session that the database terminates while a `POST`'s insert is
  flushed at commit (`57P01`, as a restart does).
  [Database failures](../DESIGN.md#database-failures) maps every case to its answer.
- The readiness `db` component turns DOWN only after the pool's 5 s `connection-timeout`, so probe
  timeouts must exceed it.
- `/actuator/health` stays detail-free. Liveness stays `LivenessState` only, so a database outage
  never restarts the process.

### Database calls are bounded in time
A slow or stalled database ends a request with a retryable 503 instead of holding its thread and
its pool slot. PgJDBC waits for an answer forever by default (`socketTimeout` 0), and the database
sits behind a high-latency link ([README](../../README.md), task 3).
- Every connection carries a server-side `statement_timeout` of 10 s (`DB_STATEMENT_TIMEOUT`, sent
  in the startup `options`), a `socketTimeout` of 15 s (`DB_SOCKET_TIMEOUT`), a
  `cancelSignalTimeout` of 3 s and `tcpKeepAlive`.
- Every transaction has a default timeout of 15 s (`TX_TIMEOUT`).
- The server cancels a slow statement cleanly (503 `database_timeout`). The socket timeout is the
  only bound on a stall, and it drops the connection (503 `service_unavailable`), so it stays above
  the statement timeout. [Timeouts](../DESIGN.md#timeouts) gives the measured bounds.
- Liquibase runs on the same pool, so the `migrate` job sets `DB_STATEMENT_TIMEOUT` and
  `DB_SOCKET_TIMEOUT` to `0`, and a Kubernetes `Job` needs the same. An index build or a data
  migration on a large table may outlast any request limit, and a cancelled changeset stops the
  rollout. [ADR 0009](0009-index-changesets.md) records how the index changesets lock their tables.

### Stop grace covers the drain
The requests in flight at a stop signal may finish, and every grace period is sized to cover that
drain. [Shutdown](../DESIGN.md#shutdown) gives the sequence, the measurements and what a client
sees.
- `server.shutdown: graceful` gives the requests in flight at most the 20 s shutdown phase
  (`spring.lifecycle.timeout-per-shutdown-phase`). Tomcat then waits up to 5 s more for the thread
  of a request cut at the end of the phase. `stop_grace_period: 30s` on the Compose `store`
  service covers both.
- Readiness turns `OUT_OF_SERVICE` when the drain begins, but the API connector closes its
  listening socket at the same moment, so the flip moves no traffic away. A new connection is
  refused, and a keep-alive connection idle at the signal gets no response to its next request.
- In Kubernetes, kube-proxy and the ingress stop sending to a deleted pod only a moment after the
  kubelet starts stopping it. The deployment therefore delays the signal with a `preStop` hook, a
  step the kubelet runs before it sends the signal. The hook uses the `sleep` handler (Kubernetes
  1.30 and later), because the image has no shell for an `exec` hook.
- The delay moves new connections only, since kube-proxy keeps a connection on the pod it was
  opened to.
- `terminationGracePeriodSeconds` counts from the start of the hook and covers the delay, the 20 s
  shutdown phase, Tomcat's 5 s and a margin.
- `GracefulShutdownIT` observes the drain on a second application, and
  `FailClosedConfigurationTest` pins both properties. The idle-connection case was observed by
  hand.

### The Compose services are hardened
`store` and `migrate` run with a read-only root, no capabilities and a 512 MiB memory limit
without swap, and the heap's share of that limit was measured under load.
[Container hardening and memory](../DESIGN.md#container-hardening-and-memory) gives the method,
every share tried and the GC cost.
- `read_only: true` with `tmpfs: [/tmp]`, a memory-backed `/tmp` that holds the only writes the
  image makes: Tomcat's base directory and the JVM's `hsperfdata`. Without it `store` fails with
  `Unable to start web server` and never becomes ready.
- `cap_drop: [ALL]` and `security_opt: ["no-new-privileges:true"]`.
- `deploy.resources.limits.memory: 512m`, which Compose v2 honours without Swarm, and
  `memswap_limit: 512m`, so that an overrun fails instead of swapping.
- The heap gets 30% of the limit (`-XX:MaxRAMPercentage=30`, 154 MiB) under `-XX:+UseSerialGC`.
  At 200 busy clients the container peaked at 477.4 MiB, while a 35% heap came within 1.1 MiB of
  the limit. G1 overran the limit at 35%, and the JVM would pick it by itself once a deployment
  grants 1792 MiB or more and two CPUs.
- The limit must be raised, and the share measured again, when `TOMCAT_THREADS_MAX`,
  `DB_POOL_SIZE` or the cache grows.
- Both services rotate their `json-file` log at 3 files of 10 MB, because Docker's default keeps a
  container's whole output in one growing file and the access log adds a line per request.
- `postgres` is left unhardened, because `initdb` runs as root and needs `CHOWN` and `SETUID` to
  hand the data directory to its own user. A production database is a managed service or its own
  hardening exercise, not this stack's.

### Request size
The service bounds request bodies itself and reads no body that an endpoint does not take.
[Creating resources](../DESIGN.md#creating-resources) and
[Unread bodies and silent clients](../DESIGN.md#unread-bodies-and-silent-clients) give the rules a
client sees and the measurements.
- **Types and methods.** The three `POST`s consume `application/json` only. A body of any other
  media type, or one without a `Content-Type`, is a 415 decided when the request is mapped, and is
  never read. The API has no `PUT`, `PATCH` or `DELETE` endpoint, so such a request is a 405
  decided at the same point (a 404 for an unknown path), and its body is not read either.
- **The bound.** `RequestBodyLimitFilter` (right after `RequestIdFilter`, API connector only)
  bounds every body to `REQUEST_MAX_BODY_BYTES`, 64 KiB by default. A declared `Content-Length`
  over it fails before a byte is read, and a chunked body fails once more than the bound has been
  counted. When Spring MVC reads the JSON body, both surface as a 413 problem detail with the
  request id; malformed JSON stays a 400.
- **Why a filter.** No Spring Boot property bounds a JSON body on Tomcat: `max-http-form-post-size`
  bounds form parsing, `max-swallow-size` the swallow after an abort, and
  `max-http-request-header-size` the headers. The largest valid request is about 2 KB. There is no
  411 (Length Required), because chunked bodies are legal and common.
- **Nothing reads a body before mapping.** Three readers used to read one: Tomcat's form
  parsing, the `DispatcherServlet`'s multipart parsing, and Spring's `FormContentFilter`, which
  turned an oversized body into a 500. Multipart support and that filter are switched off
  (`spring.servlet.multipart.enabled: false`, `spring.mvc.formcontent.filter.enabled: false`), and
  `max-http-form-post-size` follows `REQUEST_MAX_BODY_BYTES` on both connectors.
- **Silent clients.** After a 413, a 415 or a 405, Tomcat reads and discards the rest of the body
  on the request's thread, up to 2 MB. `server.tomcat.connection-timeout` is 10 s, because
  Tomcat's 60 s let 200 silent clients hold every request thread for a minute. Idle keep-alive
  connections keep 60 s (`server.tomcat.keep-alive-timeout`).
- **Left to the ingress.** The timeout bounds silence, not a client that trickles bytes or keeps
  opening connections.

### Request ids, not tracing
Every request gets an id that the client can quote and every log line of the request carries.
- `RequestIdFilter` runs before every other filter. It echoes an inbound `X-Request-Id` that
  matches `[A-Za-z0-9._-]{1,128}`, and otherwise mints a UUID.
- The value is set on the response before the chain runs, and put in the MDC (the logging context
  of the thread) as `requestId` until the request completes.
- The character class and the length cap keep a caller from injecting log content or oversized
  values.
- No tracing library (`micrometer-tracing`) is added. There is no collector in scope, a trace id
  without a backend is noise, and the header is exactly where a gateway's or a tracer's id attaches
  when one arrives ([Request ids and filters](../DESIGN.md#request-ids-and-filters)).

### One access line per request
Every request that reaches the filters leaves one log line, so any id a client quotes finds
something. [Logs](../DESIGN.md#logs) gives the format, the size of a line and its measured cost.
- `AccessLogFilter` writes one INFO line on `com.example.store.access` for every request on the API
  connector that reaches the filters (a request Tomcat rejects before them, such as one with an
  oversized header, writes none).
- The line holds the method, the route template, the status the client receives (the 500 Tomcat
  sends when an exception escapes the filters) and the duration. It never holds the raw path, the
  query string or the body, because a search word is a query parameter.
- Without it, a successful read, a 304, a 400, a 404, a 413 and a 415 log nothing at the shipped
  levels, so a quoted id would find nothing.
- It is on by default, because the service cannot know whether an ingress in front of it logs
  requests. `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF` turns it off where one does.

### Creations are logged after the commit
A created order, customer or product writes one INFO line with its id; an order's line adds its
customer and its number of products. The line comes from a
`@TransactionalEventListener(phase = AFTER_COMMIT)` method, never from the service. The sequence
ids of [ADR 0005](0005-sequence-id-generation.md) defer the insert to the commit, which can still
fail after `create()` has returned. A line for a row that was never stored would mislead whoever
reads it.

### Latency buckets, not percentiles
The service publishes latency buckets for the monitoring system to combine, not percentiles of its
own.
- `http_server_requests_seconds` publishes cumulative buckets at 11 bounds from 5 ms to 10 s, plus
  `+Inf`. The monitoring system derives any quantile from them, per route and across replicas.
- Percentiles computed in the service cannot be combined across replicas.
- Micrometer's generic `percentiles-histogram` would publish 69 buckets per series, about six times
  the scrape of these 12 ([Ports, probes and metrics](../DESIGN.md#ports-probes-and-metrics)).

## Consequences
- **The Compose stack is the reference.** It is the only runnable deployment shipped, so its
  hardening is the reference for any other environment. The smoke test's `compose` mode reads each
  setting back with `docker inspect` on both services (`ReadonlyRootfs`, `Tmpfs`, `CapDrop`,
  `SecurityOpt`, `Memory`, `MemorySwap` and `LogConfig`), so a pipeline run fails when one is
  removed. A new limit needs the asserted value (`Memory` and `MemorySwap`, both 536870912) changed
  with it.
- **Ports stay on loopback.** The stack publishes its ports on 127.0.0.1 unless
  `STORE_BIND_ADDRESS` names another address, and the same mode checks every published port's
  `HostIp` against that value. The GitLab smoke job sets `0.0.0.0` to reach its Docker-in-Docker
  daemon.
- **The job owns the rollout order.** The runtime role no longer owns the schema in the Compose
  stack and the pipelines, and a failed migration stops the stack before the service starts. The
  role split is Compose wiring: any other environment needs the same role, the same two credential
  pairs and the job ahead of the deployment.
- **Request ids reach the logs.** A client that sends its own id gets it back on every response
  that reaches the filters, a 304, 413 or 500 included, and can quote it to operations. Only the
  400 that Tomcat sends itself lacks it
  ([Requests Tomcat rejects itself](../DESIGN.md#requests-tomcat-rejects-itself)). The log lines of
  one request share `requestId` in the ECS (Elastic Common Schema) JSON output, which the smoke test
  checks on the started image.
- **Failed statements log no request values.** A data-integrity conflict is logged at WARN by SQL
  state and constraint name only, with the full cause at DEBUG. PgJDBC runs with
  `logServerErrorDetail: false`, so no driver message quotes the request's values. The price: for a
  409 or a `database_timeout` 503 the log of a failed `POST` no longer says which insert failed
  ([Logs](../DESIGN.md#logs)).
- **Timeouts keep their order.** A deployment that changes a database timeout keeps the socket
  timeout above the statement timeout and above the slowest legitimate statement. Otherwise a slow
  statement loses its connection instead of being cancelled.
- **Maintenance takes the service out of rotation.** Readiness and the database are coupled, so a
  planned database maintenance window takes the service out of rotation for its duration. That is
  the intended behaviour and must be communicated as such.
- **A blank context list is refused.** `${LIQUIBASE_CONTEXTS:prod}` covers an unset variable only.
  A blank one binds to an empty context list, under which Liquibase runs every changeset, seed
  included. `LiquibaseContextsGuard`, an `EnvironmentPostProcessor` registered in
  `META-INF/spring.factories`, therefore refuses a blank or absent `spring.liquibase.contexts`
  before any bean or connection exists, unless `spring.liquibase.enabled` is `false` as in the
  Compose `store` service ([Fail closed](../DESIGN.md#fail-closed)).

### What pins each part
[Operations](../DESIGN.md#operations) documents the probe bodies, the info endpoint, the request-id
rule, the access and creation lines, the latency buckets, the grace periods and the `preStop`
delay. These tests pin them:

| Part | Pinned by |
|---|---|
| Probe bodies, info endpoint, request id, access and creation lines, latency buckets | `ActuatorIT`, `RequestIdFilterTests`, `RequestIdFilterMdcTest`, `RequestIdIT`, `AccessLogIT`, `CreationLogIT` |
| Status logged for an exception that escapes the filter chain | `AccessLogFilterTest` |
| Graceful shutdown and its 20 s phase, multipart parsing and the form-content filter switched off, the Tomcat and database timeout settings, the blank-context guard | `FailClosedConfigurationTest` |
| The drain | `GracefulShutdownIT` |
| The 503s of a lost and of a slow database, the 500 of a session terminated at a `POST`'s commit | `GlobalExceptionHandlerTests`, `GlobalExceptionHandlerLogTest`, `DatabaseFailureIT` |
| The body bound | `RequestBodyLimitFilterSliceTests`, `RequestBodyLimitFilterTest`, `RequestBodyLimitIT` |
| Tomcat's two timeouts on both connectors | `RequestBodyLimitIT` |
| The database timeouts, read back from the pool, a pooled connection and the transaction manager | `DatabaseTimeoutsIT` |
| No text sent appears in the lines of a cancelled, a terminated and a constraint-violating insert | `FailedInsertLogIT` |
| Fail-closed start-up, without credentials and with blank contexts | `FailClosedStartupIT` |
| The `db` component, the 413, the job's exit code, the hardening of `migrate` and `store`, the address of every published port, the runtime role's grants, the 405 of `DELETE /order/1`, the ECS access line of `GET /order/1` | `scripts/smoke-test.sh` |

No test pins the 30 s `stop_grace_period` of `compose.yaml`. The `preStop` delay is deployment
guidance that no test can pin.
