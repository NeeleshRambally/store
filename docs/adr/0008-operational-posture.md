# ADR 0008: Operational posture: migrations, readiness, shutdown and request ids

## Status
Accepted

## Context
The README asks for the project to be treated as a production application. Four operational
choices had been made implicitly and were not recorded: Liquibase ran inside the application
process at start-up with the runtime database role, which therefore owned the schema, and
changeset 4 (`CREATE EXTENSION IF NOT EXISTS pg_trgm`) needs a privileged role; the readiness
probe reported only Spring's
`ReadinessState`; the graceful-shutdown drain (20 s) was longer than the Compose stop grace
(10 s by default), so `docker compose down` killed the container half-way through a drain; and
nothing in a response or a log line identified the request a client was asking about.

## Decision
- **Migrations run as a job.** The Compose stack is the production-shaped layout: a one-shot
  `migrate` service runs the same image as the service with
  `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, the schema owner's credentials (`admin`) and
  `LIQUIBASE_CONTEXTS=seed`, applies every changeset, logs `Started StoreApplication` and
  exits 0 in about six seconds (no web server, so nothing keeps the process alive); `store`
  starts only after `migrate` has completed successfully (`depends_on` with
  `service_completed_successfully`), connects as `store_app` and sets
  `SPRING_LIQUIBASE_ENABLED=false`, under which Boot creates no Liquibase bean at all. The
  role split is created by `scripts/postgres-init/01-roles.sql`, which the postgres image runs
  once on an empty data directory: `store_app` can connect, use the `public` schema, `SELECT`
  and `INSERT` on every table and `USAGE`/`SELECT` on every sequence that `admin` creates
  afterwards (default privileges, so Liquibase's tables get the same `ar` grant; the runtime
  never touches them). It cannot `CREATE TABLE` (`permission denied for schema public`),
  `CREATE INDEX` (`must be owner`), `UPDATE` or `DELETE` (`permission denied for table`),
  which is exactly the API's write surface: three `POST`s and no update or delete. That the
  runtime works under the restricted role is proven at start-up by `ddl-auto: validate` (the
  schema is read as `store_app`), by the readiness group's `db` component and by every smoke
  check: in `compose` mode `scripts/smoke-test.sh` asserts the `migrate` container's exit code
  is 0, that `store_app` can `select count(*) from customer`, that `create table` is denied for
  the schema and `update customer` is denied for the table, after the usual HTTP checks
  (`POST /customer` and the 413 run as `store_app`). Default privileges apply only to objects
  created after the statement, so a volume from a stack started before the job existed has no
  `store_app` role and the service fails closed at its first connection; `docker compose down
  -v` once, then `up`, recreates it. A Kubernetes rollout runs the same image as a `Job` (or an
  init container) ahead of the deployment with the owner's credentials, and the deployment
  carries the runtime role's. Both Compose passwords remain the README-path development values
  set inline; the `env_file` split is documented in ASSUMPTIONS and left open. The README's
  `./gradlew bootRun` keeps in-process Liquibase as `admin` under the `local` profile: that is
  the development path, and the extension changeset still needs a privileged role on its first
  run, which a DBA installs once in production.
- **Readiness includes the database.** `management.endpoint.health.group.readiness.include` is
  `readinessState,db` and the group shows component names
  (`{"status":"UP","components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}}}`), so
  a replica whose database connection is gone stops receiving traffic instead of answering
  errors. The trade-off is accepted knowingly: during a database outage every replica goes
  unready at once and the ingress returns 503, which is the honest signal for a service that
  cannot serve a single request without its database. The service answers the same way for
  the requests that reach it meanwhile: a connection that cannot be obtained within the
  pool's 5 s `connection-timeout` (`CannotCreateTransactionException`), that is lost during one
  of the request's reads (which surfaces as the failed rollback's `JpaSystemException`, caused
  by Hibernate's `TransactionException`) or that the socket timeout cuts while a `POST`'s insert
  is flushed at commit (`DataAccessResourceFailureException`) is a 503 problem detail with
  `code: service_unavailable`, and a cancelled statement or an expired transaction
  (`QueryTimeoutException`, `TransactionTimedOutException`) one with `code: database_timeout`,
  each with `Retry-After: 5` and declared on every operation in `OpenAPI.yaml`, so overload, a
  lost database and a slow one are distinguishable from a bug (500) at the ingress. One loss
  still answers a 500: a session that the database terminates during that flush (`57P01`, as a
  restart does) fails the commit with a `JpaSystemException` that no failed rollback caused
  (`docs/DESIGN.md`, Operations). The readiness `db` component turns DOWN only after that same
  5 s, so probe timeouts must exceed it. The health endpoint itself stays detail-free; liveness
  stays `LivenessState` only, so a database outage never restarts the process.
- **Database calls are bounded in time.** The database sits behind a high-latency link
  (README, task 3), and PgJDBC waits for an answer forever by default (`socketTimeout` 0), so a
  stalled or silently dropped connection used to hold its pool slot and its request thread
  with no limit. Every connection now carries a server-side `statement_timeout` of 10 s
  (`DB_STATEMENT_TIMEOUT`), sent in the startup `options` so that it is the session's reset
  value for whichever role connects, a `socketTimeout` of 15 s (`DB_SOCKET_TIMEOUT`) for a
  database that stops answering, a `cancelSignalTimeout` of 3 s so that a cancel sent to a
  stalled server delays the request by 3 s rather than the driver's 10, and `tcpKeepAlive`;
  every transaction has a default timeout of 15 s (`TX_TIMEOUT`). The server cancels a slow
  statement cleanly (a 503 `database_timeout`), while the socket timeout is the only bound on
  a stall and drops the connection (a 503 `service_unavailable`), so it stays above the
  statement timeout. `docs/DESIGN.md`, Configuration, gives the measured bounds. Liquibase runs
  on the same pool, so the `migrate` job sets `DB_STATEMENT_TIMEOUT` and `DB_SOCKET_TIMEOUT` to
  `0`, and a Kubernetes `Job` needs the same: an index build or a data migration on a large
  table may outlast any request limit, and a cancelled changeset stops the rollout. ADR 0009
  records how the index changesets lock their tables while they build.
- **Stop grace covers the drain.** `server.shutdown: graceful` with a 20 s phase timeout is
  matched by `stop_grace_period: 30s` on the Compose service, and a Kubernetes deployment
  needs `terminationGracePeriodSeconds` of at least the same (its default is 30 s). Readiness
  flips to `REFUSING_TRAFFIC` on shutdown, so an orchestrator routes new connections away
  while in-flight requests complete. The drain is documented but not observed by a test: the
  API has no slow request to hold open while a shutdown is signalled, so a test could only
  show that an idle server stops, which the configuration already states. The fail-closed
  start-up, by contrast, is observed by `FailClosedStartupIT`.
- **The Compose services are hardened.** `store` and `migrate` run with `read_only: true`
  and `tmpfs: [/tmp]` (Tomcat's base directory and the JVM's `hsperfdata` are the only
  writes the image makes), `cap_drop: [ALL]`, `security_opt: ["no-new-privileges:true"]`,
  `deploy.resources.limits.memory: 512m`, which Compose v2 honours without Swarm, and
  `memswap_limit: 512m`, so that an overrun fails instead of swapping (without it Docker
  allows as much swap again as the limit). The image's entrypoint gives the heap
  `-XX:MaxRAMPercentage=30` of that limit (`MaxHeapSize` 154 MiB) under `-XX:+UseSerialGC`,
  and the share is measured under load, not guessed: with the heap fully resident and 200
  busy clients for 300 s the container peaked at 477.4 MiB, while 75% (384 MiB of heap)
  needed 636.8 MiB and was killed once swap was off, 40% reached the limit and 35% came
  within 1.1 MiB of it (`docs/DESIGN.md`, Operations, gives the method, the environment and
  the GC cost). SerialGC is explicit because G1, whose own native structures take 59 to 63
  MiB, overran the limit at a 35% heap under the same load, and the JVM picks G1 by itself
  once a deployment grants 1792 MiB or more and two CPUs. The limit must be raised, and the
  share re-measured, when `TOMCAT_THREADS_MAX`, `DB_POOL_SIZE` or the cache grows. Both
  services rotate their `json-file` log at 3 files of 10 MB (`max-size: "10m"`,
  `max-file: "3"`), because Docker's default keeps the whole output of a container in one
  growing file and the access log adds a line per request.
  `postgres` is left unhardened because `initdb` runs as root and needs `CHOWN` and `SETUID`
  to hand the data directory to its own user; a production database is a managed service or
  its own hardening exercise, not this stack's. The tmpfs is not decoration: with it removed
  the `migrate` job still exits 0 (no web server, so the JVM only loses its `hsperfdata`
  file), but `store` fails with `Unable to start web server`, because Tomcat creates its
  base directory under `java.io.tmpdir` and the read-only root refuses it, and readiness is
  never reached.
- **Request size.** `RequestBodyLimitFilter` (ordered right after `RequestIdFilter`, API
  connector only) wraps every request so that a declared `Content-Length` over
  `REQUEST_MAX_BODY_BYTES` (64 KiB by default) fails before a byte is read and a chunked body
  fails once more than the bound has been counted; both surface as a 413 problem detail with
  the request id, and malformed JSON stays a 400. No Spring Boot property bounds a JSON body on
  Tomcat (`max-http-form-post-size` is form parsing, `max-swallow-size` the post-abort swallow,
  `max-http-request-header-size` the headers), which is why the filter exists. The largest
  valid request is about 2 KB; Tomcat closes the connection after the 413 and swallows the
  unread remainder up to 2 MB. There is no 411: chunked bodies are legal and common.
- **Request ids, not tracing.** `RequestIdFilter` runs before every other filter, echoes an
  inbound `X-Request-Id` that matches `[A-Za-z0-9._-]{1,128}` and otherwise mints a UUID; the
  value is set on the response before the chain runs and put in the MDC as `requestId` until
  the request completes. The character class and length cap keep a caller from injecting log
  content or oversized values. No tracing library (`micrometer-tracing`) is added: there is no
  collector in scope, a trace id without a backend is noise, and the header is exactly where a
  gateway's or a tracer's id attaches when one arrives.
- **One access line per request.** `AccessLogFilter` writes one INFO line on
  `com.example.store.access` for every request on the API connector, with the method, the
  route template, the status and the duration, never the raw path, the query string or the
  body (a search word is a query parameter). Without it most requests write no line at the
  shipped levels (a successful read, a 304, a 400, a 404, a 413 and a 415 log nothing), so a
  request id a client quotes would find nothing; with it every id finds at least that line.
  It is on by default because the service cannot know whether an ingress in front of it logs
  requests, and `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF` turns it off where one does;
  `docs/DESIGN.md`, Operations, gives the format, the size of a line and its measured cost.
- **Creations are logged after the commit.** A created order, customer or product writes one
  INFO line with its id (an order also with its customer and its number of products) from a
  `@TransactionalEventListener(phase = AFTER_COMMIT)` method, never from the service: the
  sequence ids defer the insert to the commit, which can still fail after `create()` has
  returned, and a line for a row that was never stored would mislead whoever reads it.
- **Latency buckets, not percentiles.** `http_server_requests_seconds` publishes cumulative
  buckets at 11 bounds from 5 ms to 10 s plus `+Inf`, from which the monitoring system derives
  any quantile per route and across replicas; percentiles computed in the service cannot be
  combined across replicas, and Micrometer's generic `percentiles-histogram` would publish 69
  buckets per series, about six times the scrape of these 12.

## Consequences
- `docs/DESIGN.md`, "Operations", documents the probe bodies, the info endpoint, the request-id
  rule, the access and creation lines, the latency buckets and the grace periods; `ActuatorIT`,
  `RequestIdFilterTests`, `RequestIdFilterMdcTest`, `RequestIdIT`, `AccessLogIT` and
  `CreationLogIT` pin them, `GlobalExceptionHandlerTests`, `GlobalExceptionHandlerLogTest` and
  `DatabaseFailureIT` the 503s of a lost and of a slow database and the 500 of a session
  terminated at a `POST`'s commit,
  `RequestBodyLimitFilterSliceTests`, `RequestBodyLimitFilterTest` and
  `RequestBodyLimitIT` pin the body bound, `FailClosedStartupIT` the fail-closed start-up, and
  `scripts/smoke-test.sh` checks the `db` component, the 413, the migration job's exit code,
  the hardening of the `migrate` and `store` containers, the address of every published port
  and the runtime role's grants on the started stack, plus the 405 problem detail of
  `DELETE /order/1` and the ECS access line of a `GET /order/1`.
- The Compose stack is the only runnable deployment shipped, so its hardening is the
  reference for any other environment: a read-only root with a writable `/tmp`, no
  capabilities, no privilege escalation and a memory limit without swap, sized to the
  footprint measured under load for the shipped thread, pool and cache settings. The smoke
  test's `compose` mode asserts it with `docker inspect` on both services (`ReadonlyRootfs`,
  `Tmpfs` with `/tmp`, `CapDrop` with `ALL`, `SecurityOpt` with `no-new-privileges:true`,
  `Memory` and `MemorySwap` both 536870912, so no swap, and `LogConfig` with `json-file`,
  `max-file` 3 and `max-size` 10m), so a pipeline run fails when a setting is removed, and a
  new limit needs the asserted value changed with it. The stack
  publishes its ports on 127.0.0.1 unless `STORE_BIND_ADDRESS` names another address, and the
  same mode checks every published port's `HostIp` against that value (the GitLab smoke job
  sets `0.0.0.0` to reach the ports of its dind daemon).
