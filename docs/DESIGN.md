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
and the `seed` Liquibase context as defaults, plus `org.hibernate.SQL` at DEBUG, so the console
shows every statement Hibernate sends, formatted, with the JPQL as a comment for queries and
bind values as `?`. An explicit `SPRING_PROFILES_ACTIVE` or `--spring.profiles.active` replaces
the default profile. The file stays in `src/main/resources`, so `bootRun`,
`FailClosedConfigurationTest` and an IDE run that activates `local` find it, but the bootJar,
and with it the container image, leaves it out: `SPRING_PROFILES_ACTIVE=local` on the jar or
the image adds no defaults, and the process fails closed at its first connection like any
environment without credentials (see Configuration). `verifyBootJarContents`, part of `check`,
fails the build if the bootJar ever contains the file. CI sets every variable explicitly.

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
the database on localhost:5434, all three published on 127.0.0.1 only: the API has no
authentication (ADR 0002) and the database role is the `admin:admin` superuser, so none of
them is published on the machine's network interfaces. `STORE_BIND_ADDRESS` publishes them on
another address instead, for example `STORE_BIND_ADDRESS=0.0.0.0 docker compose up` on every
interface, which exposes all three to every network the machine is on. The service starts only
once `migrate` has exited 0, so a failed migration stops the stack before the API comes up.
After upgrading from a stack started before the migration job existed, run
`docker compose down -v` once: the role and its default privileges are created only when the
data directory is empty, and without them `store` fails closed at its first connection.

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
| `DB_USERNAME` | none (`admin` under the `local` profile) | Database user, from the variable or the file `/run/secrets/DB_USERNAME` (see below); without either the first connection fails and the process exits |
| `DB_PASSWORD` | none (`admin` under the `local` profile) | Database password, from the variable or the file `/run/secrets/DB_PASSWORD`; without either the first connection fails and the process exits |
| `LIQUIBASE_CONTEXTS` | `prod` (`seed` under the `local` profile) | Liquibase contexts to apply; only `seed` loads the sample data; must be non-blank: a blank value stops start-up |
| `SPRING_LIQUIBASE_ENABLED` | `true` | Boot's own `spring.liquibase.enabled`; `false` creates no Liquibase bean, which is how the Compose `store` service runs under a role that cannot alter the schema |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size; a connection is waited for at most 5 s (`connection-timeout`), then the request is a 503 |
| `TOMCAT_THREADS_MAX` | `200` | Tomcat request threads (`server.tomcat.threads.max`); see the sizing note below |
| `MANAGEMENT_PORT` | `8081` | Port of the management connector that serves the actuator |
| `CACHE_TYPE` | `caffeine` | Spring cache provider for the by-id reads; `none` disables caching |
| `CACHE_TTL` | `60s` | Time an `orders` or `products` entry stays cached (`expireAfterWrite`) |
| `NESTED_ORDERS_LIMIT` | `20` | Newest orders embedded in each customer and newest order ids in each product (1 to 100); `orderCount` always carries the total |
| `REQUEST_MAX_BODY_BYTES` | `65536` | Largest request body accepted (at least 1024); a longer declared `Content-Length`, or a chunked body read past it, is a 413 problem detail |
| `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS` | none (the logger inherits `INFO`) | Boot's own `logging.level` for the access log (see Operations); `OFF` removes the line per request where an ingress already logs every request |

The pool and the request threads are sized together. With `DB_POOL_SIZE` 10 and 200 request
threads at most 10 requests touch the database concurrently; the rest wait up to 5 s for a
connection and then get a 503 problem detail with `Retry-After: 5` (see Operations), so a
remote database that slows down degrades into retryable 503s rather than into 200 threads
blocked on one pool. Size the pool to the database's connection budget per replica, and the
threads to roughly the pool times the share of request time spent outside the database
(serialisation, compression, the ETag hash); every endpoint spends most of its time in the
database, so the defaults leave the threads well above the pool and the 5 s wait is the real
bound. `FailClosedConfigurationTest` pins the resolved default and the variable.

