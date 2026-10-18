# Store service: design and operations guide

The assessment README at the repository root is kept as delivered. This document describes how
the service is built, run, tested and delivered, and points to the reasoning behind each
decision.

## Prerequisites
* JDK 17 (the Gradle toolchain downloads one if it is missing)
* Docker (used by Docker Compose for local runs and by Testcontainers for the tests)

## Running locally
The README's instructions still work: start PostgreSQL 16.2 on port 5433 with `admin:admin` and
a `store` database, then `./gradlew bootRun`. `bootRun` sets `spring.profiles.default=local`, and
the `local` profile (`application-local.yaml`) is the README path: the 5433 URL, `admin:admin`
and the `seed` Liquibase context as defaults, plus formatted SQL in the logs. An explicit
`SPRING_PROFILES_ACTIVE` or `--spring.profiles.active` replaces the default profile; the
container image and CI set every variable explicitly and never use `local`.

The alternative is Docker Compose, the production-shaped layout: it starts PostgreSQL 16.2 on
port 5434, runs the migrations as a one-shot `migrate` job (the same image with
`SPRING_MAIN_WEB_APPLICATION_TYPE=none`, the schema owner `admin` and the `seed` context),
and then starts the service image as the runtime role `store_app`, which can only connect,
read and insert (`scripts/postgres-init/01-roles.sql` creates it when the volume is first
initialised) and runs no Liquibase (`SPRING_LIQUIBASE_ENABLED=false`):
```shell
docker compose up --build
```
The API is then available on http://localhost:8080, the actuator on http://localhost:8081 and
the database on localhost:5434. The service starts only once `migrate` has exited 0, so a
failed migration stops the stack before the API comes up. After upgrading from a stack started
before the migration job existed, run `docker compose down -v` once: the role and its default
privileges are created only when the data directory is empty, and without them `store` fails
closed at its first connection.

To run the service from Gradle against that database instead:
```shell
docker compose up -d postgres
DB_URL=jdbc:postgresql://localhost:5434/store ./gradlew bootRun
```

Under `bootRun` and in the tests Liquibase migrates the schema in-process at start-up (the
development path); in Compose the `migrate` job does it ahead of the service. Sample data (100
customers, 10,000 orders, 100 products) is loaded when the `seed` Liquibase context is active,
which it is under the `local` profile, in the tests and in the `migrate` job of `compose.yaml`;
the base configuration applies the context-free changesets only (`LIQUIBASE_CONTEXTS` defaults
to `prod`), so an environment that sets nothing
gets the schema and no sample rows. More data can be generated with the script described in
`utils/README.md`.