- The runtime role no longer owns the schema in the Compose stack and the pipelines; the
  `migrate` job owns the rollout order, so a failed migration stops the stack before the
  service starts. The role split is Compose-specific wiring: any other environment needs the
  same role, the same two credential pairs and the job ahead of the deployment.
- A client that sends its own id gets it back on every status, including 304, 413 and 500, and
  can quote it to operations; log lines of one request share `requestId` in the ECS JSON
  output, one object per line with nested `log`, `service` (name and version) and `ecs`
  members and the MDC entries at the top level, and every request writes at least its access
  line. The smoke test's `compose` mode proves it on the started image: `GET /order/1` sent
  with `X-Request-Id: smoke-<pid>` must leave its access line, with that `requestId`, the route
  and the `service` name and version, in the `store` container's output. A data-integrity
  conflict is logged at WARN by SQL state and constraint name only, with the full cause at
  DEBUG. PgJDBC runs with `logServerErrorDetail: false`, so no driver message quotes the
  request's values: a failed entry of a batched insert reads `<unknown>` where it would quote
  the insert with the posted name or description, and the server's detail (the key of a
  violated constraint, the failing row) is left out, which is the price, while the constraint
  name survives in the server's primary message. `FailedInsertLogIT` finds none of the text
  sent in the lines that a cancelled, a terminated and a constraint-violating insert write.
- A deployment that changes a database timeout keeps their order: the socket timeout above the
  statement timeout and above the slowest legitimate statement, or a slow statement loses its
  connection instead of being cancelled. `DatabaseTimeoutsIT` reads the values back from the
  application's pool, a pooled connection and the transaction manager, and
  `FailClosedConfigurationTest` pins their variables.
- Readiness and the database are coupled: a planned database maintenance window takes the
  service out of rotation for its duration, which is the intended behaviour and must be
  communicated as such.
- The context default `${LIQUIBASE_CONTEXTS:prod}` covers an unset variable only; an explicitly
  blank one binds to an empty context list, and Liquibase runs every changeset when no runtime
  context is given, so the seed would land in a production schema. `LiquibaseContextsGuard`
  (an `EnvironmentPostProcessor` registered in `META-INF/spring.factories`) therefore refuses a
  blank or absent `spring.liquibase.contexts` before any bean or connection exists, unless
  `spring.liquibase.enabled` is `false` as in the Compose `store` service; the failure is a
  plain-text block because logging is not yet initialised. `FailClosedConfigurationTest` and
  `FailClosedStartupIT` pin it.