The two credentials can also be files. `application.yaml` imports
`optional:configtree:/run/secrets/`, which turns each file in that directory into a property
named after the file, with its trailing newline trimmed, so `/run/secrets/DB_USERNAME` and
`/run/secrets/DB_PASSWORD` resolve the two placeholders. A variable of the same name still wins
over its file, and where the directory does not exist (the tests, `bootRun`, the Compose stack
as shipped) the import adds nothing. Compose mounts a secret under the variable's name:
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
Name the files after the variables, never after the properties: a file named
`spring.datasource.password` replaces the property itself, so it silently overrides
`DB_PASSWORD`, and only `SPRING_DATASOURCE_PASSWORD` would still beat it.

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
* an OpenAPI contract check (`OpenApiContractIT`): captured request/response pairs are validated
  against the checked-in `OpenAPI.yaml` with `swagger-request-validator`. Real requests cover
  the 200 offset and cursor pages of the three list endpoints and the three by-id reads, and the
  customer-filtered page and the product-filtered cursor page of `/order`; the 201 and the
  rejected-body 400 of each `POST` (including a whitespace-only `name`, a repeated product id, a
  fractional `customerId`, a numeric product `description`, a NUL in each request string, and an
  order body that repeats a property or has content after it);
  the 400 of a non-numeric id on the three by-id paths, of an unknown sort property on `/order`
  and `/products`, of a cursor combined with a page, of a negative `after`, of a negative or
  hexadecimal `page`, of a `size` beyond the 32-bit range, of a `customerId` of 0 and of
  `customerId` combined with `productId` on `/order`, and of a multi-word `name` and of a
  `name` containing a
  NUL on `/customer` (while a no-break space, and a word containing U+0085 and U+2028, are
  accepted by both); the 404 of an unknown order, product and customer and of an order for an
  unknown customer; the 413 of an oversized body and the 415 of a `POST /order` sent as
  `text/plain`; and the 304 of a conditional `GET /order/{id}`, `GET /products/{id}`,
  `GET /customer/{id}` and `GET /customer` page. A 405 for a method a path does not list cannot
  be declared per operation, because the method is absent from the contract; `info.description`
  states it and the smoke test sends `DELETE /order/1` to check it. The 409, 500 and 503 bodies
  are validated synthetically against `POST /order` and `GET /order`, because no real request
  reaches them (there are no unique constraints and no injected failure). The specification
  declares `X-Request-Id` as a required header on every response, `Location` on every 201 and
  `Retry-After` on every 503, and the validator enforces declared headers, so a response that
  drops one fails the check (the synthetic 409 and 500 cases had to carry the id before they
  passed again, and a synthetic 503 without `Retry-After` is rejected, which a case pins). An
  undocumented response field, a missing required field at any depth, a problem detail without a
  `code` or with one the specification does not list (two synthetic cases pin both), a missing
  declared header, an undeclared status or a request the specification would reject fails the
  build, so the specification cannot drift from the implementation.

The test classes follow one naming convention per tier: `*Test` runs without a Spring test
context (JUnit 5, Mockito, `ApplicationContextRunner`: `NestedOrdersPropertiesTest`,
`RequestBodyLimitPropertiesTest`), `*Tests` is a `@WebMvcTest` slice (all six), and `*IT` runs
on the shared Testcontainers context (`CucumberIT` is the acceptance suite on the same
context). Where a unit test and a slice cover the same class the slice carries `Slice` in its
name (`RequestBodyLimitFilterTest`, `RequestBodyLimitFilterSliceTests`), so the tier is never a
single letter apart.