## Configuration
| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/store` | JDBC URL |
| `DB_USERNAME` | none (`admin` under the `local` profile) | Database user; without it the first connection fails and the process exits |
| `DB_PASSWORD` | none (`admin` under the `local` profile) | Database password; without it the first connection fails and the process exits |
| `LIQUIBASE_CONTEXTS` | `prod` (`seed` under the `local` profile) | Liquibase contexts to apply; only `seed` loads the sample data |
| `SPRING_LIQUIBASE_ENABLED` | `true` | Boot's own `spring.liquibase.enabled`; `false` creates no Liquibase bean, which is how the Compose `store` service runs under a role that cannot alter the schema |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size |
| `MANAGEMENT_PORT` | `8081` | Port of the management connector that serves the actuator |
| `CACHE_TYPE` | `caffeine` | Spring cache provider for the by-id reads; `none` disables caching |
| `CACHE_TTL` | `60s` | Time an `orders` or `products` entry stays cached (`expireAfterWrite`) |
| `NESTED_ORDERS_LIMIT` | `20` | Newest orders embedded in each customer and newest order ids in each product (1 to 100); `orderCount` always carries the total |
| `REQUEST_MAX_BODY_BYTES` | `65536` | Largest request body accepted (at least 1024); a longer declared `Content-Length`, or a chunked body read past it, is a 413 problem detail |

A missing `DB_USERNAME` or `DB_PASSWORD` is not a configuration error at start-up: Spring Boot
binds the unresolved placeholder as a literal, so the process fails at Liquibase's first
connection (`password authentication failed for user "${DB_USERNAME}"` when a server answers,
`Connection refused` otherwise; HikariCP makes one attempt) and exits. That is the fail-closed
behaviour: no environment can start on the sample credentials by omission.
`FailClosedStartupIT` observes it against a real database: a second, non-web application
started without the two variables fails with `password authentication failed for user
"${DB_USERNAME}"` as the root cause. The graceful drain (see Operations) is documented but not
observed by a test, because the API has no slow request to hold open while a shutdown is
signalled; ADR 0008 says the same.

## Development workflow
Every change goes through a pull request. The pipeline must be green and the change reviewed
before it is squash-merged into `main`. New behaviour starts with a failing test (unit, slice or
integration) and, for API behaviour, a Cucumber scenario.

## Testing
```shell
./gradlew build
```
runs, in one task and in one JVM:
* unit tests (JUnit 5, Mockito),
* controller slice tests (`@WebMvcTest`),
* integration tests against a real PostgreSQL started by Testcontainers,
* Cucumber acceptance scenarios in `src/test/resources/features`,
* an OpenAPI contract check (`OpenApiContractIT`): captured request/response pairs are
  validated against the checked-in `OpenAPI.yaml` with `swagger-request-validator`. Real
  requests cover the 200 offset and cursor pages of the three list endpoints and the three by-id
  reads, and the customer-filtered page and the product-filtered cursor page of `/order`; the
  201 and the rejected-body 400 of each `POST` (including a whitespace-only `name` and a
  repeated product id); the 400 of a non-numeric id on the three by-id paths, of an unknown
  sort property on `/order` and `/products`, of a cursor combined with a page, of a negative
  `after`, of a `customerId` of 0 and of `customerId` combined with `productId` on `/order`,
  and of a multi-word `name` on `/customer` (while a no-break space in `name` is accepted by
  both); the 404 of an unknown order, product and customer and of an order for an unknown
  customer; and the 304 of a conditional `GET /order/{id}`, `GET /products/{id}`,
  `GET /customer/{id}` and `GET /customer` page. The 409 and 500 bodies are validated
  synthetically against `POST /order` and `GET /order`, because no real request reaches them
  (there are no unique constraints and no injected failure). The specification declares
  `X-Request-Id` as a required header on every response and `Location` on every 201, and the
  validator enforces declared headers, so a response that drops either fails the check (the
  synthetic 409 and 500 cases had to carry the id before they passed again). An undocumented
  response field, a missing required field at any depth, a missing declared header, an
  undeclared status or a request the specification would reject fails the build, so the
  specification cannot drift from the implementation.

`build` also checks formatting (`spotlessCheck`) and fails below 80% line coverage. Two
exclusions apply to the report and the gate alike: `**/mapper/**` (the MapStruct-generated
implementations) and `StoreApplication` (the launcher); the 100% line figure quoted in the
history is measured under them. Reports land in `build/reports`.

## Continuous integration
`.github/workflows/ci.yml` runs on every push to `main` and on every pull request:
1. `build`: `./gradlew spotlessCheck build test --rerun`; a failing test fails the pipeline.
   `--rerun` makes the test suite execute on every run instead of being replayed from Gradle's
   cache when only documentation changed. Test results and coverage reports are uploaded as the
   `reports` artifact.
2. `docker`: builds the image once as `store:ci` (loaded into the runner's daemon, labelled with
   the metadata of the commit), scans it with Trivy (a critical or high fixable vulnerability
   fails the pipeline; the findings table is printed in the job log, because code scanning and
   the Security tab are not available on a private free-plan repository), attaches an SPDX SBOM
   generated by Syft as the single `sbom` artifact (the action's own upload is switched off),
   starts the built image with Compose
   (`STORE_IMAGE=store:ci docker compose up -d --no-build`, which runs the `migrate` job and
   returns only once it has exited 0) and runs `scripts/smoke-test.sh ... compose` against it:
   the container must report readiness `UP` on 8081 within 120 seconds with the `db`
   component in the body, serve `GET /order/1`, `POST /customer`, `GET /products/2` and a cursor
   page on 8080 and reject a 70,000 byte body with a 413, answer 404 for `/actuator/health` on
   8080 and 200 for the liveness probe on 8081; then, in `compose` mode, the `migrate`
   container's exit code must be 0, `store_app` must be able to `select count(*) from customer`
   through `psql` in the database container, and `create table` and `update customer` as
   `store_app` must fail with `permission denied for schema public` and `permission denied for
   table customer`. A failing step prints the `migrate` and `store` logs and fails the job; the
   stack is always torn down. On `main` the job then tags that same `store:ci` image and pushes
   it to GitHub Container Registry as `ghcr.io/<owner>/store:sha-<short sha>` and `:latest`,
   so the digest delivered is
   the digest that was scanned and smoke-tested, not a rebuild. The jar inside the image is
   compiled from the same commit by the image build (wrapper-pinned Gradle, BOM-locked
   versions) and the tested jar is the `build` job's, so the artifact identity is the commit,
   not the jar bytes.

Pulling the delivered image: the package is private because the repository is, so log in with
a token that carries `read:packages`, then pull and run it against a database the container
can reach (`host.docker.internal` on Docker Desktop):
```shell
docker login ghcr.io
docker pull ghcr.io/neeleshambally/store:latest
docker run --rm -p 8080:8080 -p 8081:8081 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5434/store \
  -e DB_USERNAME=admin -e DB_PASSWORD=admin -e LIQUIBASE_CONTEXTS=seed \
  ghcr.io/neeleshambally/store:latest
```
Run alone like this, the image migrates in-process as the user it is given; the Compose stack
splits that into the `migrate` job and the `store_app` runtime (see Running locally).

Both jobs must be green and the change reviewed before a merge; GitHub cannot enforce required
checks on a private free-plan repository, so that is a procedural gate. Permissions are scoped
per job: `build` has `contents: read`, `docker` has `contents: read` and `packages: write` for
the push.

The same smoke test runs locally against the Compose stack:
```shell
docker compose up -d --build && sh scripts/smoke-test.sh http://localhost:8080 http://localhost:8081 compose
```
The script is POSIX `sh` and needs only `curl`; the optional third argument `compose` adds the
migration-job and role checks, which use `docker compose` and `docker inspect` and must run
from the repository root. A local run leaves the `Smoke Test` customer it creates in the
Compose volume; `docker compose down -v` clears it.

Dependabot opens weekly pull requests, validated by the same pipeline: Gradle dependencies with
Spring artifacts grouped and majors excluded, GitHub Actions as one grouped pull request including
majors except for `gradle/actions`, and the base images as one grouped pull request with the
Java major excluded. Both `FROM` lines of the `Dockerfile` are pinned by the multi-arch index
digest with the tag kept for readability, so a rebuild resolves the same base image until
Dependabot refreshes the digest (a digest refresh carries no major, so the exclusion does not
suppress it). Actions are
referenced by major tag rather than commit SHA on purpose: the grouped weekly pull request is the
update path, and a SHA pin would freeze the pipeline on a deprecated revision without automation
that rewrites the SHA. `gradle/actions/setup-gradle` stays on v5 because v6 moved the build cache
into a closed-source component that requires accepting separate terms of use.

`.gitlab-ci.yml` is provided for portability and has not been executed in this project; there is
no GitLab project for this repository. It follows the same order: `build` runs
`./gradlew spotlessCheck build test --rerun` under the same `--rerun` rule, `package` builds the
image once and saves it as the `image.tar` artifact, `smoke` loads that tarball, starts the stack
with Compose inside `docker:27-dind` and runs the same script against `http://docker:8080` and
`http://docker:8081`, `scan` runs Trivy 0.70.0 (the version `aquasecurity/trivy-action@v0.36.0`
installs by default on GitHub) on the tarball with the same severity, `--ignore-unfixed` and
`.trivyignore` settings, and `publish`, on the default branch only, loads the same tarball and
pushes it as `$CI_REGISTRY_IMAGE:<short sha>` and `:latest` after `package`, `smoke` and `scan`
have passed.
Three details differ from GitHub. The trigger scope is expressed as `workflow: rules`: a
pipeline runs for merge requests and for the default branch, so a push to a feature branch
without a merge request runs no pipeline on either platform. The published tag is
`<short sha>` where GitHub's `docker/metadata-action` writes `sha-<short sha>`. The SBOM is
CycloneDX produced by Trivy rather than SPDX produced by Syft; both scans print the findings
table to the job log and neither stores a report.

## Data model
An order has an id, a description, belongs to the customer that placed it, and contains one or
more products. A customer has an id, a name, and zero or more orders. A product has an id and a
description and can appear in any number of orders. Beyond the primary keys, the schema indexes
`order.customer_id`, the two sides of `order_product`, `lower(customer.name)` with trigrams for
the substring search, and each whitelisted sort column together with `id`
(`idx_order_description_id`, `idx_product_description_id`, `idx_customer_name_id`).

## API
The contract is documented in `OpenAPI.yaml`.

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

List responses are a page envelope: `content`, `page`, `size`, `totalElements`, `totalPages`.
The default page size is 20, the maximum 100, and the default sort is by `id`. `sort` takes
`property,(asc|desc)` and accepts only `id` and `description` on `/order` and `/products` and
`id` and `name` on `/customer`; any other property is a 400 problem detail, and ties are broken
by `id` in the same direction as the last property so page boundaries are deterministic (ADR 0006).
With `after` the same endpoints switch to keyset (cursor) pagination: the response is a cursor
envelope (`content`, `size`, `nextCursor`) holding the elements whose id is greater than `after`
in ascending id order; `nextCursor` is the id of the last element while more exist and `null`
when the walk is complete. `after=0` starts a walk, `size` keeps its default and cap, and `name`
still filters customers. `after` cannot be combined with `page` (other than 0) or with a `sort`
whose first property is not `id` ascending; that is a 400 problem detail with the detail
`after cannot be combined with page or sort` (ADR 0003, "Keyset pagination").
`GET /order` also accepts `customerId` or `productId` (each at least 1) to list the orders of
one customer or the orders containing one product; the filter applies in both paging modes
with the same `sort` whitelist, an id that matches nothing yields an empty page rather than a
404, and the two filters together are a 400 problem detail with the detail
`customerId and productId cannot be combined`. These are the uncapped lists behind the nested
`orders` of a customer or a product: a customer on a list response carries its newest
`NESTED_ORDERS_LIMIT` orders (20 by default; the highest ids, returned in ascending id order) and
`orderCount`, the total; a product on the list and by-id responses carries the ids of the newest
orders containing it the same way, plus `orderCount` (ADR 0003, "Nested collections").
Errors are RFC 7807 problem details; validation failures, whether of a request body or of a
query parameter such as `name`, `after`, `customerId` or `productId`, include an `errors` array with the offending field
names and the constraint messages. A non-numeric path id is a 400 problem detail. Requests
never carry an id; unknown properties are rejected with 400. A request body over
`REQUEST_MAX_BODY_BYTES` (64 KiB by default) is a 413 problem detail (`Request body exceeds
65536 bytes`), judged by the declared `Content-Length` before any byte is read or by the bytes
counted while a chunked body is read; Tomcat appends `Connection: close` to the 413 and
discards the unread remainder, up to its 2 MB swallow limit. An order carries between 1 and 100
products, each id at most once: a repeated id is a 400 naming `productIds`, not a silent
de-duplication. Every `POST` answers 201 with a `Location` header holding the relative path of
the created resource (`/order/{id}`, `/products/{id}`, `/customer/{id}`); the path is relative
so that a gateway rewriting the host stays correct, which RFC 9110 permits.
Every successful `GET` carries a weak `ETag` and `Cache-Control: no-cache`; sending that ETag
back in `If-None-Match` yields `304 Not Modified` with no body when the representation is
unchanged (ADR 0007). `GET /order/{id}` and `GET /products/{id}` are additionally cached in the
service for `CACHE_TTL`; `products` entries are evicted when an order containing the product is
created, so its order ids and `orderCount` reflect the new order on the next read.
`GET /customer/{id}` is not cached in the service: its `orderCount` changes on every
`POST /order` and nothing keys customers for eviction, so each read costs its 2 statements.

## Operations
The service listens on two ports. The API is served on 8080; the actuator is served on a
separate management connector, 8081 by default (`MANAGEMENT_PORT`), and is absent from 8080
(`/actuator/health` on the application port is a 404). The ingress exposes 8080 only, so the
probes and metrics are reachable from inside the cluster but never from the public network.
* `GET :8081/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`;
  liveness is Spring's `LivenessState`; readiness combines `ReadinessState` (which flips to
  `ACCEPTING_TRAFFIC` once the context has started and back on shutdown) with the database
  indicator, so a replica that has lost its database is taken out of rotation, and the body
  names its components without details
  (`{"status":"UP","components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}}}`);
  `/actuator/health` stays detail-free. Neither probe proves that the API connector answers;
  the container smoke test of the pipeline does that. ADR 0008 records the trade-off.
* `GET :8081/actuator/info` reports the build (`build.artifact`, `build.name`, `build.group`,
  `build.version`) from `META-INF/build-info.properties`, generated without the build time so
  that a rebuild of unchanged sources is cacheable
* `GET :8081/actuator/prometheus` for scraping; `cache_gets_total{cache=...,result=hit|miss}`
  shows the hit rate of the `orders` and `products` caches, and `http_server_requests_seconds`
  covers the API port only (management traffic is not recorded)
* Every response carries an `X-Request-Id`. An inbound value is echoed when it matches
  `[A-Za-z0-9._-]{1,128}`; otherwise, or when absent, a UUID is generated. The header is
  declared as required on every response in `OpenAPI.yaml` and the contract check enforces it.
  The filter runs
  before every other filter, so 304, 400, 404 and 500 responses carry it too, and the id is in
  the MDC as `requestId` for the duration of the request, so every log line of a request can be
  found from the id a client quotes. There is no tracing library: no collector is in scope, and
  the header is where a gateway's or a later tracer's id attaches.
* `server.compression` applies to both connectors; the `ETag` filter and the `Cache-Control`
  interceptor are registered for the API paths only
* `RequestBodyLimitFilter` enforces `REQUEST_MAX_BODY_BYTES` and, like `RequestIdFilter`, is a
  `@Component` filter registered on the API connector only: the management connector's child
  context does not inherit it, and the actuator takes no request bodies
* Logs are ECS-formatted JSON inside the container (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)
  with `service.name` and `service.version` taken from `spring.application.name` and the
  build version, and `requestId` as a field on every line written while a request is handled;
  a data-integrity conflict is logged at WARN by SQL state and constraint name, never with
  the driver message that quotes the request's values (the full cause is at DEBUG)
* Shutdown is graceful with a 20 second drain; `compose.yaml` gives the container a 30 second
  stop grace for it, and a Kubernetes deployment needs the same margin in
  `terminationGracePeriodSeconds` (the default is 30 s) or the drain is cut short by `SIGKILL`
* A missing `DB_USERNAME` or `DB_PASSWORD` fails at the first database connection, so the
  process exits during start-up and the orchestrator reports a crash, not a running service
  on sample credentials; `LIQUIBASE_CONTEXTS` unset means no sample data (see Configuration)
* Migrations are a job, not a start-up side effect: the Compose stack runs the same image once
  as `migrate` with the schema owner's credentials before `store` starts, and `store` connects
  as `store_app` (connect, `SELECT`, `INSERT`, sequence usage; no DDL, `UPDATE` or `DELETE`)
  with `SPRING_LIQUIBASE_ENABLED=false`. `ddl-auto: validate` and the readiness `db` component
  prove the runtime works under that role, and the smoke test's `compose` mode proves the
  grants. A Kubernetes rollout runs the image as a `Job` or init container ahead of the
  deployment. `bootRun` keeps in-process Liquibase as `admin` for development. ADR 0008
  records the split, which changeset needs a privileged role, and the `down -v` step for a
  volume created before the job existed

## Further reading
* `docs/ASSUMPTIONS.md`: the judgement calls behind each task and how they can be defended.
* `docs/PERFORMANCE.md`: the analysis of the slow GET endpoints and the measures taken.
* `docs/adr/`: architecture decision records.
