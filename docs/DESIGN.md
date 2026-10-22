# Store service: design and operations guide

[Notes for the assessor](../NOTES.md) · [Design](DESIGN.md) · [Assumptions](ASSUMPTIONS.md) · [Performance](PERFORMANCE.md) · [Decision records](adr/README.md)

This guide is for engineers who run, change or operate the store service. It covers installing and
running it, every setting, how a change is tested and delivered, the data model, the API and its
errors, and how the service behaves in production. The reasons behind the larger choices are in the
[decision records](adr/README.md) and [Assumptions](ASSUMPTIONS.md), and the measurements of the
read paths are in [Performance](PERFORMANCE.md).

| Looking for | Section |
|---|---|
| What to install and how to start the service | [Prerequisites](#prerequisites), [Running locally](#running-locally) |
| Every environment variable, its default, and the database timeouts | [Configuration](#configuration) |
| How a change is tested and delivered | [Development workflow](#development-workflow), [Testing](#testing), [Continuous integration](#continuous-integration) |
| Tables and indexes | [Data model](#data-model) |
| Endpoints, input rules and error codes | [API](#api), [Strict input](#strict-input), [Errors](#errors) |
| Idempotency keys, caching and Swagger UI | [Creating resources](#creating-resources), [Caching](#caching), [API documentation](#api-documentation) |
| Ports, logs, database failures, shutdown and the image | [Operations](#operations) |

## Prerequisites
Gradle needs a JDK, and the tests and the Compose stack need Docker. The last two items apply only
in the cases they name.

* **A JDK 17 to 24 to run Gradle.** The Spring Boot and toolchain plugins need 17. Gradle 8.14 runs
  on Java 24 at most; on Java 25 it starts, but `spotlessCheck` fails.
* **A Java 17 toolchain for the code.** Gradle uses an installed JDK 17, or downloads Eclipse
  Temurin 17 (about 190 MB) into `~/.gradle/jdks` on the first build. Offline, point
  `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties` at an installed JDK 17.
* **Docker**, for Docker Compose and for Testcontainers, which starts PostgreSQL for the
  integration tests.
* **The `pg_trgm` contrib module**, for a PostgreSQL installed on bare metal (the
  [README](../README.md)'s alternative). Changeset 4 creates it as an extension for the name
  search's trigram index, an index of three-character fragments. The official image and most
  distributions bundle it.
* **Node.js 22 and npm**, only for the data generator in `utils`.

## Running locally
There are three ways to run the service. The [README](../README.md) path expects a PostgreSQL of
its own; the Compose stack brings one and is laid out as production would be.

| Way | Database | Command |
|---|---|---|
| The README path | PostgreSQL 16.2 on port 5433, `admin:admin`, database `store` | `./gradlew bootRun` |
| Docker Compose, the production-shaped layout | PostgreSQL 16.2 on port 5434, started by the stack | `docker compose up --build` |
| Gradle against the Compose database | The stack's PostgreSQL on 5434 | `docker compose up -d postgres`, then `DB_URL=jdbc:postgresql://localhost:5434/store ./gradlew bootRun` |

### The `local` profile
`./gradlew bootRun` runs under the `local` profile, which supplies the README defaults. The
deployable jar never contains it.

* `bootRun` sets `spring.profiles.default=local`. An explicit `SPRING_PROFILES_ACTIVE` or
  `--spring.profiles.active` replaces that default.
* `application-local.yaml` holds the README defaults: the 5433 URL, `admin:admin` and the `seed`
  Liquibase context.
* It logs every SQL statement: `org.hibernate.SQL` at DEBUG, formatted, with the JPQL as a comment
  and bind values as `?`.
* The file stays in `src/main/resources`, so `bootRun`, `FailClosedConfigurationTest` and an IDE
  run find it.
* The bootJar, and so the image, leave it out. `verifyBootJarContents` (part of `check`) fails the
  build if it is ever packaged.
* So `SPRING_PROFILES_ACTIVE=local` on the jar or the image adds no defaults, and the process fails
  closed at its first connection ([Fail closed](#fail-closed)). CI sets every variable explicitly.

### The Compose stack
`docker compose up --build` starts three services in order: `migrate` waits until `postgres` is
healthy, and `store` until `migrate` has exited 0.

| Service | What it does |
|---|---|
| `postgres` | PostgreSQL on 5434, so that the README's 5433 instance can stay up alongside. When its volume is first initialised, `scripts/postgres-init/01-roles.sql` creates the runtime role `store_app`, which can only connect, read and insert. Changeset 9 adds `UPDATE` and `DELETE` on the `idempotency_key` table alone ([ADR 0010](adr/0010-idempotency-keys.md)) |
| `migrate` | Runs the migrations once with the same image (`SPRING_MAIN_WEB_APPLICATION_TYPE=none`), as the schema owner `admin` and with the `seed` context |
| `store` | Starts only once `migrate` has exited 0, so a failed migration stops the stack before the API comes up. Connects as `store_app` with `SPRING_LIQUIBASE_ENABLED=false` |

Once the stack is up:
* The API is on http://localhost:8080, the actuator on http://localhost:8081 and the database on
  localhost:5434.
* All three bind to 127.0.0.1 only, because the API has no authentication
  ([ADR 0002](adr/0002-authentication-deferred.md)) and the database role is the `admin:admin`
  superuser.
* `STORE_BIND_ADDRESS` publishes them on another address instead.
  `STORE_BIND_ADDRESS=0.0.0.0 docker compose up` publishes all three on every interface, so to
  every network the machine is on.
* A stack first started before the `migrate` job existed needs `docker compose down -v` once. The
  role and its default privileges are created only on an empty data directory, and without them
  `store` fails closed.

### Migrations and sample data
Sample data loads only where it is asked for. An environment that sets nothing gets the schema and
no sample rows.

* Under `bootRun` and in the tests, Liquibase migrates in-process at start-up. In Compose, the
  `migrate` job does it ahead of the service ([Start-up and migrations](#start-up-and-migrations)).
* The sample data (100 customers, 10,000 orders, 100 products) loads when the `seed` Liquibase
  context is active: under the `local` profile, in the tests and in `migrate`.
* The base configuration applies only the context-free changesets, because `LIQUIBASE_CONTEXTS`
  defaults to `prod`.
* A blank value is rejected at start-up, because an empty context set would apply every changeset,
  seed included ([Fail closed](#fail-closed)).
* [utils/README.md](../utils/README.md) describes the script that generates more data.

## Configuration
Every setting is an environment variable with a default, except the two credentials. Without those
the process fails closed ([Fail closed](#fail-closed)).

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/store` | JDBC URL, TLS settings included. A database across a network needs `sslmode=verify-full` ([TLS to the database](#tls-to-the-database)) |
| `DB_USERNAME` | none (`admin` under `local`) | Read from the variable or the file `/run/secrets/DB_USERNAME` ([Credentials as files](#credentials-as-files)). Without either, the first connection fails and the process exits |
| `DB_PASSWORD` | none (`admin` under `local`) | Read from the variable or the file `/run/secrets/DB_PASSWORD`; the same failure without either |
| `LIQUIBASE_CONTEXTS` | `prod` (`seed` under `local`) | Liquibase contexts to apply. Only `seed` loads the sample data; a blank value stops start-up |
| `SPRING_LIQUIBASE_ENABLED` | `true` | Boot's own `spring.liquibase.enabled`. `false` creates no Liquibase bean, which is how the Compose `store` service runs under a role that cannot alter the schema |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size. A request waits at most 5 s for a connection (`spring.datasource.hikari.connection-timeout`), then gets a 503 ([Pool and threads](#pool-and-threads)) |
| `DB_STATEMENT_TIMEOUT` | `10s` | Server-side `statement_timeout` of every connection, sent in the PgJDBC startup `options`. A PostgreSQL time (`10s`, `500ms`, or plain milliseconds); `0` disables it. The server cancels a statement still running at the limit: 503 `database_timeout` ([Timeouts](#timeouts)) |
| `DB_SOCKET_TIMEOUT` | `15` | PgJDBC `socketTimeout` in whole seconds: how long the driver waits for the database to send anything before it drops the connection (503 `service_unavailable`). `0` disables it. Keep it above `DB_STATEMENT_TIMEOUT` ([Timeouts](#timeouts)) |
| `TX_TIMEOUT` | `15s` | Default timeout of every transaction (`spring.transaction.default-timeout`), a duration: `15s`, or `15` for seconds. `-1` disables it; `0` does not ([Timeouts](#timeouts)) |
| `TOMCAT_THREADS_MAX` | `200` | Tomcat request threads (`server.tomcat.threads.max`; see [Pool and threads](#pool-and-threads)) |
| `SERVER_TOMCAT_CONNECTIONTIMEOUT` | `10s` | Boot's own `server.tomcat.connection-timeout`, a duration; a bare number is milliseconds here, where `TX_TIMEOUT` reads one as seconds. How long Tomcat waits for the next bytes of a request it has begun to read, body included, or for a response write to make progress, before it drops the connection. So it is how long a silent client holds a request thread ([Unread bodies and silent clients](#unread-bodies-and-silent-clients)). Idle keep-alive connections are kept 60 s (`server.tomcat.keep-alive-timeout`) regardless |
| `MANAGEMENT_PORT` | `8081` | Port of the management connector that serves the actuator |
| `CACHE_TYPE` | `caffeine` | Spring cache provider for the by-id reads; `none` disables caching ([Caching](#caching)) |
| `CACHE_TTL` | `60s` | Time an `orders` or `products` entry stays cached (`expireAfterWrite`) |
| `NESTED_ORDERS_LIMIT` | `20` | Newest orders embedded in each customer, and newest order ids in each product (1 to 100). `orderCount` always carries the total |
| `REQUEST_MAX_BODY_BYTES` | `65536` | Largest JSON body a `POST` accepts (at least 1024). A longer declared `Content-Length`, or a chunked body read past it, is a 413. Also Tomcat's limit for parsing a URL-encoded form (`server.tomcat.max-http-form-post-size`) on both connectors. A multipart body, and the body of a `PUT`, `PATCH` or `DELETE`, are never read ([Creating resources](#creating-resources), [Unread bodies and silent clients](#unread-bodies-and-silent-clients)) |
| `IDEMPOTENCY_TTL` | `PT24H` | How long the stored answer to a `POST` sent with an `Idempotency-Key` is replayed (an ISO 8601 duration, positive). After it, the key behaves as a new one ([Creating resources](#creating-resources)) |
| `IDEMPOTENCY_STALE_AFTER` | `PT30S` | How long a key claimed by a request that never finished (a process killed, a connection lost) stays reserved before the next request with it may take it over. It must cover the time a keyed `POST` can hold its claim: the 5 s connection wait plus `TX_TIMEOUT` plus `DB_STATEMENT_TIMEOUT`, 30 s at the defaults. A shorter window stops start-up; with either timeout disabled the check only warns |
| `IDEMPOTENCY_PURGE_INTERVAL` | `PT10M` | Interval of the sweep that deletes expired key rows (positive). It runs in the serving process only, never in the `migrate` job |
| `SWAGGER_UI_ENABLED` | `true` | Serves Swagger UI at `/swagger-ui.html` and the checked-in contract at `/openapi.yaml` on the API port. `false` leaves both out, so they are 404s like any unknown path ([API documentation](#api-documentation)) |
| `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS` | none (inherits `INFO`) | Boot's own `logging.level` for the access log ([Logs](#logs)). `OFF` removes the line per request, for a deployment whose ingress already logs every request |

### Pool and threads
The pool, not the thread count, bounds the work sent to the database. A request that cannot get a
connection within 5 s gets a retryable 503.

* With `DB_POOL_SIZE` 10 and 200 request threads, at most 10 requests touch the database at once.
* The rest wait up to 5 s for a connection, then get a 503 with `Retry-After: 5`.
* A slow or stalled database therefore degrades into retryable 503s instead of 200 blocked threads.
  The [timeouts](#timeouts) bound how long a request holds a connection.

To size them:
* Size the pool to the database's connection budget per replica.
* Size the threads to roughly the pool divided by the share of request time spent in the database.
  A request holds its thread for its whole duration, but a connection only for its database part;
  the rest is serialisation, compression and the ETag hash.
* Every endpoint spends most of its time in the database, so that comes to little more than the
  pool. The defaults leave the threads well above it, and the 5 s wait is the real bound.
  `FailClosedConfigurationTest` pins the default.

### Timeouts
Three timeouts bound every database call: one per statement, one per transaction, and one for a
database that stops answering. They were measured on the Compose stack (Docker Desktop on arm64,
PostgreSQL 16.2) with a `GET /order?size=5` held on a lock that a second session took on the
`order` table.
[Sessions, connections and timeouts](PERFORMANCE.md#sessions-connections-and-timeouts) places them
among the other read-path measures.

| Timeout | Bounds | Measured |
|---|---|---|
| `DB_STATEMENT_TIMEOUT`, 10 s | One statement; the server cancels it | 503 `database_timeout` after 10.0 s |
| `TX_TIMEOUT`, 15 s | The statements of one transaction together: each gets the remaining time as its JDBC timeout, and a deadline already passed fails the next statement at once | With the other two raised, cancelled after 15.05 s; with `-1` the request waited 19.16 s for the lock and answered 200 |
| `DB_SOCKET_TIMEOUT`, 15 s | A database that sends nothing at all, which no server-side timeout can cancel | With the `postgres` container paused, 503 `service_unavailable` after 18.03 to 18.06 s in five of six runs and 15.02 s in the sixth |

#### Why `TX_TIMEOUT` is not a hard bound
* A statement started with less than half a second left runs without a JDBC timeout, because the
  remaining time is rounded to whole seconds. `DB_STATEMENT_TIMEOUT` alone then bounds it.
* Hibernate floors the remaining time of its own statements to whole seconds, with at least 1.
* So a transaction can approach `TX_TIMEOUT` plus `DB_STATEMENT_TIMEOUT`.
* `TX_TIMEOUT=0` is not "off", but only queries treat it as a deadline that has already passed.
  `GET /order` then answered a 503 `database_timeout` at once. Hibernate applies no deadline of 0 to
  its own statements, so `GET /order/{id}` (an entity load, not a query) and `POST /customer` still
  succeeded. With a deadline above 0 that has passed, those two answer 503 `service_unavailable`
  ([Database failures](#database-failures)).

#### Why a stalled database takes 18 s
The socket stall takes 18 s rather than 15 because of the transaction timeout's cancel. The
transaction timeout is also 15 s, so it fires at about the moment the socket timeout does. It sends
a cancel to the stalled server, and the driver waits up to its 3 s `cancelSignalTimeout` for that
cancel before the request ends. With the driver's default of 10 s for that signal the stall took
25.06 s, which is why it is set to 3.

#### Keep the socket timeout highest
Keep `DB_SOCKET_TIMEOUT` above `DB_STATEMENT_TIMEOUT` and above the slowest legitimate statement. A
backend that waits or computes sends nothing, so the socket timeout would drop its connection
before the server could cancel the statement.

* With `DB_STATEMENT_TIMEOUT` raised to 30 s, the request lost its connection after 15.04 s
  (503 `service_unavailable`) instead of being cancelled.
* `tcpKeepAlive` is on but adds little: the Linux default is two hours before the first probe.
* HikariCP checks an idle pooled connection every 2 minutes (`keepaliveTime`) and replaces every
  connection after 30 minutes (`maxLifetime`). So the socket timeout, not keepalive, bounds a
  stall.

#### A write held at commit
A `POST /order` whose insert waited for the same lock at commit behaved the same. The first two
rows are three runs each.

| Case | Result |
|---|---|
| The lock held | 503 `database_timeout` after 10.03 to 10.19 s |
| `postgres` paused | 503 `service_unavailable` after 17.03 to 17.07 s |
| Statement and socket timeouts raised | The transaction timeout cancelled the insert after 14.03 and 14.16 s, against the `GET`'s 15.05 s |
| `postgres` restarted while the insert waited | The request ended after 0.42 to 0.49 s with a 500, the loss at commit described under [Database failures](#database-failures) |

The stall ends a second sooner than the `GET`'s because of Hibernate's flooring, which the third
row shows.

#### How the timeouts are set
* The statement timeout travels in the connection's startup `options`, not in
  `connection-init-sql` or an `ALTER ROLE ... SET`. That has three effects:
  * it is the session's reset value, rather than a `SET` that `RESET ALL` undoes;
  * it applies to whichever role connects;
  * it survives the tests' `@ServiceConnection`, which replaces only the URL and the credentials
    (an `options` parameter in the URL would not).
* `DatabaseTimeoutsIT` reads the three values back through the pool, a pooled connection and the
  transaction manager.
* Liquibase runs on the same pool and inherits both driver timeouts: with
  `DB_STATEMENT_TIMEOUT=50ms`, the `migrate` job fails at changeset `5-products-seed`. So `migrate`
  sets `DB_STATEMENT_TIMEOUT` and `DB_SOCKET_TIMEOUT` to `0`, because an index build or a data
  migration may run longer than any request should ([ADR 0009](adr/0009-index-changesets.md)).

### Credentials as files
Either credential can come from a file under `/run/secrets/`, where Compose mounts secrets. A
variable of the same name wins over its file.

* `application.yaml` imports `optional:configtree:/run/secrets/`. Each file in that directory
  becomes a property named after the file, with the trailing newline trimmed. So
  `/run/secrets/DB_USERNAME` and `/run/secrets/DB_PASSWORD` resolve the placeholders.
* Where the directory does not exist (the tests, `bootRun`, the Compose stack as shipped), the
  import adds nothing.

Compose mounts a secret under the variable's name:
```yaml
services:
  store:
    secrets:
      - source: db_password
        target: DB_PASSWORD
secrets:
  db_password:
    file: ./db_password.txt
```
* Name the files after the variables, never after the properties. A file named
  `spring.datasource.password` replaces the property itself and silently overrides `DB_PASSWORD`.
* The import sits just above the packaged `application.yaml`. So a profile-specific or external
  configuration file, `SPRING_DATASOURCE_PASSWORD`, a system property and a command-line argument
  all beat it.
* `.gitignore` and `.dockerignore` list `db_password.txt` and `.env`, so a local secret file stays
  out of Git and out of the image's build context.

### TLS to the database
The default URL does not check the server's certificate. A database across a network, as the
[README](../README.md)'s is, needs `sslmode=verify-full` and the CA that signed the server's
certificate:
```shell
DB_URL='jdbc:postgresql://db.example.internal:5432/store?sslmode=verify-full&sslrootcert=/etc/store/db-ca.crt'
```
* Without TLS options, PgJDBC uses `sslmode=prefer`: TLS when the server offers it, without checking
  the certificate, and plain text otherwise. That neither prevents a downgrade nor detects an
  impostor.
* With `verify-full`, the connection is encrypted, the certificate must chain to that CA and name
  the host, and a server without TLS is refused (`The server does not support SSL.`).
* Mount the CA file read-only outside `/run/secrets/`, where the configtree import would turn it
  into a property.

### Fail closed
The deployable jar never starts on the sample credentials, or loads the sample data, by omission.
[Configuration fails closed](ASSUMPTIONS.md#configuration-fails-closed) says why there is no
fallback.

* **Missing credentials.** A missing `DB_USERNAME` or `DB_PASSWORD` is not a start-up configuration
  error. Spring Boot binds the unresolved placeholder as a literal, so the process fails at
  Liquibase's first connection and exits:
  * `password authentication failed for user "${DB_USERNAME}"` when a server answers;
  * `Connection refused` otherwise (HikariCP makes one attempt).
* `FailClosedStartupIT` observes this with a second, non-web application started without the two
  variables. The test skips itself when `DB_USERNAME` is exported, because the variable would then
  supply the credentials it removes.
* **A blank context list.** A blank `LIQUIBASE_CONTEXTS` (or `SPRING_LIQUIBASE_CONTEXTS`) is caught
  before any connection by `LiquibaseContextsGuard`, an `EnvironmentPostProcessor` registered in
  `META-INF/spring.factories`. It stops start-up with `spring.liquibase.contexts is blank`, unless
  `spring.liquibase.enabled` is `false` (the Compose `store` service).
* That failure is plain text rather than JSON, because logging is not yet initialised.
  `FailClosedConfigurationTest` pins both cases.

## Development workflow
Every change reaches `main` through a pull request, merged once both CI jobs are green and the pull
request is approved. A ruleset on `main` enforces both.

* New behaviour starts with a failing test (unit, slice or integration) and, for API behaviour, a
  Cucumber scenario.
* Pull requests are rebase-merged, so each commit lands on `main` as written.
* [Pull requests and history](ASSUMPTIONS.md#pull-requests-and-history) records how the same gate
  was held before the ruleset existed.

## Testing
`./gradlew build` runs every test tier and every check in one task and one JVM. Reports land in
`build/reports`.

It runs these test tiers:
* unit tests (JUnit 5, Mockito);
* controller slices (`@WebMvcTest`, which starts only the web layer);
* integration tests against a real PostgreSQL started by Testcontainers;
* Cucumber scenarios in `src/test/resources/features`;
* the OpenAPI contract check ([The contract check](#the-contract-check)).

And these checks:
* `spotlessCheck` (formatting);
* `verifyBootJarContents` ([The `local` profile](#the-local-profile));
* `spotbugsMain`, where any finding fails the build
  ([Static analysis](ASSUMPTIONS.md#static-analysis) gives the settings);
* line coverage of at least 80%.

The coverage report and gate both exclude `**/mapper/**` and `StoreApplication`. The mapper
exclusion covers the MapStruct-generated implementations and the hand-written
`ProductMapper.orderIds`, which `ProductMapperTest` covers but the gate does not count. The 100%
line coverage that [Notes for the assessor](../NOTES.md) quotes is measured under these
exclusions.

Each tier has its own name suffix:

| Suffix | Tier | Examples |
|---|---|---|
| `*Test` | Runs without the shared application context: plain JUnit 5 and Mockito, or a small context built by `ApplicationContextRunner` | `SortPolicyTest`, `OrderServiceImplTest`, and `NestedOrdersPropertiesTest` on an `ApplicationContextRunner` |
| `*Tests` | A `@WebMvcTest` slice | All six, such as `OrderControllerTests` |
| `*IT` | Runs on the shared Testcontainers context that `AbstractIntegrationTest` declares | `CucumberIT`, through `CucumberSpringConfiguration`, is the acceptance suite on it |

Where a unit test and a slice cover the same class, the slice also carries `Slice`, so the two
names never differ by a single letter (`RequestBodyLimitFilterTest`,
`RequestBodyLimitFilterSliceTests`).

### The contract check
`OpenApiContractIT` checks real requests and responses against the checked-in `OpenAPI.yaml`, so
the contract cannot drift from the code. It validates captured request/response pairs with
`swagger-request-validator`.

Any of these fails the build:
* an undocumented response field;
* a missing required field at any depth;
* a problem detail without a `code`, or with one the specification does not list;
* a missing declared header;
* an undeclared status;
* a request the server serves and the specification would reject.

The check proves only what its interactions exercise, so each new response arrives with a contract
case of its own. The `test` task declares `OpenAPI.yaml` as an input, so an edit to the
specification alone reruns it.

Real requests cover these statuses:

| Status | Reached by |
|---|---|
| 200 | Every list page (offset, cursor and the two `/order` filters) and every by-id read |
| 201 | Each `POST`, and each `POST` repeated with its `Idempotency-Key`, answered from the store with `Idempotent-Replayed: true` |
| 400 (body) | Each `POST` with a rejected body: a whitespace-only `name`, a repeated product id, a fractional `customerId`, a numeric `description`, a NUL in a string, a client-supplied `id`, a repeated property, content after the object |
| 400 (input rules) | The [Strict input](#strict-input) rules for ids, sort properties, cursors, pages, sizes, filters and search words, including a `size` beyond the 32-bit range, a `customerId` of 0 or in fullwidth digits and a `name` containing a NUL. A no-break space and a word containing U+0085 and U+2028 are accepted by server and specification alike |
| 404 | An unknown order, product and customer, and an order for an unknown customer |
| 406 | Each of the nine operations with `Accept: application/xml` |
| 413 | An oversized body |
| 415 | A `POST /order` sent as `text/plain` |
| 400 (header) | Each `POST` with an `Idempotency-Key` that is empty, too long or not visible ASCII |
| 409 | A keyed `POST /customer` repeated while the first request with that key waits for a table lock (`TableLock`) |
| 422 | An `Idempotency-Key` sent again with another body |
| 304 | A conditional `GET /order/{id}`, `GET /products/{id}`, `GET /customer/{id}` and `GET /customer` page |
| 503 | A `GET /order?size=5` whose statement is cancelled, or whose session is terminated, while it waits for a lock a second connection holds on `order` (`TableLock`) |

Each named example except `constraintViolation` is compared with the real response to the request
its summary names: `unknownSortProperty`, `cursorWithPage`, `combinedFilters`, `unknownOrder`,
`unknownProduct`, `unknownCustomer`, `orderAsXml`, `serviceUnavailable`, `databaseTimeout`,
`invalidIdempotencyKey`, `requestInProgress` and `idempotencyKeyReused`. No real request reaches
the constraint 409 that `constraintViolation` shows.

What real requests cannot reach is covered another way:
* **405.** It cannot be declared per operation, because the method is absent from the contract.
  The [Errors](#errors) table lists it, and the smoke test sends `DELETE /order/1`.
* **The constraint 409, the 500 and the 503 bodies.** They are validated synthetically against
  `POST /order` and `GET /order`. Nothing the check sends reaches the constraint 409 or a 500:
  there are no unique constraints, and `DatabaseFailureIT` produces the one real 500, a `POST`
  whose session is terminated at commit. The real 503s are captured on a `GET` only. The other
  409, a keyed `POST` repeated while its first request still runs, is reached for real (above).
* **Required headers.** `X-Request-Id` on every response, `Location` on every 201, `Retry-After`
  on every 503 and on the in-progress 409, and `ETag` and `Cache-Control` on every 200 and 304 of a
  `GET`. The validator enforces them, and synthetic responses without them pin that.
* **`additionalProperties: false`.** The three request schemas declare it themselves, because the
  validator injects it into every schema that lacks it, while a client built from the specification
  would not. A second validator that injects nothing checks that each `POST` schema rejects an `id`
  on its own.

[What the specification declares](ASSUMPTIONS.md#what-the-specification-declares) lists the
declarations, most of them added after a contract case failed without them.

## Continuous integration
GitHub Actions runs two jobs, `build` and `docker`, on every push to `main` and on every pull
request. On `main`, `docker` pushes the image it tested, not a rebuild.

* The workflow is `.github/workflows/ci.yml`.
* `build` tests the code. `docker` builds, scans and smoke-tests the image and, on `main`, pushes
  it.
* Permissions are per job: `build` has `contents: read`; `docker` has `contents: read` and
  `packages: write`.

**`build`** runs these steps:
1. `npm ci && npm test` in `utils`: the data generator's own check, on Node 22, with the npm cache
   keyed on `utils/package-lock.json`.
2. `./gradlew spotlessCheck build test --rerun --no-daemon`. `--rerun` executes the suite on every
   run, instead of replaying Gradle's cache when only documentation changed.
3. Uploads the test results and the coverage and SpotBugs reports as the `reports` artifact.

**`docker`** runs these steps:
1. Builds the image once as `store:ci`, loaded into the runner's daemon and labelled with the
   commit's metadata.
2. Scans it with Trivy, failing on a fixable `CRITICAL` or `HIGH` vulnerability. The findings table
   goes to the job log. The results are not uploaded to the Security tab: code scanning was not
   available while the repository was private, and no upload step has been added since it went
   public on 2026-10-22. Two advisories with no fix on the current Spring
   line are time-boxed exceptions in `.trivyignore`
   ([ADR 0004](adr/0004-spring-framework-6-cve-exceptions.md)).
3. Attaches an SPDX SBOM (software bill of materials) from Syft as the `sbom` artifact for 90 days
   (`retention-days: 90`; the action's own upload is off).
   [Vulnerability scan, SBOM and dependency line](ASSUMPTIONS.md#vulnerability-scan-sbom-and-dependency-line)
   says how to describe an image after that.
4. Starts the image with `STORE_IMAGE=store:ci docker compose up -d --no-build`, which runs
   `migrate` and returns once it has exited 0, then runs `scripts/smoke-test.sh ... compose`
   ([The smoke test](#the-smoke-test)). A failing step prints the `migrate` and `store` logs; the
   stack is always torn down.
5. On `main`, tags the same `store:ci` image and pushes it to GitHub Container Registry as
   `ghcr.io/<owner>/store:sha-<short sha>` and `:latest`. So the digest delivered is the digest
   scanned and smoke-tested.

The jar inside the image is compiled from the same commit by the image build (wrapper-pinned
Gradle, BOM-managed versions), while the tested jar is the `build` job's. So the artifact identity
is the commit, not the jar bytes.

### The smoke test
`scripts/smoke-test.sh` checks a started stack from the outside. It is POSIX `sh` and needs only
`curl`.

It always checks that:
* readiness reports `UP` on 8081 within 120 seconds, with the `db` component;
* 8080 serves `GET /order/1`, `POST /customer`, `GET /products/2` and a cursor page;
* 8080 replays a `POST /customer` repeated with its `Idempotency-Key` (same id,
  `Idempotent-Replayed: true`);
* 8080 answers 413 to a 70,000 byte body and 404 to `/actuator/health`;
* 8080 serves Swagger UI, its bundle and `/openapi.yaml`;
* liveness on 8081 answers 200.

The optional third argument `compose` adds checks that use `docker compose` and `docker inspect`,
so the script must then run from the repository root. They check that:
* the `migrate` container exited 0;
* `migrate` and `store` have a read-only root filesystem, a tmpfs on `/tmp`, `CapDrop` `ALL`,
  `no-new-privileges:true`, `Memory` and `MemorySwap` both 536870912 (512 MiB without swap), and a
  `json-file` log rotated at 3 files of 10 MB;
* every port `postgres` and `store` publish is bound to `STORE_BIND_ADDRESS` (127.0.0.1 when unset,
  so run the script with the value the stack was started with);
* `store_app` can `select count(*) from customer` through `psql`, while `create table` and
  `update customer` as `store_app` fail with `permission denied for schema public` and
  `permission denied for table customer`;
* `has_table_privilege` reports `UPDATE` and `DELETE` on `idempotency_key` for `store_app`;
* `DELETE /order/1` is a 405 problem detail;
* `GET /order/1` with `X-Request-Id: smoke-<pid>` leaves an ECS JSON access line in the `store`
  output, with the `com.example.store.access` logger, `"requestId":"smoke-<pid>"`, the route
  `/order/{id}`, and the `service` name and version;
* no `Picked up JAVA_TOOL_OPTIONS` line appears anywhere in that output.

Run it locally with:
```shell
docker compose up -d --build && sh scripts/smoke-test.sh http://localhost:8080 http://localhost:8081 compose
```
A local run leaves the `Smoke Test` and `Smoke Replay` customers in the Compose volume;
`docker compose down -v` clears them.

### Build cache and time limits
The image build keeps its dependencies in a cached layer of their own, and both jobs have time
limits far below GitHub's six-hour default.

* The image build warms its Gradle cache with `./gradlew resolveDependencies`, a task in
  `build.gradle` that downloads every jar of the compile, annotation-processor and runtime
  classpaths (96 jars, about 71 MB).
* That layer is keyed on the build files alone. So a change under `src` reuses it, and the bootJar
  step compiles without a download (it succeeds with `--offline`).
* A library that cannot be resolved fails the warm-up with
  `Could not resolve all files for configuration ...`, where Gradle's `dependencies` report would
  exit 0 and print `FAILED`.
* Both jobs carry a `timeout-minutes`: 20 for `build`, 30 for `docker`.
* The last 15 green push runs on `main` (run 39172594999 on 2026-10-18 to run 39289734712 on
  2026-10-19) took 2.0 to 3.0 minutes for `build` and 2.4 to 5.7 for `docker`, measured from job
  start to completion through the jobs API on 2026-10-19.
* A cold build cache can take `docker` to about 10 minutes.
* A hung Testcontainers pull, Gradle daemon or image pull therefore releases the runner within half
  an hour instead of six hours.

### Pulling the delivered image
Every merge to `main` publishes the tested image to GitHub Container Registry. The package is
public, so no login is needed.

Run the image on the Compose network, where PostgreSQL answers as `postgres`. The network is
`<project>_default`; the project name is the checkout directory unless `COMPOSE_PROJECT_NAME` sets
another, so a clone named `store` gives `store_default`:
```shell
docker pull ghcr.io/neeleshrambally/store:latest
docker compose up -d postgres
docker run --rm --network store_default -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8081 \
  -e DB_URL=jdbc:postgresql://postgres:5432/store \
  -e DB_USERNAME=admin -e DB_PASSWORD=admin -e LIQUIBASE_CONTEXTS=seed \
  ghcr.io/neeleshrambally/store:latest
```
* The image is built for `linux/amd64` only, the runner's platform. On an arm64 machine, such as
  Apple silicon, Docker runs it under emulation with a platform warning; `docker compose up --build`
  builds a native image instead.
* The Compose network works on every engine. `host.docker.internal` is Docker Desktop's; on Linux,
  `--add-host host.docker.internal:host-gateway` resolves to the bridge gateway, where a port
  published on 127.0.0.1 does not listen.
* Run alone like this, the image migrates in-process as the user it is given.
  `STORE_IMAGE=ghcr.io/neeleshrambally/store:latest docker compose up -d --no-build` runs it with
  the stack's `migrate` and `store_app` split instead.

### Dependency updates
Dependabot opens weekly pull requests, which the same pipeline validates.

| Ecosystem | Pull requests | Excluded |
|---|---|---|
| Gradle | Spring artifacts grouped | Majors |
| GitHub Actions | One grouped pull request, majors included | Majors of `gradle/actions/setup-gradle` |
| `Dockerfile` base images | One grouped pull request | `eclipse-temurin` majors |
| `compose.yaml` images | The `compose-images` group | Every version update of `postgres`, keeping the README's 16.2; `store` entirely, because Dependabot reads `${STORE_IMAGE:-store:local}` as a Docker Hub image |

* The `eclipse-temurin` exclusion governs only the build stage. The runtime
  `gcr.io/distroless/java17-debian13:nonroot` has no version in its tag, so it gets digest refreshes
  only. A move to another Java line is a change of image name, which Dependabot does not propose.
* Both `FROM` lines and `postgres:16.2` are pinned by the multi-arch index digest, with the tag kept
  for readability. So a rebuild or `compose up` resolves the same image until Dependabot refreshes
  the digest. A refresh carries no major, so the exclusions do not suppress it.
* Every action is pinned to the commit SHA of its release, with the version in a trailing comment.
  Dependabot moves both weekly, so a pin never freezes the pipeline. The cost is that Dependabot
  security alerts cover only semver-referenced actions, so the weekly update and the comment are the
  review path.
* `gradle/actions/setup-gradle` stays on v5, because v6 moved the build cache into a closed-source
  component with separate terms of use.
* The Gradle wrapper carries `distributionSha256Sum`, so every distribution is verified before it is
  unpacked, independently of `validateDistributionUrl`. The image build stage downloads on every
  build; runners and developers verify once and reuse the cache. Adding the key invalidated no
  cache.

### The GitLab pipeline
`.gitlab-ci.yml` gives GitLab the same gate, for portability. It has never run, because there is
no GitLab project.

* It passes GitLab's CI JSON schema (`app/assets/javascripts/editor/schema/ci.json` in
  `gitlab-org/gitlab`), which checks keyword names and value types, not what jobs do.
* Each job carries the time limit of the GitHub job it stands for.

| Job (stage) | What it does | Timeout |
|---|---|---|
| `build` (build) | `./gradlew spotlessCheck build test --rerun` | 20 min |
| `generator-test` (build) | `npm ci && npm test` in `utils` on `node:22-alpine` (the `build` image has no Node) | 20 min |
| `package` (package) | Builds `store:ci` once, saves it as the `image.tar` artifact, labels it with `org.opencontainers.image.revision` (`$CI_COMMIT_SHA`), `org.opencontainers.image.source` (`$CI_PROJECT_URL`) and `org.opencontainers.image.created` (`$CI_PIPELINE_CREATED_AT`) | 30 min |
| `smoke` (smoke) | Loads the tarball, starts the stack inside `docker:27-dind` (Docker-in-Docker) with `STORE_BIND_ADDRESS: "0.0.0.0"`, runs the same script against `http://docker:8080` and `http://docker:8081` | 30 min |
| `scan` (scan) | Trivy 0.70.0 (what `aquasecurity/trivy-action@v0.36.0` installs by default) on the tarball, with the same severity, `--ignore-unfixed` and `.trivyignore` settings | 30 min |
| `publish` (publish) | Default branch only: retags the tarball's image as `$CI_REGISTRY_IMAGE:<short sha>` and `:latest`, and pushes both after `package`, `smoke` and `scan` | 30 min |

How it runs:
* Only `publish` touches the registry name, so a merge-request pipeline without a container
  registry still builds, smokes and scans.
* Every job is `interruptible` (under `default:`) except `publish`.
* The default-branch entry of `workflow: rules` sets `auto_cancel: on_new_commit: none`. So a new
  commit cancels a running merge-request pipeline and never a default-branch one, as GitHub's
  `cancel-in-progress` does for pull requests only.
* `publish` holds the `registry-publish` resource group, so two pipelines never push at once.

#### Differences from the GitHub workflow
Thirteen details differ:

| # | Detail | GitHub Actions | GitLab |
|---|---|---|---|
| 1 | Trigger scope | Pushes to `main` and pull requests | `workflow: rules`: merge requests and the default branch |
| 2 | Published tag | `sha-<short sha>`, written by `docker/metadata-action` | `<short sha>` |
| 3 | SBOM | SPDX from Syft, kept 90 days (`retention-days: 90`) | CycloneDX from Trivy, kept 90 days (`expire_in: 90 days`). GitLab also keeps the artifacts of the newest successful pipeline of each ref past that, unless the project or instance turns it off |
| 4 | Job layout | Scan, SBOM and smoke test in sequence in one `docker` job | `smoke` and `scan` both `needs: [package]` and run in parallel; `publish` needs all three, so the gate is the same |
| 5 | `build` artifacts | `build/reports/` and `build/test-results/` | Only `build/reports/` as a path artifact; JUnit XML goes through `reports: junit` |
| 6 | Pinning | Every action SHA-pinned and moved weekly | The job images (`eclipse-temurin:17-jdk-jammy`, `node:22-alpine`, `docker:27`, `aquasec/trivy:0.70.0`) are tag-pinned, with no Dependabot path |
| 7 | Teardown | `docker compose down -v --remove-orphans` | `docker compose down -v`, without `--remove-orphans` |
| 8 | Bind address of the smoke stack | Loopback | `STORE_BIND_ADDRESS: "0.0.0.0"` (see below) |
| 9 | Layer cache for the image build | Layers restored from the `gha` cache (`cache-from`, `cache-to`) | None: each `package` job starts an empty `docker:27-dind`, so every build pulls the base images and reruns the Gradle warm-up |
| 10 | Runs so far | Every pull request and every push to `main` | None |
| 11 | Publish order | `main` runs go one at a time, and a newer run replaces a waiting one, so the newest merge pushes last | The resource group's default `unordered` mode only keeps two publishes apart (see below) |
| 12 | OCI labels | Eight, from `docker/metadata-action`: `title`, `description`, `url`, `source`, `version`, `created`, `revision`, `licenses` | Three, set by `package`: no title, description, url, version or licenses, and `created` is the pipeline's creation time rather than the metadata step's |
| 13 | Cancelling a superseded run | The workflow's `concurrency` block decides it alone | Also depends on the project's "Auto-cancel redundant pipelines" setting, which the file cannot set; with it off, `interruptible` has no effect |

On both platforms, a push to a feature branch without a pull or merge request runs no pipeline, and
the scan prints the findings table and stores no report.

**The bind address (row 8).** On the Docker executor, `docker:27-dind` is a separate container
reached as `docker`. A port it publishes on its own 127.0.0.1 refuses the job container, so `smoke`
publishes on `0.0.0.0`, and the script expects the same value.
* The ports open on the dind container's interfaces, not the runner host's.
* On a Kubernetes executor the job and services share the pod's network namespace, so loopback
  would work. `0.0.0.0` also opens the ports on the pod IP for the length of the job, which is
  acceptable for an ephemeral job.

**Publish order (row 11).** The default mode lets an older pipeline push `:latest` after a newer
one. Pipeline order needs `process_mode` set to `oldest_first`, once, through the API
(`PUT /projects/:id/resource_groups/registry-publish`); the file cannot express it.

## Data model
The schema holds customers, orders, products, the link between orders and products, and the
idempotency keys of the `POST`s.

* An order has an id and a description, and belongs to the customer that placed it. An order
  created through the API contains 1 to 100 products. An order that pre-dates changeset 5 may have
  none ([Model and endpoints](ASSUMPTIONS.md#model-and-endpoints)).
* A customer has an id, a name, and zero or more orders.
* A product has an id and a description, and can appear in any number of orders, through the
  `order_product` join table.
* An `idempotency_key` row records one `POST` sent with an `Idempotency-Key`, keyed by client and
  key. It holds the fingerprint of the request and the response to replay, until the row expires
  ([ADR 0010](adr/0010-idempotency-keys.md)).

Beyond the primary keys, the schema has these indexes. [Indexes](PERFORMANCE.md#indexes) gives the
changeset and effect of each read-path index, [ADR 0010](adr/0010-idempotency-keys.md) the expiry
index, and [ADR 0009](adr/0009-index-changesets.md) how an index changeset locks its table.

| Index | On | Serves |
|---|---|---|
| `idx_order_customer_id` | `order (customer_id)` | Lookups of orders by customer |
| `idx_order_customer_id_id` | `order (customer_id, id)` | The `customerId` filter of `GET /order`, as one index range in id order |
| `idx_order_product_product_id_order_id` | `order_product (product_id, order_id)`, beside the primary key on `(order_id, product_id)` | The `productId` filter, and a product's order ids in id order |
| `idx_customer_name_trgm` | `lower(customer.name)` with trigrams (a GIN index from `pg_trgm`) | The case-insensitive substring search on `name` |
| `idx_order_description_id`, `idx_product_description_id`, `idx_customer_name_id` | Each whitelisted sort column together with `id` | A sorted page as an index scan instead of a sort of the whole table |
| `idx_idempotency_key_expires_at` | `idempotency_key (expires_at)` | The sweep that deletes expired keys |

## API
The contract is `OpenAPI.yaml`. Its version, 3.1.0, is the contract's own, not the build's.
[Contract version and client migration](ASSUMPTIONS.md#contract-version-and-client-migration) lists
what a client written against the delivered 1.0, or against 2.1.0 or 3.0.0, has to change.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/customer?page=&size=&sort=` | Page of customers, each with its newest orders and `orderCount` |
| `GET` | `/customer?after=&size=` | Cursor page of customers with an id greater than `after` |
| `POST` | `/customer` | Create a customer: `{"name": "..."}` |
| `GET` | `/customer?name=onne` | Customers whose name contains the word fragment (single word, case-insensitive) |
| `GET` | `/customer/{id}` | A single customer with its newest orders and `orderCount` |
| `GET` | `/order?page=&size=&sort=` | Page of orders, each with its customer and products |
| `GET` | `/order?after=&size=` | Cursor page of orders with an id greater than `after` |
| `GET` | `/order?customerId=1` | The orders of one customer, in either paging mode; an unknown id is an empty page |
| `GET` | `/order?productId=2` | The orders containing one product, in either paging mode; an unknown id is an empty page |
| `GET` | `/order/{id}` | A single order with its customer and products |
| `POST` | `/order` | Create an order: `{"description": "...", "customerId": 1, "productIds": [2, 8]}` |
| `GET` | `/products?page=&size=&sort=` | Page of products, each with the ids of the newest orders containing it and `orderCount` |
| `GET` | `/products?after=&size=` | Cursor page of products with an id greater than `after` |
| `GET` | `/products/{id}` | A single product with the ids of the newest orders containing it and `orderCount` |
| `POST` | `/products` | Create a product: `{"description": "..."}` |

### API documentation
Swagger UI at http://localhost:8080/swagger-ui.html renders `OpenAPI.yaml` exactly as checked in,
so the page shows the contract that `OpenApiContractIT` enforces.

* The build copies the file into the jar, and the service serves it at `/openapi.yaml`. Nothing is
  generated from the code.
* "Try it out" sends requests to the `servers` entry, `http://localhost:8080`.
* The page loads the Swagger UI WebJar from the service itself, and makes no call to the public spec
  validator.
* The management port, which shares the web configuration, serves the same page. From there, "Try
  it out" would be a cross-origin request to 8080, which the API does not allow.
* `SWAGGER_UI_ENABLED=false` removes the page and the file
  ([ADR 0011](adr/0011-swagger-ui-from-the-contract.md)).

### Paging, sorting and filters
Every list is paged. A client pages by offset with `page` or by cursor with `after`, and can filter
`GET /order` by customer or by product.

**Page envelope.** List responses carry `content`, `page`, `size`, `totalElements` and
`totalPages`. The default size is 20, the maximum 100, and the default sort `id`.

**Sorting.** `sort` takes `property,(asc|desc)`. It accepts only `id` and `description` on `/order`
and `/products`, and `id` and `name` on `/customer`. Ties are broken by `id` in the same direction
as the last sort property, so page boundaries are deterministic
([ADR 0006](adr/0006-list-projections-and-sort-whitelist.md)).

**Cursor paging.** `after` switches to keyset (cursor) pagination: paging by the last id seen
instead of by page number ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Keyset
pagination").
* The response is a cursor envelope (`content`, `size`, `nextCursor`), holding the elements with an
  id greater than `after` in ascending id order.
* `nextCursor` is the last element's id while more exist, and `null` when the walk is complete.
* `after=0` starts a walk. `size` keeps its default and cap, and `name` still filters customers.
* `after` combined with `page` (other than 0), or with a `sort` whose first property is not `id`
  ascending, is a 400 (`after cannot be combined with page or sort`).

**Filters.** `GET /order` also takes `customerId` or `productId` (each at least 1), in both paging
modes and with the same `sort` whitelist.
* An unknown id yields an empty page, not a 404.
* Both together are a 400 (`customerId and productId cannot be combined`).

**Nested orders.** The two filters are the uncapped lists behind the nested `orders`
([ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Nested collections").
* A customer on the list and by-id responses carries its newest `NESTED_ORDERS_LIMIT` orders (20 by
  default; the highest ids, in ascending order) and `orderCount`, the total.
* A product on the list and by-id responses carries the ids of the newest orders containing it in
  the same way, plus `orderCount`.

### Creating resources
Writes follow six rules. Four shape every `POST`, one applies to `POST /order` only, and the last
is that no other write method exists.

* **Content type.** A `POST` reads only `application/json` (`consumes`; a `charset` parameter is
  accepted).
  * Any other `Content-Type` (a URL-encoded or multipart form, text, or a JSON-based type like
    `application/vnd.api+json`), or none, is a 415 decided at mapping, before the body is read.
  * The 415 carries `Accept: application/json` and a detail that names the type sent
    (`Content-Type 'text/plain;charset=UTF-8' is not supported.`), `Content-Type is missing.` or
    `Could not parse Content-Type.`
* **Body size.** A JSON body over `REQUEST_MAX_BODY_BYTES` (64 KiB by default) is a 413
  (`Request body exceeds 65536 bytes`).
  * It is judged by the declared `Content-Length` before any byte is read, or by the bytes counted
    while a chunked body is read.
  * Tomcat appends `Connection: close`.
* **Products.** An order carries 1 to 100 products, each id at most once. A repeated id is a 400
  naming `productIds`, not a silent de-duplication.
* **Response.** Every `POST` answers 201 with a `Location` holding the relative path of the created
  resource (`/order/{id}`, `/products/{id}`, `/customer/{id}`). It is relative so that a gateway
  rewriting the host stays correct, which RFC 9110 permits.
* **Idempotency.** A `POST` may carry an `Idempotency-Key` header. The store, the claim token and
  the stale window are in [ADR 0010](adr/0010-idempotency-keys.md).
  * The key is 1 to 255 visible ASCII characters, compared exactly. Anything else is a 400
    (`invalid_idempotency_key`).
  * The first request with a key runs as usual, and its 201 is stored for `IDEMPOTENCY_TTL`
    (24 hours).
  * A repeat with the same key and body gets the stored status, `Location` and body back, with
    `Idempotent-Replayed: true`, and creates nothing.
  * The same key with another body or on another path is a 422 (`idempotency_key_reused`).
  * A repeat while the first request still runs is a 409 (`idempotency_request_in_progress`,
    `Retry-After: 1`).
  * An error response is never stored, so the key can be sent again with a corrected request.
  * A key whose request never finished is taken over after `IDEMPOTENCY_STALE_AFTER` (30 s). A
    request taken over while it still runs is rolled back and answered 409.
  * Until authentication ([ADR 0002](adr/0002-authentication-deferred.md)) supplies a client
    identity, every caller shares one key scope, so keys must be random (UUIDs).
  * Without the header nothing changes.
* **No `PUT`, `PATCH` or `DELETE`.** Such a request is the 405 decided at mapping (a 404 for an
  unknown path), whatever body it carries, and the body is not read.

### Unread bodies and silent clients
Tomcat discards what remains of a rejected body without parsing it. A client that then goes silent
loses its request thread after 10 s.

* After a 413, 415, 405 or 404, Tomcat reads and discards the unread remainder on the request's
  thread, up to its 2 MB swallow limit, and keeps none of it.
* Past 2 MB it closes the connection instead, as the answer's `Connection: close` announced.
* The thread stays busy until the rest arrives, or until the client has sent nothing for
  `server.tomcat.connection-timeout`, 10 s.

**Why 10 s.** With Tomcat's default of 60 s, 200 silent clients held all 200 request threads for a
minute. On the jar of 5996b3c:
* 200 form `POST`s declaring 1,900,000 bytes and sending 8 KiB were all answered 415 within 0.03 s,
  and readiness still answered 200;
* a `GET /order/1` sent next was answered after 60.05 s, and with the 10 s timeout after 9.89 s;
* a silent client was disconnected after 10.0 s instead of 60.0 s.

**What the timeout does not bound.**
* It bounds a silent client only. It does not bound one that trickles bytes (one byte every 8 s
  kept its thread for the 40 s it went on), or one that keeps opening connections. Request buffering
  or a rate limit at the ingress bounds those.
* Idle keep-alive connections are still closed after 60 s (`server.tomcat.keep-alive-timeout`).
  Otherwise the connection timeout would apply to them too (with it alone, an idle connection closed
  after 11.0 s), and an ingress that reuses idle connections longer would find them closed under it.

**Bodies read before mapping.** Three readers used to get at a body before the request was mapped,
two of them past `RequestBodyLimitFilter`'s bounded stream. Each is now closed;
[Request body bounds](ASSUMPTIONS.md#request-body-bounds) has the measurements.
* **Tomcat's form parsing.** Tomcat parsed a URL-encoded form `POST` itself (Spring builds such a
  body from the request parameters), up to its 2 MB form limit.
  * Now `server.tomcat.max-http-form-post-size` follows `REQUEST_MAX_BODY_BYTES` on both
    connectors, and over it Tomcat does not read the body at all.
  * The limit is needed because anything that asks for a `POST`'s parameters has Tomcat parse such
    a body, `consumes` or not. The hidden-method filter (`spring.mvc.hiddenmethod.filter.enabled`)
    and `spring.mvc.log-request-details` with the `DispatcherServlet` at DEBUG both made Tomcat
    read a 1,900,000-byte body again.
  * The property cannot switch parsing off with 0: Spring Boot skips a zero and Tomcat keeps 2 MB.
* **Multipart.** The `DispatcherServlet` parsed any `multipart/*` request, whatever its method,
  before mapping it, up to 1 MB a part and 10 MB a request, and spooled its parts under
  `java.io.tmpdir`. Multipart is now off (`spring.servlet.multipart.enabled: false`). With no
  resolver and no servlet multipart configuration, neither Spring nor Tomcat parses such a body,
  and it is a 415 like any other type.
* **Spring's `FormContentFilter`.** On by default, it read the URL-encoded body of every `PUT`,
  `PATCH` and `DELETE` through the bounded stream before the 405 or 404. Over the bound its
  exception escaped the servlet: a 500 with Spring Boot's default error body, not a problem detail,
  and an ERROR stack trace. No endpoint takes such a body, so the filter is off
  (`spring.mvc.formcontent.filter.enabled: false`).

### Errors
Every error that Spring MVC handles on the API connector is an RFC 7807 problem detail, the standard
JSON error body. Clients branch on its `code`, never on `detail`.

* `title` holds the status phrase, `detail` a human-readable message, and `code` the stable
  identity of the problem.
* `type` stays `about:blank`; [Responses and errors](ASSUMPTIONS.md#responses-and-errors) gives the
  reason.
* Clients must accept a code they do not know, and handle it by status. Adding a code is a minor
  version of the contract; removing or renaming one is a major version.
* Validation failures (of a body or a query parameter) add an `errors` array naming each offending
  field with its constraint message.

| `code` | Status | Raised for |
|---|---|---|
| `validation_failed` | 400 | A body field or a query parameter that breaks a constraint, or a body value of the wrong JSON type or beyond the 64-bit range; `errors` names each |
| `invalid_parameter` | 400 | A path or query parameter that does not convert (`GET /order/abc`, `GET /order/0x1`, `GET /order/01`, `page=abc`), a query string Tomcat cannot parse in full on a list endpoint (`customerId=1%`), or a repeated single-valued query parameter (named in `errors`) |
| `unknown_property` | 400 | A body property the request does not define (named in `errors`) |
| `malformed_body` | 400 | A body that is not one readable JSON object (malformed JSON, another JSON value, a repeated property, content after the object) |
| `invalid_sort` | 400 | A `sort` property outside the endpoint's list |
| `invalid_cursor` | 400 | `after` combined with a `page` other than 0 or a `sort` whose first property is not `id` ascending |
| `conflicting_filters` | 400 | `customerId` combined with `productId` |
| `invalid_idempotency_key` | 400 | An `Idempotency-Key` header that is empty, longer than 255 characters or not all visible ASCII |
| `not_found` | 404 | An unknown id in the path, an unknown customer or product in a `POST /order` body, or a path without a handler |
| `method_not_allowed` | 405 | A method the path does not support |
| `not_acceptable` | 406 | An `Accept` header naming no media type the endpoint produces |
| `conflict` | 409 | A write that a database constraint rejects |
| `idempotency_request_in_progress` | 409 | A `POST` repeated with an `Idempotency-Key` whose first request is still running, or a keyed `POST` whose claim was taken over while it ran, which is rolled back (with `Retry-After: 1`) |
| `payload_too_large` | 413 | A JSON body over `REQUEST_MAX_BODY_BYTES` |
| `unsupported_media_type` | 415 | A `POST` whose `Content-Type` is missing or is not `application/json`, decided before the body is read |
| `idempotency_key_reused` | 422 | An `Idempotency-Key` sent again with another body or on another path |
| `internal_error` | 500 | An unexpected failure (no `detail`) |
| `service_unavailable` | 503 | A database connection that cannot be obtained, or one lost during the request, including one cut by `DB_SOCKET_TIMEOUT`. Also a by-id read or a `POST`'s insert attempted after the `TX_TIMEOUT` deadline. Sent with `Retry-After`. One loss is `internal_error` instead: a session the database terminates while a `POST`'s insert is flushed at commit ([Database failures](#database-failures)) |
| `database_timeout` | 503 | A database statement cancelled at `DB_STATEMENT_TIMEOUT` or at the deadline `TX_TIMEOUT` sets, or a query issued after that deadline (with `Retry-After`) |

**How the code is set.**
* `GlobalExceptionHandler` sets the code in its own handlers.
* The problem details that Spring MVC's `ResponseEntityExceptionHandler` builds (a type mismatch, a
  path without a handler, 405, 406, 415) return through `handleExceptionInternal`. Its override
  takes the code from the status: 400 `invalid_parameter`, 404 `not_found`, the names above for
  405, 406, 413, 415 and 503, and `internal_error` otherwise.
* `OpenAPI.yaml` declares `code` as a required enum, so the contract check fails on a missing or
  unlisted code.

**Edge cases.** The management connector shares the advice, so a 404 or 405 there carries `code`
but no `X-Request-Id`, because the filters run on the API connector only. A direct `GET /error`
gets Boot's error controller's 500: `{"timestamp":"...","status":999,"error":"None"}`.

**Media types.**
* Every operation answers in `application/json`, and a problem detail in
  `application/problem+json`; the controllers declare both as `produces`.
* An `Accept` that admits neither is a 406 (`not_acceptable`,
  `Acceptable representations: [application/json, application/problem+json].`), decided at
  mapping, before validation or the body.
* Spring used to run the handler first: a `POST /order` with `Accept: application/xml` created the
  order and then answered 406, and a `GET` of an unknown id or with an invalid parameter answered
  404 or 400.
* `*/*`, as browsers and curl send, is served JSON.

### Requests Tomcat rejects itself
A few malformed requests never reach the service. Tomcat answers them before the servlet filters
with its own minimal HTML page (`text/html;charset=utf-8`, `HTTP Status 400 – Bad Request`, no
server version), with no `X-Request-Id` and no access line.

It does so for:
* a request line and headers over the 8 KB default of `server.max-http-request-header-size`;
* a request line that does not parse (a target with a character not allowed in it, such as an
  unencoded `|` or space);
* a header holding a control character;
* a path that cannot be percent-decoded (`/order/%ZZ`, `/order/1%`);
* a path whose decoded form holds a NUL (`%00`), an encoded slash or backslash (`%2F`, `%5C`), or
  bytes that are not UTF-8 (`%FF`).

**What Tomcat logs.**
* Parse failures of the request line or a header are logged at INFO on
  `org.apache.coyote.http11.Http11Processor`, with a stack trace that quotes what was rejected:
  * the whole target, query string included
    (`Invalid character found in the request target [/customer?name=...|x ]`, with non-ASCII bytes
    in hex such as `0xc3`);
  * the word after an unencoded space;
  * or the header line.
* So a search word sent with an unencoded `|` or space reaches the log, while an oversized line or
  header is logged only as `Request header is too large`.
* Each connection processor logs its first such failure at INFO, and later ones in the next 24
  hours at DEBUG. The connector adds processors when all are busy, so a busy server writes several
  INFO lines.
* Undecodable or rejected paths are not logged at the shipped levels.

**Query strings are different.**
* A query string is parsed only when the service asks for its parameters, after the filters. So a
  failure there is a problem detail on the list endpoints
  ([Query strings Tomcat cannot parse](#query-strings-tomcat-cannot-parse)).
* Tomcat logs it at INFO on `org.apache.tomcat.util.http.Parameters`, on the request's thread, so
  with `requestId`. The line quotes:
  * for an escape it cannot decode, the parameter's name and value:
    `Character decoding failed. Parameter [name] with value [Secret%ZZWord] has been ignored. ...`
  * for a value without a name, the chunk:
    `Invalid chunk starting at byte [0] and ending at byte [7] with a value of [=Secret] ignored`
* One JVM-wide throttle writes the first such line after start-up at INFO, and the rest for 24
  hours at DEBUG.

### Strict input
Request input is strict rather than coerced ([ADR 0002](adr/0002-authentication-deferred.md)), and
requests never carry an id ([ADR 0001](adr/0001-layered-architecture-and-dtos.md)). Most rules
replaced a Spring, Jackson or Tomcat default that silently coerced, merged or dropped bad input.
[API contract](ASSUMPTIONS.md#api-contract) explains why a single reading of each request matters
behind a gateway.

#### JSON bodies
* **Unknown property:** a 400 (`Unknown property 'id'`, with the `errors` entry
  `is not a known property`).
* **Wrong JSON type:** a 400 (`Invalid request content.`) whose `errors` entry names the field by
  path (`productIds[0]`) with `must be an integer`, `must be a string` or `must be an array`. An
  integer outside the 64-bit range (`9223372036854775808`) gets `must be a 64-bit integer`.
* **Not one readable object:** malformed JSON, a body that is not an object, a repeated property,
  content after the object, or a number of more than 1,000 digits is a 400
  (`Failed to read request`, no `errors`). Two Jackson settings are on for a reason:
  * `spring.jackson.deserialization.fail-on-trailing-tokens`, because an order body followed by a
    stray `x` used to create the order;
  * `spring.jackson.parser.strict-duplicate-detection`, because `{"customerId":5,"customerId":6}`
    used to create an order for customer 6. A repetition after every field had appeared once was a
    500, since Jackson had already built the record.
* **Exact JSON types.** A string, fraction or exponent where an integer is expected (`"5"`, `""`,
  `5.7`, `5.0`, `5e0`), or a number or boolean where a string is expected, is a type failure.
  * Jackson's defaults would coerce these: `"customerId": 5.7` used to create an order for
    customer 5.
  * `spring.jackson.deserialization.accept-float-as-int` and
    `spring.jackson.mapper.allow-coercion-of-scalars` are off, and `JacksonConfig` fails the
    number-to-text and boolean-to-text coercions those leave on.
  * Jackson stops at the first field it cannot bind, so `errors` lists one type failure at a time.
* **Single-line text.** Body strings (`description`, `name`) are single-line. A control character
  (U+0000 to U+001F or U+007F, so also a tab or line break) is a 400 naming the field
  (`must not contain control characters`). Before this rule, a NUL reached the insert and came back
  as a 409, and a tab or line break was stored.

#### Search words
* `name` on `GET /customer` is one word. ASCII whitespace, a C0 control or DEL is a 400 naming
  `name` (`must be a single word without whitespace or control characters`); a NUL used to come
  back as a 409.
* A no-break space, C1 controls such as U+0085, and the line separator U+2028 are part of the word,
  in server and specification alike
  ([Customer name search](ASSUMPTIONS.md#customer-name-search-readme-task-2)).

#### Numbers in paths and queries
* **Ranges.** `page` below 0 and `size` below 1 are 400s naming the parameter
  (`must be greater than or equal to 0` or `1`), in both paging modes. Spring Data used to normalise
  them silently (`page=-1` became 0, `size=0` the default 20). `size` above 100 is still capped at
  100.
* **The largest page.** `page` above 21474836 is a 400 (`must be less than or equal to 21474836`).
  * The offset, `page` times `size`, reaches the query as a 32-bit `int` (JPA's `setFirstResult`
    and the product filter's native query), and 21474836 is the largest page that fits at size 100.
  * An offset past 2,147,483,647 used to be a 500: `/order?page=107374183` at the default size
    failed with `Page offset exceeds Integer.MAX_VALUE`, the product filter with an integer
    overflow.
  * Further positions are reached with `after`.
* **Canonical decimal form.** The path `id` and the query parameters `after`, `customerId`,
  `productId`, `page` and `size` are integers in canonical decimal form: ASCII digits, no plus, no
  leading zeros, and a minus only before a non-zero digit (the one spelling `Long.toString`
  writes).
  * Anything else is the type-mismatch 400 (`invalid_parameter`,
    `Failed to convert 'id' with value: '0x1'`, no `errors`). That covers `abc`, `0x1`, `#1`, `1 0`,
    `+1`, `01`, `-0`, a digit outside ASCII such as U+0661 or U+FF11, leading or trailing whitespace
    (which is also what the unencoded plus of `page=+2` decodes to), and a value outside the 64-bit
    range (32-bit for `page` and `size`).
  * A negative value reaches its parameter's range check. A blank path id is a 400
    (`Required path variable 'id' is not present.`).
* **How the form is enforced.** `DecimalParameterBinding` checks the untrimmed text against
  `0|-?[1-9][0-9]*` before `Long.valueOf` or `Integer.valueOf`.
  * It sees the text after percent-decoding, and after the `;` parameters of each path segment are
    removed. So `/order/%31` and `/order/1;x=1` are order 1
    ([ADR 0002](adr/0002-authentication-deferred.md) says what that means for a gateway).
  * It exists because the parse alone accepted a plus sign, leading zeros and non-ASCII digits, and
    Spring's number conversion strips whitespace and reads `0x` and `#` as hexadecimal.
    [Numbers in canonical form](ASSUMPTIONS.md#numbers-in-canonical-form) shows what each spelling
    returned before.
  * It is an editor on the binder, not a converter, because the binder retries a failed conversion
    with its lenient default editor.

#### Empty and repeated parameters
* **Empty counts as absent.** An empty numeric query value counts as absent, as for Spring Data's
  resolver. So does one of whitespace only, as `Character.isWhitespace` defines it: space, tab,
  line feed, vertical tab, form feed, carriage return, U+001C to U+001F, and the Unicode space,
  line and paragraph separators other than the three no-break spaces, such as U+2003.
  * So `customerId=` does not filter, `after=%20` asks for an offset page, and `page=` and
    `size=%09` take the defaults.
  * `OpenAPI.yaml` states this in the five parameter descriptions, because an integer schema cannot
    express it and the validator honours `allowEmptyValue` only for string and array parameters.
* **No repeats.** A single-valued query parameter (`name`, `customerId`, `productId`, `after`,
  `page`, `size`) that is repeated is a 400 (`'name' must not be repeated`, named in `errors`).
  * Spring used to keep the first number and join repeated strings with commas.
  * The check runs after mapping and before binding, so it is reported even when a value is also
    invalid.
  * `sort` may be repeated, and a comma inside a `name` stays part of the word.

#### Query strings Tomcat cannot parse
* A query string Tomcat cannot parse in full is a 400 on the three list endpoints
  (`invalid_parameter`, `The query string could not be parsed`, no `errors`). That means:
  * a `%` not followed by two hexadecimal digits, in a value or a name (`customerId=1%`,
    `customerId=%ZZ1`, `name=50%`, `%ZZ=1`);
  * or a value without a name (`=1`).
* Tomcat drops such a parameter and sets `org.apache.catalina.parameter_parse_failed`. The list
  endpoints used to run without it. On the seed:
  * `/order?customerId=1%` listed all 10,000 orders (customer 1 has 96);
  * `/order?productId=%ZZ2` listed all 10,000 (product 2 has 200);
  * `/customer?name=50%` and `/customer?name=Secret%ZZWord` listed all 100 customers;
  * an undecodable `after`, `page`, `size` or `sort` counted as absent;
  * and `customerId=1&customerId=%ZZ` passed the repetition check.
* `MalformedQueryInterceptor` runs before the repetition check, on every handler that binds a query
  parameter. It rejects the request when the attribute is set, whichever parameter failed, even one
  the endpoint does not read. It has to, because:
  * Tomcat does not say which parameter it dropped;
  * the bad part can be the name;
  * only the first failure's reason is recorded (for `=1&customerId=1%` it is the value without a
    name, while `customerId` was dropped as well).
* The by-id reads and the `POST`s bind no query parameter, so the service never has Tomcat parse
  their query string, and `/order/1?customerId=1%` is order 1. Parsing a `POST`'s parameters would
  also make Tomcat read a form body, which is why a form `POST` is rejected first.
* A `%` in a search word is sent as `%25`.
* Bytes that are not UTF-8 are not a parse failure: `%FF` decodes to U+FFFD, which `customerId`
  rejects as not decimal and `name` searches for.
* The same escapes in the path are Tomcat's HTML 400
  ([Requests Tomcat rejects itself](#requests-tomcat-rejects-itself)).

### Caching
Every successful `GET` can be revalidated by its ETag, and two by-id reads are also cached in the
service ([ADR 0007](adr/0007-caching.md)).

* Every successful `GET` carries a weak `ETag` (a hash of the representation) and
  `Cache-Control: no-cache`. A client that sends the ETag back in `If-None-Match` gets
  `304 Not Modified`, with no body, when the representation is unchanged.
* `GET /order/{id}` and `GET /products/{id}` are also cached in the service for `CACHE_TTL`.
* `products` entries are evicted after an order containing the product commits, so the next read
  normally lists the new order.
* A read that missed the cache before that commit can still store the old value after the eviction.
  The entry then stays stale for up to `CACHE_TTL`.
* `GET /customer/{id}` is not cached: its `orderCount` changes on every `POST /order`, and nothing
  keys customers for eviction. So each read costs its 2 statements.

[Caching](PERFORMANCE.md#caching) has the measured effect of both layers.

## Operations
This section describes how the service behaves in production.
[ADR 0008](adr/0008-operational-posture.md) records why it runs this way. In short:

* API on 8080; probes and metrics on 8081 ([Ports, probes and metrics](#ports-probes-and-metrics)).
* An `X-Request-Id` and an access line for every request, in ECS JSON logs
  ([Request ids and filters](#request-ids-and-filters), [Logs](#logs)).
* Database failures are 503s with `Retry-After` ([Database failures](#database-failures)).
* A 20 s graceful drain ([Shutdown](#shutdown)), and migrations as a job
  ([Start-up and migrations](#start-up-and-migrations)).
* A hardened distroless image with a measured memory limit ([The image](#the-image),
  [Container hardening and memory](#container-hardening-and-memory)).

### Ports, probes and metrics
The actuator has its own port, 8081, which the ingress never exposes. Probes and metrics are
reachable inside the cluster, but never from the public network.

* The actuator is on a separate management connector, 8081 by default (`MANAGEMENT_PORT`), and is
  absent from 8080: `/actuator/health` there is a 404.
* The ingress exposes 8080 only.

**Probes.**

| Probe (on 8081) | What it reports |
|---|---|
| `GET /actuator/health` | The overall status, detail-free |
| `GET /actuator/health/liveness` | Spring's `LivenessState` |
| `GET /actuator/health/readiness` | `ReadinessState` combined with the database indicator, so a replica that has lost its database leaves rotation. It names its components without details: `{"status":"UP","components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}}}` |

* `ReadinessState` is `ACCEPTING_TRAFFIC` once the context has started, and is withdrawn when
  shutdown begins. That by itself moves no traffic away ([Shutdown](#shutdown)).
* Neither probe proves the API connector answers; the pipeline's smoke test does.

**Build information.** `GET :8081/actuator/info` reports `build.artifact`, `build.name`,
`build.group` and `build.version` from `META-INF/build-info.properties`. That file is generated
without the build time, so a rebuild of unchanged sources is cacheable.

**Metrics.** `GET :8081/actuator/prometheus` is for scraping.
* `cache_gets_total{cache=...,result=hit|miss}` gives the hit rate of the `orders` and `products`
  caches.
* `http_server_requests_seconds` covers the API port only. It carries cumulative latency buckets at
  5, 10, 25, 50, 100, 250 and 500 ms and 1, 2.5, 5 and 10 s
  (`management.metrics.distribution.slo.http.server.requests`).
* So this query gives the p99 per route across replicas, and the share of requests within a bound
  is read from its bucket:
  ```text
  histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))
  ```
* The cost is 12 `_bucket` lines per series: one per bound plus `+Inf`. A series is one combination
  of method, `uri`, status, outcome and exception. That is about 1.9 KB of scrape per series:
  27,233 B for the 14 series left by the smoke test and a 1,000-request mix on the Compose stack.
* `percentiles-histogram` would publish Micrometer's generic set instead: 69 lines (about 11.6 KB)
  per series.
* `ActuatorIT` pins the bucket set.

### Request ids and filters
Every response carries an `X-Request-Id`, and every log line written for the request carries the
same id as `requestId`. So the id a client quotes finds the request's log lines.

* The one exception is the 400 Tomcat sends itself
  ([Requests Tomcat rejects itself](#requests-tomcat-rejects-itself)).
* An inbound value matching `[A-Za-z0-9._-]{1,128}` is echoed; otherwise a UUID is generated.
* `RequestIdFilter` runs before every other filter, so 304, 400, 404 and 500 responses carry it.
* It puts the id in the MDC (the thread's logging context) as `requestId` for the request's
  duration.
* There is no tracing library, because no collector is in scope. The header is where a gateway's or
  a later tracer's id attaches.

**Which filters apply where.**
* `server.compression` applies to both connectors.
* The `ETag` filter and the `Cache-Control` interceptor are registered for the API paths only.
* `RequestIdFilter`, `RequestBodyLimitFilter` (which enforces `REQUEST_MAX_BODY_BYTES`) and
  `AccessLogFilter` are `@Component` filters on the API connector only. The management connector's
  child context does not inherit them, and the actuator takes no bodies.
* Tomcat's form limit, which follows the same variable, is set on both connectors, because Spring
  Boot applies its Tomcat settings to the management server too. `RequestBodyLimitIT` reads it back
  from both.

### Logs
Inside the container, logs are ECS JSON (Elastic Common Schema): one JSON object per line, with the
request id on every line of a request.

* The image sets `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
* Each line holds `"log":{"level":...,"logger":...}`,
  `"service":{"name":"store","version":"1.0.0-SNAPSHOT"}` (from `spring.application.name` and the
  build version), `"message"` and `"ecs":{"version":"8.11"}`.
* Every MDC entry and event key-value pair is a top-level member, so `"requestId":"<id>"` is on
  every line written while a request is handled.
* The smoke test's `compose` mode checks the shape on the started image. It looks up to five times,
  a second apart, because the ETag filter flushes the response before the line is written.

#### The access line
Every request on the API port that reaches the filters writes one INFO line on
`com.example.store.access` when it completes:
```text
GET /order/{id} 200 3 ms
```
* The fields are the method, the route, the status and whole milliseconds; a cache hit usually logs
  0 ms.
* `route`, `status` (a number) and `durationMs` are top-level ECS members beside `message` and
  `requestId`.
* The route is the pattern Spring MVC matched (`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`),
  the same as the `uri` tag of `http_server_requests_seconds`:
  * a 304, a 400, a service 404 and a 413 carry their endpoint's pattern;
  * an unmapped path logs `/**` (the static-resource mapping);
  * a 405, 406 or 415, raised during mapping, logs `UNKNOWN`.
* The status is the one the client receives. An exception that escapes the filters is answered by
  Tomcat with a 500 after the filter has run, so the line logs 500 (as the timer does), not the
  status the response held. On the jar of 5996b3c, a `PUT` whose form body the form-content filter
  rejected was a 500 logged as `PUT UNKNOWN 200 0 ms`. A response committed before the exception
  logs the status it sent.
* The line never holds the raw path, the query string or the body. So search words stay out, and
  the set of routes stays bounded. Tomcat's own INFO lines do quote them
  ([Requests Tomcat rejects itself](#requests-tomcat-rejects-itself)).
* `AccessLogFilter` sits right after `RequestIdFilter` and `RequestBodyLimitFilter`, and outside
  the ETag filter, so a 304 logs as 304.
* It runs on the API connector only, so probes and scrapes write nothing (`AccessLogIT`,
  `AccessLogFilterTest`).

What the line costs:

| Measure | With the line | With `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF` |
|---|---|---|
| Size on stdout | About 395 B a line (mean of 1,008 lines from the smoke test and a 1,000-request mix on the Compose stack; 370 to 403 B), roughly 400 MB per million requests per replica | None |
| Mean of the server's request timer over 50,000 cached `GET /order/1` from one keep-alive client (`ab -k -c 1`, Docker Desktop on arm64), two runs | 46.4 and 48.6 µs | 29.4 and 30.3 µs, so the line costs about 17 µs |
| The same for a 3-statement `GET /order?size=20` | 766 and 785 µs | 738 and 777 µs, within the spread between runs |

The client's throughput did not change: about 3,100 requests per second for the cached read and
930 for the list. No statement is added (`QueryCountIT`). Where an ingress already logs every
request, `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF` turns the line off without touching any
other logger.

#### Creation lines
A created order, customer or product writes one INFO line on
`com.example.store.service.CreationEventLogger` after its transaction commits, with `requestId`
beside it:
```text
Created order 10051 for customer 2 with 3 products
Created customer 101
Created product 101
```
* The lines hold ids and the number of products only, never a name or description.
* The services publish `OrderCreatedEvent`, `CustomerCreatedEvent` and `ProductCreatedEvent`, and
  `@TransactionalEventListener(phase = AFTER_COMMIT)` methods log them.
* The reason is that with sequence ids the inserts are flushed at commit, after `create()` has
  returned ([ADR 0005](adr/0005-sequence-id-generation.md)). A line written in the service would
  also appear for a commit that fails.
* `CreationLogIT` pins the lines, and that a creation whose insert fails at commit logs nothing. It
  calls each service with a 256-character text, which the request validation would have refused,
  so only the column rejects it.

#### Failed statements
Failed statements are logged without the request's values. PgJDBC runs with
`logServerErrorDetail: false`, so its messages hold the server's severity and primary message only.

* A failed batched insert reads `Batch entry 0 <unknown> was aborted:` plus the server's message,
  where it would otherwise quote the insert with the posted name or description.
* The server's detail, hint and position (the violated key, the failing row) are left out of:
  * the full cause logged at DEBUG;
  * Hibernate's `SqlExceptionHelper` lines;
  * the 500's ERROR stack trace;
  * HikariCP's WARN line for a terminated connection.
* Hibernate's own messages show the statement with `?` for each value.
* A violated constraint is still named, because PostgreSQL puts the name in the primary message.
  Hibernate reads it there for the 409's WARN line (SQL state and constraint name; full cause at
  DEBUG).

The price is less context in the log of a failed `POST`:
* A 409 or a `database_timeout` 503 logs only the SQL state, the primary message and the handler's
  WARN line with the constraint or the cause's class. So the log no longer says which insert
  failed.
* The statement, with `?` for each value, appears only in an ERROR stack trace: the 500's, or the
  transaction interceptor's for a read whose connection was lost. The handler's DEBUG line adds it
  for a `database_timeout` only.
* No line carries the key or the row.
* `FailedInsertLogIT` cancels the insert of each `POST` at commit, terminates that of a
  `POST /order`, and violates a foreign key on an order insert. It finds none of the names and
  descriptions it sent in any line at the shipped levels, stack traces included.
  `DatabaseTimeoutsIT` reads the setting back.

### Database failures
A database that cannot be reached, loses the connection or does not answer in time is a 503 with
`Retry-After: 5`, not a 500, with one exception. `code` tells the cases apart, and all of them
recover without a restart once the database answers.

| Situation | Answer |
|---|---|
| No connection within the pool's 5 s `connection-timeout` (pool exhausted, or the database down) | 503 `service_unavailable`, `The service cannot obtain a database connection` |
| A statement cancelled at `DB_STATEMENT_TIMEOUT` or at the `TX_TIMEOUT` deadline, a `POST`'s insert at commit included, or a transaction whose deadline passed before a query | 503 `database_timeout`, `The database did not answer in time` |
| A connection lost during a service method's reads (every statement of a `GET`; a `POST`'s reads of the customer and products), whether the backend is terminated, the server restarts or `DB_SOCKET_TIMEOUT` cuts a stall | 503 `service_unavailable` |
| A transaction deadline that passed before one of Hibernate's own statements (a by-id read, a `POST`'s flush): `transaction timeout expired` | 503 `service_unavailable` |
| A `POST`'s insert at commit cut by `DB_SOCKET_TIMEOUT` (SQL state `08006`) | 503 `service_unavailable` |
| A `POST`'s insert at commit whose session the database terminates (`57P01`: `pg_terminate_backend`, or the fast shutdown of a restart): `could not execute batch` | 500 `internal_error`, logged at ERROR as `Unhandled exception` |

**Why reads and commits differ.**
* When a connection is lost during a read, HikariCP marks it broken, the rollback fails, and Spring
  throws the rollback's exception (`Unable to rollback against JDBC Connection`) in place of the
  original.
* With sequence ids, Hibernate flushes a `POST`'s inserts at commit, after the service method has
  returned ([ADR 0005](adr/0005-sequence-id-generation.md)). A failure there is the commit's own
  exception, which no failed rollback replaces, so the SQL state decides the answer.

**How the handler decides.** It tests the exception's type, never its message:

| Exception | Answer |
|---|---|
| `CannotCreateTransactionException` | 503 `service_unavailable` |
| `DataAccessResourceFailureException`, which Spring raises for Hibernate's `JDBCConnectionException` | 503 `service_unavailable` |
| `JpaSystemException` caused by Hibernate's `TransactionException` (a failed rollback, or an expired deadline) | 503 `service_unavailable` |
| `QueryTimeoutException`, `TransactionTimedOutException` | 503 `database_timeout` |
| Any other `JpaSystemException`, such as one caused by the `GenericJDBCException` of a terminated session | 500 `internal_error` |

The handler imports two Hibernate types, because Spring's exceptions expose neither the violated
constraint's name (`ConstraintViolationException`, needed for the 409's log line) nor whether a
`JpaSystemException` came from Hibernate's transaction handling (`TransactionException`).

**What gets logged.**
* For the lost-connection read, Spring's `TransactionInterceptor` logs the original exception at
  ERROR as `Application exception overridden by rollback exception`, with the SQL (bind values as
  `?`) and the driver message. The line stays, because silencing the logger would also hide a
  failed rollback that replaces a genuine application error.
* The handler logs each 503 at WARN by the class name of its most specific cause. The driver
  message can carry hosts or SQL, so the full cause is at DEBUG.
* Hibernate's `SqlExceptionHelper` still logs the SQL state at WARN and the driver message at ERROR
  (`ERROR: canceling statement due to statement timeout`).

**What it means for operators and clients.**
* Readiness reports `db` DOWN while no connection can be obtained, but only after the same 5 s, so
  probe timeouts must exceed it.
* Neither a 503 nor that 500 tells a client whether a `POST` was applied. A `POST` sent with an
  `Idempotency-Key` can simply be sent again: the service replays it if the first attempt committed,
  and runs it afresh if not ([Creating resources](#creating-resources)).
* An unkeyed `POST` cannot be repeated safely ([Request bodies](ASSUMPTIONS.md#request-bodies)).

**Tests.** `DatabaseFailureIT` produces three of these answers on real requests. It holds a lock on
`order`, cancels or terminates the backend of a `GET /order?size=5`, and terminates that of a
`POST /order` at commit (no order written). `GlobalExceptionHandlerTests`,
`GlobalExceptionHandlerLogTest` and `OpenApiContractIT` pin the statuses, codes, headers, WARN
lines and the `ServiceUnavailable` examples.

### Shutdown
On `SIGTERM`, requests in flight get up to 20 s to finish, and new connections are refused
(`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 20s`).

* The drain closes the API listening socket first. A new connection to 8080 is refused, while the
  management port keeps answering: readiness 503 `OUT_OF_SERVICE`, liveness 200.
* The management server, the API server and the pool stop once the requests in flight have
  finished.

#### What a client sees
| Case | What happens |
|---|---|
| A keep-alive connection with a request in flight | Closed right after its response (no `Connection: close` header) |
| A keep-alive connection idle when the drain begins | Stays open until the API server stops. A request sent on it meanwhile gets no response at all: no 503, no access line, and a `POST` stores nothing |
| A request still running at 20 s | Loses its connection without a response |

For a request still running at 20 s:
* Tomcat then waits up to 5 s for its thread before the pool closes. So a `POST` cut there may
  still have committed, and its access line shows the status its handler set.
* A keyed `POST` can be sent again safely; an unkeyed one cannot
  ([Request bodies](ASSUMPTIONS.md#request-bodies)).
* A stuck request therefore keeps the process alive for 20 s plus 5 s plus about 0.3 s, which the
  30 second stop grace of `compose.yaml` covers.

#### What was observed
`GracefulShutdownIT` observes the drain on a second application on the shared database. While a
`GET /order?size=5` waits for a lock and the application closes:
* a new connection is refused;
* readiness is 503 and liveness 200;
* the request is answered 200 once the lock is released (with `server.shutdown: immediate`, the
  connection is reset).

`FailClosedConfigurationTest` pins both properties. The idle-connection case and the timings were
observed by hand on 2026-10-20 (Java 17 on macOS, PostgreSQL 16.2, the same held request):

* **Jar 0503f3e, `SIGTERM` 1.1 s after the request started.**
  * A new connection 0.56 s after the signal was refused.
  * The held request was answered 200 when the lock was released 3.4 s after the signal, and the
    pool closed 13 ms after the drain.
  * With the shutdown timeout lowered to 2 s, the held request got an empty reply 2.0 s after the
    signal. Tomcat waited 1.3 s more for its thread (which finished and logged a 200), and the
    process exited 3.5 s after the signal.
* **Jar 3f93bc1, five runs.**
  * Keep-alive connections that had answered a request about a second before the signal were still
    open 1.0 to 3.6 s after it. A `GET /order/{id}` and a `POST /customer` sent on them got no byte
    back, wrote no access line and stored no row.
  * A connection with a `GET /order` in flight was closed as soon as its 200 was sent. Idle
    connections were closed when the API server stopped.
  * Two further runs kept the held request stuck (statement and socket timeouts 0, transaction
    timeout 120 s). They showed an empty reply at 20.0 s, idle connections closed at the same
    moment, a thread dump at 22.6 s with the shutdown waiting for Tomcat's request threads, the
    pool closed at 25.0 s, a 503 access line from the stuck thread, and exit at 25.2 and 25.3 s.
* **Docker Desktop's published port does not pass the refusal on.** On Docker Desktop 4.38.0
  (engine 27.5.1, macOS, 2026-10-20, two runs, jar 3f93bc1 in the runtime image):
  * a new connection to the published port during the drain completed its handshake and was closed
    without a byte (`curl` exit 52, where a refused connection is exit 7; `nc -z` succeeded);
  * the same connection inside the container's network namespace was refused;
  * the published port refused only once the container had exited.

#### Under Kubernetes
Kubernetes needs a delay before the signal, because traffic keeps arriving for a moment after the
pod starts stopping.

* Deleting a pod marks its endpoints not ready while the kubelet starts stopping the container, and
  kube-proxy and the ingress act a moment later. Without a delay, connections still arrive after
  the drain has closed the port, and are refused.
* Readiness turning `OUT_OF_SERVICE` moves no traffic by itself.
* The delay moves new connections only. kube-proxy chooses an endpoint when a connection opens, so a
  connection established through a Service before the endpoint was removed stays on the pod, and
  is served during the delay. If it is idle when the drain begins, it gets no response to its next
  request.
* A pooling client can retry a `GET` on a new connection, but cannot tell whether a `POST` without
  a response was applied.
* The image has no shell for an `exec` hook, so the delay is the `sleep` handler
  (`lifecycle.preStop.sleep.seconds`, on by default from Kubernetes 1.30).
* `terminationGracePeriodSeconds` (30 s by default) counts from the start of the hook. It must cover
  the delay, the 20 s drain and the 5 s for a cut request, plus a margin: for example a 10 s delay
  with `terminationGracePeriodSeconds: 40`. Otherwise `SIGKILL` cuts the drain short.

### Start-up and migrations
A misconfigured process exits during start-up, and migrations run as a job before the service
starts.

* Missing credentials make the process exit during start-up, so the orchestrator reports a crash
  rather than a service on sample credentials.
* `LIQUIBASE_CONTEXTS` unset means no sample data, and a blank value is refused
  ([Fail closed](#fail-closed)).

**Migrations are a job, not a start-up side effect.**
* Compose runs the image once as `migrate`, with the schema owner's credentials, before `store`.
* `store` connects as `store_app` with `SPRING_LIQUIBASE_ENABLED=false`. That role has connect,
  `SELECT`, `INSERT` and sequence usage, plus `UPDATE` and `DELETE` on `idempotency_key` alone, and
  no DDL.
* `ddl-auto: validate` and the readiness `db` component prove that the runtime works under that
  role, and the smoke test proves the grants.
* The job sets `DB_STATEMENT_TIMEOUT` and `DB_SOCKET_TIMEOUT` to `0`
  ([How the timeouts are set](#how-the-timeouts-are-set)).
* A Kubernetes rollout runs the image as a `Job` or init container ahead of the deployment, with the
  same two variables.
* `bootRun` keeps in-process Liquibase as `admin`.
* [ADR 0008](adr/0008-operational-posture.md) records the split, which changeset needs a privileged
  role, and the `down -v` step.

**Index changesets need a write window.**
* Changesets 3, 4 and 7 index existing tables with a plain `CREATE INDEX`, which holds every write
  until the changeset commits. Changeset 7 took about a second on 1,000,000 orders.
* [ADR 0009](adr/0009-index-changesets.md) records the measured builds and the concurrent form every
  new index changeset uses. Changeset 8 follows it for its two builds and its drop.

**The runtime role's grant.**
* Changeset 9 grants `UPDATE` and `DELETE` on `idempotency_key` to the role named by the Liquibase
  parameter `runtimeRole` (`spring.liquibase.parameters.runtimeRole`, `store_app` by default).
* Where no such role exists, the changeset is recorded as skipped. So a deployment that renames the
  role must pass the name to the migration job, or the first keyed `POST` fails with
  `permission denied` ([ADR 0010](adr/0010-idempotency-keys.md)).

### The image
The image runs the layered jar as a non-root user on a distroless Java 17 base, which has no shell
and no package manager.

* The user is 65532, and the base is `gcr.io/distroless/java17-debian13:nonroot`, pinned by digest.
* That base holds Eclipse Temurin 17.0.20.1 as the Debian package `temurin-17-jre`, among 25 Debian
  13 packages.
* It has no shell, package manager, `curl` or `wget`, so there is no `docker exec`. Debug locally
  with the `:debug-nonroot` variant, which adds a busybox shell and is never shipped.

**JVM flags.** They are `ENTRYPOINT` arguments:
```text
/usr/bin/java -XX:+UseSerialGC -XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError org.springframework.boot.loader.launch.JarLauncher
```
* The image sets no `JAVA_TOOL_OPTIONS`. The JVM announces that variable, even empty, with a
  plain-text `Picked up JAVA_TOOL_OPTIONS: ...` line on stderr ahead of the JSON, which the smoke
  test fails on.
* Command-line flags win over `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS`. So an operator can attach
  an agent through `JAVA_TOOL_OPTIONS` without losing the heap flags (the JVM then prints its own
  `Picked up` line). Only `_JAVA_OPTIONS` or a replaced entrypoint changes them.

**When the base stops receiving updates.**
* Distroless retires a Java image when it stops updating that Java version on a Debian release;
  `java17-debian12` is no longer updated. So `java17-debian13` may stop receiving updates while the
  service is still on Java 17.
* A stale JRE alone passes the scan. The scan lists `temurin-17-jre`, but no advisory data covers
  Adoptium's package ([ADR 0004](adr/0004-spring-framework-6-cve-exceptions.md)). So JRE fixes
  arrive only with a new base digest, the refresh Dependabot proposes.
* A stale base fails the scan only once another Debian package has a fixable `HIGH` or `CRITICAL`
  advisory. `java17-debian12` does, through `libexpat1`, `libpcre2-8-0` and `liblcms2-2` (and its
  JRE, Debian's own `openjdk-17`, which Debian's advisories cover).
* The way out is a newer Java line: a `java21` image and the matching toolchain.

### Container hardening and memory
The Compose `store` and `migrate` services run hardened, inside a 512 MiB memory limit without
swap, with a heap share measured under load.

* `read_only: true` and `tmpfs: [/tmp]`. Tomcat's base directory and the JVM's `hsperfdata` live in
  `/tmp`; without the tmpfs, `store` fails with `Unable to start web server`, while `migrate` still
  exits 0.
* `cap_drop: [ALL]` and `security_opt: ["no-new-privileges:true"]`.
* `deploy.resources.limits.memory: 512m` (Compose v2 honours it without Swarm) and
  `memswap_limit: 512m`. Without the latter, Docker allows as much swap again, so an overrun swaps
  silently instead of failing.
* Log rotation (`json-file`, `max-size: "10m"`, `max-file: "3"`), because Docker's default keeps a
  container's whole output in one unbounded file, and the access line grows it with every request.
  A line takes about 520 B in that file (the JSON escaped inside Docker's record), so three files of
  10 MB hold roughly the last 60,000 lines per container.

`postgres` stays unhardened, because `initdb` runs as root and needs `CHOWN` and `SETUID`.
[ADR 0008](adr/0008-operational-posture.md) records the settings. The smoke test's `compose` mode
reads each back with `docker inspect`, so a new limit needs the asserted 536870912 changed too.

#### Sizing the heap
The heap is a share of the 512 MiB: `-XX:MaxRAMPercentage=30` gives a `MaxHeapSize` of 154 MiB. The
rest holds metaspace, the code cache, thread stacks and native buffers. The share was measured like
this:
* **Load:** a load generator kept 200 keep-alive clients busy for 300 s on the list, by-id, cursor
  and search reads and both `POST`s, half asking for gzip.
* **JVM:** the heap fully resident from the start (`-XX:InitialRAMPercentage` equal to the maximum,
  `-XX:+AlwaysPreTouch`), on images with the Dockerfile's layers, flags and JVM.
* **Readings:** the cgroup's `memory.peak` and `memory.events`, `docker stats`, `VmHWM`, and native
  memory tracking through the HotSpot attach socket from a sidecar sharing the PID namespace (the
  JRE image has no `jcmd`).
* **Host:** Docker Desktop on arm64 with transparent huge pages `always` and 2040 KiB thread stacks
  (the arm64 default). A Linux host with the common `madvise` setting, such as the amd64 CI runner,
  is expected to use less.

| Heap share | Result at 200 clients |
|---|---|
| 30% (the setting) | Peaked at 477.4 MiB with 241 threads, flat after 120 s; about 440 MiB with 50 clients (2,686 requests per second, p99 64.7 ms) |
| 35% | Within 1.1 MiB of the limit (510.9 MiB) in five minutes |
| 40% | Reached the limit |
| 75% (384 MiB of heap) | 636.8 MiB by native memory tracking, hit the limit and swapped 104.7 MiB; with swap off, killed (exit 137) |

**Why SerialGC.**
* G1's own native structures took 59 to 63 MiB at a 35% heap, and that run overran the limit.
* The JVM's ergonomics switch to G1 once a deployment grants 1792 MiB or more with two or more
  CPUs.
* SerialGC cost 2,050 young collections (13.5 s) and 3 full ones (283 ms) in the 300 s.
* A larger `TOMCAT_THREADS_MAX`, `DB_POOL_SIZE` or cache needs the limit raised and the share
  re-measured, since each busy thread adds its stack and buffers outside the heap.

## Further reading
* [Notes for the assessor](../NOTES.md): a short summary of what was delivered and how to run it.
* [Assumptions and judgement calls](ASSUMPTIONS.md): the judgement calls behind each task, and how
  they can be defended.
* [Performance analysis](PERFORMANCE.md): why the GET endpoints were slow, the measures taken, and
  what they measure.
* [Decision records](adr/README.md): the index of the architecture decision records.
* [OpenAPI.yaml](../OpenAPI.yaml): the API contract.
* [utils/README.md](../utils/README.md): the data generator.