`build` also checks formatting (`spotlessCheck`), checks that the bootJar does not contain the
local profile (`verifyBootJarContents`, see Running locally) and fails below 80% line
coverage. Two exclusions apply to the report and the gate alike: `**/mapper/**` (the whole
`mapper` package: the MapStruct-generated implementations and the hand-written default method
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
   generated by Syft as the single `sbom` artifact, kept for 90 days (`retention-days: 90`; the
   action's own upload is switched off, and ASSUMPTIONS "Delivery" says how to describe an
   image after that), starts the built image with Compose
   (`STORE_IMAGE=store:ci docker compose up -d --no-build`, which runs the `migrate` job and
   returns only once it has exited 0) and runs `scripts/smoke-test.sh ... compose` against it:
   the container must report readiness `UP` on 8081 within 120 seconds with the `db`
   component in the body, serve `GET /order/1`, `POST /customer`, `GET /products/2` and a cursor
   page on 8080 and reject a 70,000 byte body with a 413, answer 404 for `/actuator/health` on
   8080 and 200 for the liveness probe on 8081; then, in `compose` mode, the `migrate`
   container's exit code must be 0, `docker inspect` must show the `migrate` and `store`
   containers with a read-only root filesystem, a tmpfs on `/tmp`, `CapDrop` `ALL`,
   `no-new-privileges:true` and `Memory` and `MemorySwap` both 536870912 (512 MiB without
   swap) and every port `postgres` and `store` publish bound to `STORE_BIND_ADDRESS`
   (127.0.0.1 when unset), `store_app` must be able to `select count(*) from customer`
   through `psql` in the database container, and `create table` and `update customer` as
   `store_app` must fail with `permission denied for schema public` and `permission denied for
   table customer`, `DELETE /order/1` must be a 405 problem detail, `GET /order/1` sent with
   `X-Request-Id: smoke-<pid>` must leave its access line in the `store` container's output as
   ECS JSON with the `com.example.store.access` logger, `"requestId":"smoke-<pid>"`, the route
   `/order/{id}` and the `service` name and version (see Operations), and that output must not
   contain a `Picked up JAVA_TOOL_OPTIONS` line. A failing
   step prints the `migrate` and `store` logs and fails the job; the
   stack is always torn down. On `main` the job then tags that same `store:ci` image and pushes
   it to GitHub Container Registry as `ghcr.io/<owner>/store:sha-<short sha>` and `:latest`,
   so the digest delivered is
   the digest that was scanned and smoke-tested, not a rebuild. The jar inside the image is
   compiled from the same commit by the image build (wrapper-pinned Gradle, BOM-locked
   versions) and the tested jar is the `build` job's, so the artifact identity is the commit,
   not the jar bytes.

The image build warms its Gradle cache with `./gradlew resolveDependencies`, a task in
`build.gradle` that downloads every jar of the compile, annotation-processor and runtime
classpaths (96 jars, about 71 MB) in a layer keyed on the build files alone. A change under
`src` reuses that layer, and the bootJar step then compiles without a download (it succeeds
with `--offline`). A library that cannot be resolved, because it does not exist or its
repository cannot be reached, fails the warm-up with `Could not resolve all files for
configuration ...`; Gradle's `dependencies` report would not serve here, because it resolves
metadata only and exits 0 when a library is missing, printing `FAILED` in the report.

Both jobs carry a `timeout-minutes`: 20 for `build` and 30 for `docker`. The last 15 green
push runs on `main`, from run 39172594999 (2026-10-18) to run 39289734712 (2026-10-19), took
2.0 to 3.0 minutes for `build` and 2.4 to 5.7 minutes for `docker` from job start to job
completion (read from the jobs API on 2026-10-19), and a `docker` run with a cold build cache
can reach about 10 minutes, so the limits leave a wide margin over a normal run while a hung
Testcontainers pull, Gradle daemon or image pull releases the runner within half an hour
instead of GitHub's six-hour default.

Pulling the delivered image: the package is private because the repository is, so log in with
a token that carries `read:packages`, then pull it and run it on the network of the Compose
stack's database, where PostgreSQL answers as `postgres`. The network is `<project>_default`,
and the project name is the checkout's directory name unless `COMPOSE_PROJECT_NAME` sets
another, so a clone named `store` gives `store_default`:
```shell
docker login ghcr.io
docker pull ghcr.io/neeleshambally/store:latest
docker compose up -d postgres
docker run --rm --network store_default -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8081 \
  -e DB_URL=jdbc:postgresql://postgres:5432/store \
  -e DB_USERNAME=admin -e DB_PASSWORD=admin -e LIQUIBASE_CONTEXTS=seed \
  ghcr.io/neeleshambally/store:latest
```
The Compose network works on every engine; `host.docker.internal` is Docker Desktop's name for
the host, and a Linux engine has it only through `--add-host host.docker.internal:host-gateway`,
which resolves to the bridge gateway, where the database port, published on 127.0.0.1, does not
listen. Run alone like this, the image migrates in-process as the user it is given; the Compose
stack splits that into the `migrate` job and the `store_app` runtime (see Running locally), and
`STORE_IMAGE=ghcr.io/neeleshambally/store:latest docker compose up -d --no-build` runs the
pulled image that way.

Both jobs must be green and the change reviewed before a merge; GitHub cannot enforce required
checks on a private free-plan repository, so that is a procedural gate. Permissions are scoped
per job: `build` has `contents: read`, `docker` has `contents: read` and `packages: write` for
the push.

The same smoke test runs locally against the Compose stack:
```shell
docker compose up -d --build && sh scripts/smoke-test.sh http://localhost:8080 http://localhost:8081 compose
```
The script is POSIX `sh` and needs only `curl`; the optional third argument `compose` adds the
migration-job, hardening, port-binding and role checks, which use `docker compose` and
`docker inspect` and must run from the repository root. The port-binding check expects every
published port on `STORE_BIND_ADDRESS` (127.0.0.1 when unset), so the script must run with the
value the stack was started with. A local run leaves the `Smoke Test` customer it creates in
the Compose volume; `docker compose down -v` clears it.

Dependabot opens weekly pull requests, validated by the same pipeline: Gradle dependencies with
Spring artifacts grouped and majors excluded, GitHub Actions as one grouped pull request including
majors except for `gradle/actions`, the `Dockerfile` base images as one grouped pull request, and
the `compose.yaml` images as the `compose-images` group. The Java major-version exclusion of the
`docker` entry names `eclipse-temurin`, so it governs only the build stage; the runtime stage's
`gcr.io/distroless/java17-debian13:nonroot` has no version in its tag, so its updates are digest
refreshes only, and a move to another Java line is a change of image name, which Dependabot does
not propose. Both `FROM` lines of the `Dockerfile` and the `postgres:16.2` image of `compose.yaml`
are pinned by the multi-arch index digest with the tag kept for readability, so a rebuild or a
`compose up` resolves the same image until Dependabot refreshes the digest (a digest refresh
carries no major, so the exclusions do not suppress it). The Compose entry ignores every version
update of `postgres`, so the tag stays at the README's 16.2 while digest refreshes still arrive,
and ignores `store` entirely, because Dependabot reads `${STORE_IMAGE:-store:local}` as a Docker
Hub image to look up. Every action is pinned to the commit SHA of its release with the version in
a trailing comment; Dependabot's github-actions ecosystem moves the SHA and rewrites the comment
in the grouped weekly pull request, so a pin never freezes the pipeline. The cost is that
Dependabot security alerts cover only semver-referenced actions, not SHA pins; the weekly update
and the pinned version comment are the review path instead. `gradle/actions/setup-gradle` stays on
the v5 line (major updates ignored) because v6 moved the build cache into a closed-source
component that requires separate terms of use. The Gradle wrapper carries `distributionSha256Sum`,
so every distribution it downloads is verified against the checksum before it is unpacked,
independently of `validateDistributionUrl`: the image build stage downloads on every build,
runners and developers verify on their first download and reuse the cache afterwards, and adding
the key invalidated no existing cache.

`.gitlab-ci.yml` is provided for portability; there is no GitLab project for this repository.
It follows the same stages: `build` runs
`./gradlew spotlessCheck build test --rerun` under the same `--rerun` rule while
`generator-test`, in the same stage on `node:22-alpine`, runs `npm ci && npm test` in `utils`
(the GitLab `build` image has no Node), `package` builds the
image once as the local tag `store:ci` and saves it as the `image.tar` artifact, `smoke`
loads that tarball, starts the stack with Compose inside `docker:27-dind` with
`STORE_BIND_ADDRESS: "0.0.0.0"` and runs the same script against `http://docker:8080` and
`http://docker:8081`, `scan` runs Trivy 0.70.0 (the version `aquasecurity/trivy-action@v0.36.0`
installs by default on GitHub) on the tarball with the same severity, `--ignore-unfixed` and
`.trivyignore` settings, and `publish`, on the default branch only, loads the same tarball,
retags it as `$CI_REGISTRY_IMAGE:<short sha>` and `:latest` and pushes both after `package`,
`smoke` and `scan` have passed; as on GitHub, the registry name is touched only by the publish
step, so a merge-request pipeline of a project without a container registry still builds, smokes
and scans. Each job carries the limit of the GitHub job it stands for as a `timeout`: 20 minutes
for `build` and `generator-test`, 30 for `package`, `smoke`, `scan` and `publish`. Every job is
`interruptible` (set under `default:`) except `publish`, and the default-branch entry of
`workflow: rules` sets `auto_cancel: on_new_commit: none`, so a new commit cancels a running
merge-request pipeline and never a default-branch one, as GitHub's `cancel-in-progress` does for
pull requests only. `publish` holds the `registry-publish` resource group, so two pipelines never
push at the same time, and `package` labels the image with `org.opencontainers.image.revision`
(`$CI_COMMIT_SHA`), `org.opencontainers.image.source` (`$CI_PROJECT_URL`) and
`org.opencontainers.image.created` (`$CI_PIPELINE_CREATED_AT`).
Thirteen details differ from GitHub. The trigger scope is expressed as `workflow: rules`: a
pipeline runs for merge requests and for the default branch, so a push to a feature branch
without a merge request runs no pipeline on either platform. The published tag is
`<short sha>` where GitHub's `docker/metadata-action` writes `sha-<short sha>`. The SBOM is
CycloneDX produced by Trivy rather than SPDX produced by Syft; both platforms keep it for 90 days
(`expire_in: 90 days`, `retention-days: 90`), except that GitLab keeps the artifacts of the newest
successful pipeline of each ref past that unless the project or the instance turns that off; both
scans print the findings table to the job log and neither stores a report. The order of the
checks: GitHub runs the scan, the SBOM and the smoke test one after the other in the single
`docker` job, while on GitLab `smoke` and `scan` both `needs: [package]` and run in parallel
after it; `publish` needs all three, so the gate before a push is the same. The GitLab `build`
job keeps only `build/reports/` as a path artifact (the JUnit XML reaches GitLab through
`reports: junit`) where GitHub also uploads `build/test-results/`. The GitLab job images
(`eclipse-temurin:17-jdk-jammy`, `node:22-alpine`, `docker:27`, `aquasec/trivy:0.70.0`) are
tag-pinned with no Dependabot path, where every GitHub action is SHA-pinned and moved weekly.
GitLab tears the smoke stack down with `docker compose down -v` without `--remove-orphans`.
The `smoke` job sets `STORE_BIND_ADDRESS: "0.0.0.0"`, where GitHub keeps the loopback default:
on the Docker executor `docker:27-dind` is a separate container that the job reaches as
`docker`, and a port that daemon publishes on its own 127.0.0.1 refuses the job container's
connections, so the variable is required there; the script reads the same variable and expects
`0.0.0.0`. The binding opens the ports on the dind container's interfaces, not on the runner
host's. On a Kubernetes executor the job and its services share the pod's network namespace,
so loopback would work and the variable is optional; `0.0.0.0` there also opens the ports on
the pod IP for the length of the job, which is acceptable for an ephemeral job. `package` has no
layer cache: each job starts an empty `docker:27-dind` daemon, so every image build pulls the
base images and runs the Gradle warm-up again, where GitHub's build restores its layers from the
`gha` cache (`cache-from` and `cache-to`). The file has never run: it passes GitLab's CI JSON
schema (`app/assets/javascripts/editor/schema/ci.json` in `gitlab-org/gitlab`), which checks
keyword names and value types but not what the jobs do, while the GitHub workflow runs on every
pull request and every push to `main`. The resource group's default `unordered` process mode
only keeps two publishes apart, so an older pipeline can still push `:latest` after a newer one;
publishing in pipeline order needs the group's `process_mode` set to `oldest_first` once through
the API (`PUT /projects/:id/resource_groups/registry-publish`), which the file cannot express,
while GitHub runs the `main` runs of its concurrency group one at a time and replaces a waiting
run with a newer one, so the newest merge pushes last. `package` sets three OCI labels where
`docker/metadata-action` sets eight (`title`, `description`, `url`, `source`, `version`,
`created`, `revision` and `licenses`), so a GitLab image has no title, description, url, version
or licenses label, and its `created` is the pipeline's creation time where GitHub's is the time
of the metadata step. Cancelling a superseded merge-request pipeline also depends on the
project's "Auto-cancel redundant pipelines" setting, which the file cannot set (with it off,
`interruptible` has no effect), where GitHub's `concurrency` block decides it in the workflow
alone.

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
body or of a query parameter such as `name`, `after`, `customerId`, `productId`, `page` or
`size`, include an `errors` array with the offending field names and the constraint messages. A
non-numeric path id is a 400 problem detail. Requests never carry an id; an unknown property is
a 400 that names it (see the input rules below). A request body over
`REQUEST_MAX_BODY_BYTES` (64 KiB by default) is a 413 problem detail (`Request body exceeds
65536 bytes`), judged by the declared `Content-Length` before any byte is read or by the bytes
counted while a chunked body is read; Tomcat appends `Connection: close` to the 413 and
discards the unread remainder, up to its 2 MB swallow limit. An order carries between 1 and 100
products, each id at most once: a repeated id is a 400 naming `productIds`, not a silent
de-duplication. Every `POST` answers 201 with a `Location` header holding the relative path of
the created resource (`/order/{id}`, `/products/{id}`, `/customer/{id}`); the path is relative
so that a gateway rewriting the host stays correct, which RFC 9110 permits.

