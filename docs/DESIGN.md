# Store service: design and operations guide

The assessment README at the repository root is kept as delivered. This document describes how
the service is built, run, tested and delivered, and points to the reasoning behind each
decision.

## Prerequisites
* A JDK 17 to 24 to run Gradle (the Spring Boot and toolchain plugins need 17; Gradle 8.14
  supports running on Java 24 at most, and on Java 25 it starts but `spotlessCheck` fails). The
  build compiles and runs on a Java 17 toolchain: Gradle uses an installed JDK 17 when it finds
  one and otherwise downloads Eclipse Temurin 17 (about 190 MB) into `~/.gradle/jdks` on the
  first build; offline, point `org.gradle.java.installations.paths` in
  `~/.gradle/gradle.properties` at an installed JDK 17
* Docker (used by Docker Compose for local runs and by Testcontainers for the tests)
* A PostgreSQL installed on bare metal (the README's alternative) needs the `pg_trgm` contrib
  module, which changeset 4 creates as an extension; the official image and most
  distributions bundle it
* Node.js 22 and npm, only to run or test the data generator in `utils`

## Running locally
The README's instructions still work: start PostgreSQL 16.2 on port 5433 with `admin:admin` and
a `store` database, then `./gradlew bootRun`. `bootRun` sets `spring.profiles.default=local`, and
the `local` profile (`application-local.yaml`) is the README path: the 5433 URL, `admin:admin`
and the `seed` Liquibase context as defaults, plus formatted SQL in the logs. An explicit
`SPRING_PROFILES_ACTIVE` or `--spring.profiles.active` replaces the default profile; the
container image and CI set every variable explicitly and never use `local`.

The alternative is Docker Compose, the production-shaped layout: it starts PostgreSQL 16.2 on
port 5434 (so that the README's own 5433 instance can stay up alongside the stack), runs the
migrations as a one-shot `migrate` job (the same image with
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
to `prod`), so an environment that sets nothing gets the schema and no sample rows. A blank
value is rejected at start-up, because an empty context set would apply every changeset, seed
included (see Configuration). More data can be generated with the script described in
`utils/README.md`.

## Configuration
| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/store` | JDBC URL |
| `DB_USERNAME` | none (`admin` under the `local` profile) | Database user; without it the first connection fails and the process exits |
| `DB_PASSWORD` | none (`admin` under the `local` profile) | Database password; without it the first connection fails and the process exits |
| `LIQUIBASE_CONTEXTS` | `prod` (`seed` under the `local` profile) | Liquibase contexts to apply; only `seed` loads the sample data; must be non-blank: a blank value stops start-up |
| `SPRING_LIQUIBASE_ENABLED` | `true` | Boot's own `spring.liquibase.enabled`; `false` creates no Liquibase bean, which is how the Compose `store` service runs under a role that cannot alter the schema |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size; a connection is waited for at most 5 s (`connection-timeout`), then the request is a 503 |
| `TOMCAT_THREADS_MAX` | `200` | Tomcat request threads (`server.tomcat.threads.max`); see the sizing note below |
| `MANAGEMENT_PORT` | `8081` | Port of the management connector that serves the actuator |
| `CACHE_TYPE` | `caffeine` | Spring cache provider for the by-id reads; `none` disables caching |
| `CACHE_TTL` | `60s` | Time an `orders` or `products` entry stays cached (`expireAfterWrite`) |
| `NESTED_ORDERS_LIMIT` | `20` | Newest orders embedded in each customer and newest order ids in each product (1 to 100); `orderCount` always carries the total |
| `REQUEST_MAX_BODY_BYTES` | `65536` | Largest request body accepted (at least 1024); a longer declared `Content-Length`, or a chunked body read past it, is a 413 problem detail |

The pool and the request threads are sized together. With `DB_POOL_SIZE` 10 and 200 request
threads at most 10 requests touch the database concurrently; the rest wait up to 5 s for a
connection and then get a 503 problem detail with `Retry-After: 5` (see Operations), so a
remote database that slows down degrades into retryable 503s rather than into 200 threads
blocked on one pool. Size the pool to the database's connection budget per replica, and the
threads to roughly the pool times the share of request time spent outside the database
(serialisation, compression, the ETag hash); every endpoint spends most of its time in the
database, so the defaults leave the threads well above the pool and the 5 s wait is the real
bound. `FailClosedConfigurationTest` pins the resolved default and the variable.

A missing `DB_USERNAME` or `DB_PASSWORD` is not a configuration error at start-up: Spring Boot
binds the unresolved placeholder as a literal, so the process fails at Liquibase's first
connection (`password authentication failed for user "${DB_USERNAME}"` when a server answers,
`Connection refused` otherwise; HikariCP makes one attempt) and exits. That is the fail-closed
behaviour: no environment can start on the sample credentials by omission.
`FailClosedStartupIT` observes it against a real database: a second, non-web application
started without the two variables fails with `password authentication failed for user
"${DB_USERNAME}"` as the root cause. A blank `LIQUIBASE_CONTEXTS` (or
`SPRING_LIQUIBASE_CONTEXTS`) is caught earlier, before any connection, by
`LiquibaseContextsGuard`, an `EnvironmentPostProcessor` registered in
`META-INF/spring.factories`: an empty context list makes Liquibase run every changeset, seed
included, so the guard stops start-up with `spring.liquibase.contexts is blank` unless
`spring.liquibase.enabled` is `false` (the Compose `store` service); the failure block is
printed as plain text rather than JSON because logging is not yet initialised at that point
(the same `FailClosedStartupIT` and `FailClosedConfigurationTest` pin it). The graceful drain (see Operations) is documented but not
observed by a test, because the API has no slow request to hold open while a shutdown is
signalled; ADR 0008 says the same.

## Development workflow
Every change goes through a pull request. The pipeline must be green and the change reviewed
before it is squash-merged into `main`. New behaviour starts with a failing test (unit, slice or
integration) and, for API behaviour, a Cucumber scenario. In this single-author project the
review step is exercised by a second account, so that the branch workflow and the approval
gate stay real instead of being switched off; the substantive review is the audit-driven
follow-up work recorded in the ADRs and in the pull requests that closed each audit.

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
  customer; the 413 of an oversized body and the 415 of a `POST /order` sent as `text/plain`;
  and the 304 of a conditional `GET /order/{id}`, `GET /products/{id}`,
  `GET /customer/{id}` and `GET /customer` page. A 405 for a method a path does not list cannot
  be declared per operation, because the method is absent from the contract; `info.description`
  states it and the smoke test sends `DELETE /order/1` to check it. The 409, 500 and 503 bodies
  are validated synthetically against `POST /order` and `GET /order`, because no real request
  reaches them
  (there are no unique constraints and no injected failure). The specification declares
  `X-Request-Id` as a required header on every response, `Location` on every 201 and
  `Retry-After` on every 503, and the validator enforces declared headers, so a response that
  drops one fails the check (the synthetic 409 and 500 cases had to carry the id before they
  passed again, and a synthetic 503 without `Retry-After` is rejected, which a case pins). An undocumented
  response field, a missing required field at any depth, a missing declared header, an
  undeclared status or a request the specification would reject fails the build, so the
  specification cannot drift from the implementation.

The test classes follow one naming convention per tier: `*Test` runs without a Spring test
context (JUnit 5, Mockito, `ApplicationContextRunner`: `NestedOrdersPropertiesTest`,
`RequestBodyLimitPropertiesTest`), `*Tests` is a `@WebMvcTest` slice (all six), and `*IT` runs
on the shared Testcontainers context (`CucumberIT` is the acceptance suite on the same
context). Where a unit test and a slice cover the same class the slice carries `Slice` in its
name (`RequestBodyLimitFilterTest`, `RequestBodyLimitFilterSliceTests`), so the tier is never a
single letter apart.

`build` also checks formatting (`spotlessCheck`) and fails below 80% line coverage. Two
exclusions apply to the report and the gate alike: `**/mapper/**` (the whole `mapper` package:
the MapStruct-generated implementations and the hand-written default method
`ProductMapper.orderIds`, which `ProductMapperTest` covers but the gate does not count) and
`StoreApplication` (the launcher); the 100% line figure quoted in the history is measured under
them. Reports land in `build/reports`.

## Continuous integration
`.github/workflows/ci.yml` runs on every push to `main` and on every pull request:
1. `build`: `npm ci && npm test` in `utils` (the data generator's own check, on Node 22 with
   the npm cache keyed on `utils/package-lock.json`), then
   `./gradlew spotlessCheck build test --rerun`; a failing test fails the pipeline.
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
   table customer`, and `DELETE /order/1` sent with `X-Request-Id: smoke-<pid>` must be a 405
   problem detail whose log line the `store` container's output carries as ECS JSON with
   `"requestId":"smoke-<pid>"` and the `service` name and version (see Operations). A failing
   step prints the `migrate` and `store` logs and fails the job; the
   stack is always torn down. On `main` the job then tags that same `store:ci` image and pushes
   it to GitHub Container Registry as `ghcr.io/<owner>/store:sha-<short sha>` and `:latest`,
   so the digest delivered is
   the digest that was scanned and smoke-tested, not a rebuild. The jar inside the image is
   compiled from the same commit by the image build (wrapper-pinned Gradle, BOM-locked
   versions) and the tested jar is the `build` job's, so the artifact identity is the commit,
   not the jar bytes.

Both jobs carry a `timeout-minutes`: 20 for `build` and 30 for `docker`. Measured runs take
2.0 to 2.5 minutes for `build` and 2.7 to 5.5 minutes for `docker`, and a `docker` run with a
cold build cache can reach about 10 minutes, so the limits leave a wide margin over a normal
run while a hung Testcontainers pull, Gradle daemon or image pull releases the runner within
half an hour instead of GitHub's six-hour default.

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
majors except for `gradle/actions`, the `Dockerfile` base images as one grouped pull request with
the Java major excluded, and the `compose.yaml` images as the `compose-images` group. Both `FROM`
lines of the `Dockerfile` and the `postgres:16.2` image of `compose.yaml` are pinned by the
multi-arch index digest with the tag kept for readability, so a rebuild or a `compose up`
resolves the same image until Dependabot refreshes the digest (a digest refresh carries no major,
so the exclusions do not suppress it). The Compose entry ignores every version update of
`postgres`, so the tag stays at the README's 16.2 while digest refreshes still arrive, and
ignores `store` entirely, because Dependabot reads `${STORE_IMAGE:-store:local}` as a Docker Hub
image to look up. Every action is pinned to the commit SHA of its release with the version in a
trailing comment; Dependabot's github-actions ecosystem moves the SHA and rewrites the comment in
the grouped weekly pull request, so a pin never freezes the pipeline. The cost is that Dependabot
security alerts cover only semver-referenced actions, not SHA pins; the weekly update and the
pinned version comment are the review path instead. `gradle/actions/setup-gradle` stays on the v5
line (major updates ignored) because v6 moved the build cache into a closed-source component that
requires separate terms of use. The Gradle wrapper carries `distributionSha256Sum`, so every
distribution it downloads is verified against the checksum before it is unpacked, independently
of `validateDistributionUrl`: the image build stage downloads on every build, runners and
developers verify on their first download and reuse the cache afterwards, and adding the key
invalidated no existing cache.

`.gitlab-ci.yml` is provided for portability and has not been executed in this project; there is
no GitLab project for this repository. It follows the same stages: `build` runs
`./gradlew spotlessCheck build test --rerun` under the same `--rerun` rule while
`generator-test`, in the same stage on `node:22-alpine`, runs `npm ci && npm test` in `utils`
(the GitLab `build` image has no Node), `package` builds the
image once as the local tag `store:ci` and saves it as the `image.tar` artifact, `smoke`
loads that tarball, starts the stack with Compose inside `docker:27-dind` and runs the same
script against `http://docker:8080` and
`http://docker:8081`, `scan` runs Trivy 0.70.0 (the version `aquasecurity/trivy-action@v0.36.0`
installs by default on GitHub) on the tarball with the same severity, `--ignore-unfixed` and
`.trivyignore` settings, and `publish`, on the default branch only, loads the same tarball,
retags it as `$CI_REGISTRY_IMAGE:<short sha>` and `:latest` and pushes both after `package`,
`smoke` and `scan` have passed; as on GitHub, the registry name is touched only by the publish
step, so a merge-request pipeline of a project without a container registry still builds, smokes
and scans.
Seven details differ from GitHub. The trigger scope is expressed as `workflow: rules`: a
pipeline runs for merge requests and for the default branch, so a push to a feature branch
without a merge request runs no pipeline on either platform. The published tag is
`<short sha>` where GitHub's `docker/metadata-action` writes `sha-<short sha>`. The SBOM is
CycloneDX produced by Trivy rather than SPDX produced by Syft; both scans print the findings
table to the job log and neither stores a report. The order of the checks: GitHub runs the
scan, the SBOM and the smoke test one after the other in the single `docker` job, while on
GitLab `smoke` and `scan` both `needs: [package]` and run in parallel after it; `publish` needs
all three, so the gate before a push is the same. The GitLab `build` job keeps only
`build/reports/` as a path artifact (the JUnit XML reaches GitLab through `reports: junit`)
where GitHub also uploads `build/test-results/`. The GitLab job images
(`eclipse-temurin:17-jdk-jammy`, `node:22-alpine`, `docker:27`, `aquasec/trivy:0.70.0`) are
tag-pinned with no Dependabot path, where every GitHub action is SHA-pinned and moved weekly.
GitLab tears the smoke stack down with `docker compose down -v` without `--remove-orphans`.

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
Every error handled by Spring MVC on the API connector is an RFC 7807 problem detail; a
request that never reaches the `DispatcherServlet` (one Tomcat rejects at the connector, such
as an oversized header) and a 404 on the management connector get Boot's default error body
(`{"timestamp","status","error","path"}`) instead. Validation failures, whether of a request
body or of a query parameter such as `name`, `after`, `customerId` or `productId`, include an `errors` array with the offending field
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
* Logs are ECS-formatted JSON inside the container (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`):
  one object per line with nested members, `"log":{"level":...,"logger":...}`,
  `"service":{"name":"store","version":"1.0.0-SNAPSHOT"}` (the service name and version come
  from `spring.application.name` and the build version), `"message"`,
  `"ecs":{"version":"8.11"}`, and every MDC entry as a top-level member, so
  `"requestId":"<id>"` is on every line written while a request is handled. The smoke test's
  `compose` mode proves the shape on the started image rather than asserting it: no 2xx, 400,
  404, 413 or 415 request logs at the shipped levels, so the step sends `DELETE /order/1` with
  `X-Request-Id: smoke-<pid>` (an unsupported method is logged at WARN by
  `org.springframework.web.servlet.PageNotFound` while the id is in the MDC), expects the 405
  problem detail and then requires a `store` log line carrying `"requestId":"smoke-<pid>"` and
  `"service":{"name":"store","version":"`. A data-integrity conflict is logged at WARN by SQL
  state and constraint name, never with the driver message that quotes the request's values
  (the full cause is at DEBUG)
* A database that cannot be reached is a 503, not a 500. A request that cannot obtain a
  connection within the pool's 5 s `connection-timeout` (the pool exhausted, or the database
  down) reaches the handler as `CannotCreateTransactionException`, and a connection lost after
  the transaction began as `DataAccessResourceFailureException`; both are answered with a 503
  problem detail (`The service cannot obtain a database connection`) and `Retry-After: 5`,
  logged at WARN by the cause's class name only (the driver message can carry hosts or SQL;
  the full cause is at DEBUG). The ingress therefore sees a 503 with `Retry-After` for each
  timed-out acquisition, and readiness reports `db` DOWN while no connection can be obtained,
  but only after the same 5 s `connection-timeout`, so probe timeouts must exceed it; a replica
  is then also taken out of rotation. Both recover without a restart once the database answers.
  A 503 on a `POST` does not guarantee the write was not applied: the `POST`s are not
  idempotent (`docs/ASSUMPTIONS.md`, "API contract"). `GlobalExceptionHandlerTests` and
  `GlobalExceptionHandlerLogTest` pin the status, the header and the log line, and
  `OpenApiContractIT` the declaration on every operation
* Shutdown is graceful with a 20 second drain; `compose.yaml` gives the container a 30 second
  stop grace for it, and a Kubernetes deployment needs the same margin in
  `terminationGracePeriodSeconds` (the default is 30 s) or the drain is cut short by `SIGKILL`
* A missing `DB_USERNAME` or `DB_PASSWORD` fails at the first database connection, so the
  process exits during start-up and the orchestrator reports a crash, not a running service
  on sample credentials; `LIQUIBASE_CONTEXTS` unset means no sample data and blank is refused
  at start-up (see Configuration)
* Migrations are a job, not a start-up side effect: the Compose stack runs the same image once
  as `migrate` with the schema owner's credentials before `store` starts, and `store` connects
  as `store_app` (connect, `SELECT`, `INSERT`, sequence usage; no DDL, `UPDATE` or `DELETE`)
  with `SPRING_LIQUIBASE_ENABLED=false`. `ddl-auto: validate` and the readiness `db` component
  prove the runtime works under that role, and the smoke test's `compose` mode proves the
  grants. A Kubernetes rollout runs the image as a `Job` or init container ahead of the
  deployment. `bootRun` keeps in-process Liquibase as `admin` for development. ADR 0008
  records the split, which changeset needs a privileged role, and the `down -v` step for a
  volume created before the job existed
* The Compose `store` and `migrate` services are hardened: `read_only: true` with
  `tmpfs: [/tmp]` (Tomcat's base directory and the JVM's `hsperfdata` live there),
  `cap_drop: [ALL]`, `security_opt: ["no-new-privileges:true"]` and
  `deploy.resources.limits.memory: 512m`, which Compose v2 honours without Swarm and which
  gives the image's `-XX:MaxRAMPercentage=75` a bound to be a percentage of (`MaxHeapSize`
  resolves to 384 MiB inside the container). The limit is a measured choice: after the smoke
  run the service holds about 256 MiB of RSS (255.9 and 256.2 MiB in two readings) under the
  default `TOMCAT_THREADS_MAX` of 200, so 512 MiB leaves the heap room to fill plus metaspace
  and thread stacks; a larger `TOMCAT_THREADS_MAX` or `DB_POOL_SIZE` needs the limit raised
  with it, since each busy thread adds its stack and buffers on top of the heap. `postgres`
  stays unhardened because
  `initdb` runs as root and needs `CHOWN` and `SETUID` to hand the data directory to its own
  user. Without the tmpfs `store` fails at start-up with `Unable to start web server`
  (Tomcat's base directory lives under `java.io.tmpdir`), while `migrate` still exits 0.
  ADR 0008 records the settings; `docker inspect` on the started stack shows them

## Further reading
* `docs/ASSUMPTIONS.md`: the judgement calls behind each task and how they can be defended.
* `docs/PERFORMANCE.md`: the analysis of the slow GET endpoints and the measures taken.
* `docs/adr/`: architecture decision records.