Every problem detail carries `code`, the stable identity of the problem, next to the status
phrase in `title` and the human-readable `detail`; `type` stays `about:blank`
(`docs/ASSUMPTIONS.md`, "API contract", gives the reason). A client branches on `code`, never on
the wording of `detail`:

| `code` | Status | Raised for |
|---|---|---|
| `validation_failed` | 400 | A body field or a query parameter that breaks a constraint, or a body value of the wrong JSON type or beyond the 64-bit range; `errors` names each |
| `invalid_parameter` | 400 | A path or query parameter that does not convert (`GET /order/abc`, `page=abc`), or a repeated single-valued query parameter (named in `errors`) |
| `unknown_property` | 400 | A body property the request does not define (named in `errors`) |
| `malformed_body` | 400 | A body that is not one readable JSON object (malformed JSON, another JSON value, a repeated property, content after the object) |
| `invalid_sort` | 400 | A `sort` property outside the endpoint's list |
| `invalid_cursor` | 400 | `after` combined with a `page` other than 0 or a `sort` whose first property is not `id` ascending |
| `conflicting_filters` | 400 | `customerId` combined with `productId` |
| `not_found` | 404 | An unknown id in the path, an unknown customer or product in a `POST /order` body, or a path without a handler |
| `method_not_allowed` | 405 | A method the path does not support |
| `not_acceptable` | 406 | An `Accept` header naming no media type the endpoint produces |
| `conflict` | 409 | A write that a database constraint rejects |
| `payload_too_large` | 413 | A body over `REQUEST_MAX_BODY_BYTES` |
| `unsupported_media_type` | 415 | A body that is not `application/json` |
| `internal_error` | 500 | An unexpected failure (no `detail`) |
| `service_unavailable` | 503 | A database connection that cannot be obtained (with `Retry-After`) |

`GlobalExceptionHandler` sets the code in each of its own handlers. The problem details that
Spring MVC's `ResponseEntityExceptionHandler` builds (a type mismatch, a path without a handler,
405, 406 and 415) all return through `handleExceptionInternal`, whose override takes the code
from the status: 400 `invalid_parameter`, 404 `not_found`, 405, 406, 413, 415 and 503 by the
names above, and `internal_error` for any other status. `OpenAPI.yaml` declares `code` as a
required enum, so the contract check fails on a problem detail without a code or with one the
specification does not list. Clients must accept a code they do not know and handle it by its
status: adding a code is a minor version of the contract, while removing or renaming one is a
major version.

Request input is strict rather than coerced (ADR 0002):
- A body that does not bind names the field. An unknown property is a 400 with the detail
  `Unknown property 'id'` and an `errors` entry `is not a known property`; a value of the wrong
  JSON type is a 400 with the detail `Invalid request content.` and an `errors` entry that names
  the field by its path (`productIds[0]`) with `must be an integer`, `must be a string` or
  `must be an array`; an integer outside the 64-bit range (`9223372036854775808`) is named the
  same way, with `must be a 64-bit integer`. Malformed JSON, a body that is not a JSON object,
  an object that repeats a property, anything but whitespace after the object and a number of
  more than 1,000 digits (Jackson's parser refuses it before it reaches a field) are a 400 with
  the detail `Failed to read request` and no `errors`.
  `spring.jackson.deserialization.fail-on-trailing-tokens` is on because Jackson otherwise stops
  reading after the first JSON value: an order body followed by a stray `x` used to create the
  order. `spring.jackson.parser.strict-duplicate-detection` is on because Jackson otherwise keeps
  the last occurrence of a repeated property: `{"customerId":5,"customerId":6}` used to create
  an order for customer 6. A repetition that came after every field had appeared once was a 500
  instead, logged as an unhandled exception: Jackson had already built the record and failed
  with a type-definition error, which Spring does not report as an unreadable body.
- JSON types are bound exactly. A JSON string, a fraction or an exponent where an integer is
  expected (`"5"`, `""`, `5.7`, `5.0`, `5e0`) and a number or a boolean where a string is
  expected are such type failures, where Jackson's defaults would coerce them
  (`"customerId": 5.7` used to create an order for customer 5).
  `spring.jackson.deserialization.accept-float-as-int` and
  `spring.jackson.mapper.allow-coercion-of-scalars` are off, and `JacksonConfig` fails the
  number-to-text and boolean-to-text coercions those two leave on. Jackson stops at the first
  field it cannot bind, so `errors` lists one type failure even when several fields are wrong.
- The strings of a request body (`description`, `name`) are single-line text: a control
  character (U+0000 to U+001F or U+007F, so also a tab or a line break) is a 400 naming the
  field (`must not contain control characters`). A NUL used to reach the insert, which
  PostgreSQL rejects in text, and came back as a 409; a tab or a line break was stored.
- `name` on `GET /customer` is a single word: ASCII whitespace, a C0 control character or DEL
  (U+0000 to U+001F, U+007F) is a 400 naming `name`
  (`must be a single word without whitespace or control characters`); a NUL used to reach
  PostgreSQL, which rejects it in text, and came back as a 409. A no-break space, a C1 control
  such as U+0085 and U+2028 are part of the word, in the server and the specification alike.
- `page` below 0 and `size` below 1 are 400s naming the parameter
  (`must be greater than or equal to 0` or `1`), in both paging modes, where Spring Data's
  resolver used to normalise them silently (`page=-1` became page 0 and `size=0` the default
  20). A value that is not a decimal integer (`abc`, `0x2`, `#3`, `1 0`, or one with a leading
  space, which is what the unencoded plus of `page=+2` decodes to) or one outside the 32-bit
  range is the type-mismatch 400 (`Failed to convert 'page' with value: 'abc'`) without
  `errors`; an empty or blank value counts as absent. A `size` above 100 is still capped at
  100. `PagingParameterBinding` parses both parameters as the resolver does
  (`Integer.valueOf`): Spring's number conversion strips whitespace and reads `0x` and `#` as
  hexadecimal, while the resolver parses the raw text and falls back to page 0 or size 20 when
  it cannot, so `page=0x2` used to pass validation as 2 and be served as page 0. It registers an
  editor on the binder, not a converter, because the binder retries a failed conversion with its
  default number editor, which parses the same lenient way.
- Every query parameter an endpoint binds as a single value (`name`, `customerId`,
  `productId`, `after`, `page`, `size`) is a 400 when repeated, with the detail
  `'name' must not be repeated` and an `errors` entry for it, where Spring used to keep the first
  value of a repeated number and join a repeated string with commas. The check runs after
  handler mapping and before the arguments are bound, so a repetition is reported even when a
  value is also invalid. `sort` may be repeated to sort by several properties, and a comma
  inside a single `name` stays part of the word.

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
  covers the API port only (management traffic is not recorded). Its series carry cumulative
  latency buckets at 5, 10, 25, 50, 100, 250 and 500 ms and 1, 2.5, 5 and 10 s
  (`management.metrics.distribution.slo.http.server.requests`), so
  `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`
  gives the p99 of each route across replicas, and the share of requests within a bound is
  read from its bucket directly. That costs 12 `_bucket` lines per series (one per bound and
  `+Inf`; a series is one combination of method, `uri`, status, outcome and exception), about
  1.9 KB of scrape per series: 27,233 B for the 14 series left by the smoke test and a
  1,000-request mix on the Compose stack. `percentiles-histogram` would publish Micrometer's
  generic bucket set instead, 69 lines per series for this timer (about 11.6 KB each).
  `ActuatorIT` pins the bucket set
* Every response carries an `X-Request-Id`. An inbound value is echoed when it matches
  `[A-Za-z0-9._-]{1,128}`; otherwise, or when absent, a UUID is generated. The header is
  declared as required on every response in `OpenAPI.yaml` and the contract check enforces it.
  The filter runs
  before every other filter, so 304, 400, 404 and 500 responses carry it too, and the id is in
  the MDC as `requestId` for the duration of the request, so every log line of a request can be
  found from the id a client quotes, and every request on the API port writes at least its
  access line (below). There is no tracing library: no collector is in scope, and
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
  `"ecs":{"version":"8.11"}`, and every MDC entry and every key-value pair of the event as a
  top-level member, so `"requestId":"<id>"` is on every line written while a request is
  handled. The smoke test's `compose` mode checks the shape on the started image: it sends
  `GET /order/1` with `X-Request-Id: smoke-<pid>` and requires that request's access line in
  the `store` output with `"logger":"com.example.store.access"`, `"requestId":"smoke-<pid>"`,
  `"route":"/order/{id}"` and `"service":{"name":"store","version":"`, looking up to five times
  a second apart, because the ETag filter flushes the response before the line is written. A
  data-integrity conflict is logged at WARN by SQL state and constraint name, never with the
  driver message that quotes the request's values (the full cause is at DEBUG)
* Every request on the API port writes one INFO line on the logger `com.example.store.access`
  when it completes, for example `GET /order/{id} 200 3 ms`: the method, the route, the status
  and the duration in whole milliseconds, so a cache hit usually logs 0 ms. In the ECS output
  `route`, `status` (a number) and `durationMs` are also top-level members beside `message` and
  `requestId`, so a log pipeline can filter and aggregate on them without parsing the message.
  The route is the pattern Spring MVC matched (`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`),
  the value of the `uri` tag of `http_server_requests_seconds` for the same request: a 304, a
  400, a 404 from the service, a 413 and a 415 carry their endpoint's pattern, a path that no
  controller maps is served by the static-resource mapping and logs `/**`, and a 405, which
  matches no handler, logs `UNKNOWN`. The raw path, the query string and the body are never
  logged, so search words stay out of the log and the set of routes stays bounded.
  `AccessLogFilter` is ordered right after `RequestIdFilter` and `RequestBodyLimitFilter` and
  outside the ETag filter, so a 304 is logged as 304; like them it is registered on the API
  connector only, so probes and scrapes on the management port write nothing. `AccessLogIT`
  pins the line and its members, the 304, the `/**` and `UNKNOWN` routes, the missing query
  string and the silent management port. A line is about 395 B on stdout (the mean of 1,008
  lines left by the smoke test and a 1,000-request mix on the Compose stack, 370 to 403 B),
  roughly 400 MB per million requests per replica. Its cost was measured on the server, as the
  mean of the request timer, which encloses the filter, over 50,000 cached `GET /order/1` from
  one keep-alive client (`ab -k -c 1`, Docker Desktop on arm64): 46.4 and 48.6 µs in two runs
  with the line, 29.4 and 30.3 µs in two runs with `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF`,
  so formatting and writing the line takes about 17 µs. For a 3-statement `GET /order?size=20`
  the difference was within the spread between runs (766 and 785 µs with the line, 738 and
  777 µs without), the client's throughput did not change (about 3,100 and 930 requests per
  second) and no statement is added (`QueryCountIT` is unchanged). An ingress usually logs
  every request as well; where it does, `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF` turns this
  line off without touching any other logger
* A created order, customer or product writes one INFO line on
  `com.example.store.service.CreationEventLogger` once its transaction has committed:
  `Created order 10051 for customer 2 with 3 products`, `Created customer 101` or
  `Created product 101`, with the request's `requestId` beside it in the ECS output; ids and
  the number of products only, never a name or a description. The services publish
  `OrderCreatedEvent`, `CustomerCreatedEvent` and `ProductCreatedEvent`, and
  `@TransactionalEventListener(phase = AFTER_COMMIT)` methods log them: with sequence ids the
  inserts are flushed at commit, after `create()` has returned, so a line written in the
  service would also appear for a creation whose commit fails. `CreationLogIT` pins the three
  lines and that a creation whose insert fails at commit logs nothing (it calls each service
  directly with a 256-character text, which the request validation would have refused, so
  that only the column rejects it)
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
* The image runs the layered jar as the non-root user 65532 on
  `gcr.io/distroless/java17-debian13:nonroot`, pinned by digest: Eclipse Temurin 17.0.20.1,
  installed as the Debian package `temurin-17-jre`, among 25 Debian 13 packages, with no shell,
  package manager, `curl` or `wget`. The JVM flags are arguments of the `ENTRYPOINT`
  (`/usr/bin/java -XX:+UseSerialGC -XX:MaxRAMPercentage=30 -XX:+ExitOnOutOfMemoryError
  org.springframework.boot.loader.launch.JarLauncher`), and the image sets no
  `JAVA_TOOL_OPTIONS`: the JVM announces that variable, even an empty one, with a plain-text
  `Picked up JAVA_TOOL_OPTIONS: ...` line on stderr ahead of the JSON, and the smoke test's
  `compose` mode fails on such a line. Command-line flags win over `JAVA_TOOL_OPTIONS` and
  `JDK_JAVA_OPTIONS`, so an operator can attach an agent through `JAVA_TOOL_OPTIONS` without
  losing the heap flags (the JVM then prints its own `Picked up` line); only `_JAVA_OPTIONS` or
  a replaced entrypoint changes them. Without a shell there is no `docker exec` into the
  container; debug locally with the `:debug-nonroot` variant of the same image, which adds a
  busybox shell and is never shipped. Distroless retires a Java image when it stops updating
  that Java version on a Debian release (`java17-debian12` is no longer updated), so
  `java17-debian13` may stop receiving updates while the service is still on Java 17. A stale
  JRE alone passes the image scan: the scan lists `temurin-17-jre`, but none of its advisory
  data covers Adoptium's package (ADR 0004), so JRE fixes arrive only with a new digest of the
  base, the refresh Dependabot proposes (see Continuous integration). A stale base fails the
  scan only once another of its Debian packages has a fixable high or critical advisory, as
  `java17-debian12` already does through `libexpat1`, `libpcre2-8-0` and `liblcms2-2` (and
  through its JRE, Debian's own `openjdk-17` build, which Debian's advisories cover), and the
  way out is a move to a newer Java line (a `java21` image and the matching toolchain)
* The Compose `store` and `migrate` services are hardened: `read_only: true` with `tmpfs: [/tmp]`
  (Tomcat's base directory and the JVM's `hsperfdata` live there), `cap_drop: [ALL]`,
  `security_opt: ["no-new-privileges:true"]`, `deploy.resources.limits.memory: 512m`, which
  Compose v2 honours without Swarm, and `memswap_limit: 512m`, without which Docker allows as
  much swap again as the limit, so an overrun swaps silently instead of failing. The heap is a
  share of that limit: `-XX:MaxRAMPercentage=30` resolves `MaxHeapSize` to 154 MiB, and the rest
  holds metaspace, the code cache, thread stacks and native buffers. The share is measured. A
  load generator kept 200 keep-alive clients busy for 300 s on the list, by-id, cursor and search
  reads and both `POST` endpoints, half of them asking for gzip, with the heap fully resident
  from the start (`-XX:InitialRAMPercentage` equal to the maximum and `-XX:+AlwaysPreTouch`, the
  state a long-running process approaches); peaks were read from the cgroup's `memory.peak` and
  `memory.events`, `docker stats` and the process's `VmHWM`, and native memory tracking through
  the HotSpot attach socket from a sidecar container sharing the PID namespace, since the JRE
  image has no `jcmd`. At 30% the container peaked at 477.4 MiB with 241 threads, flat after 120
  s, and at about 440 MiB with 50 clients (2,686 requests per second, p99 64.7 ms). At 75% (384
  MiB of heap) the same load needed 636.8 MiB by native memory tracking, hit the limit and
  swapped 104.7 MiB, and with swap off the container was killed (exit 137); 40% also reached the
  limit, and 35% came within 1.1 MiB of it (510.9 MiB) in five minutes. SerialGC is set
  explicitly: G1's own native structures took 59 to 63 MiB at a 35% heap and that run overran the
  limit at 200 clients, and the JVM's ergonomics switch to G1 as soon as a deployment grants 1792
  MiB or more with two or more CPUs. SerialGC's cost at 200 clients was 2,050 young collections
  (13.5 s) and 3 full ones (283 ms) in 300 s. The runs used images with the Dockerfile's layers
  and flags and its JVM, Temurin 17.0.20.1, on Docker Desktop on arm64 with transparent huge
  pages set to `always` and 2040 KiB thread stacks (the arm64 default); a Linux host with the
  common `madvise` setting, such as the amd64 CI runner, is expected to use less. A larger
  `TOMCAT_THREADS_MAX`, `DB_POOL_SIZE` or cache needs the limit raised and the share re-measured,
  since each busy thread adds its stack and buffers outside the heap. `postgres` stays unhardened
  because `initdb` runs as root and needs `CHOWN` and `SETUID` to hand the data directory to its
  own user. Without the tmpfs `store` fails at start-up with `Unable to start web server`
  (Tomcat's base directory lives under `java.io.tmpdir`), while `migrate` still exits 0. ADR 0008
  records the settings. The smoke test's `compose` mode reads each of them back with
  `docker inspect` on both containers, so a pipeline run fails when one is removed, and a new
  limit needs the asserted 536870912 changed with it

## Further reading
* `docs/ASSUMPTIONS.md`: the judgement calls behind each task and how they can be defended.
* `docs/PERFORMANCE.md`: the analysis of the slow GET endpoints and the measures taken.
* `docs/adr/`: architecture decision records.
