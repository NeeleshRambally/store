# Store service: design and operations guide

The assessment README at the repository root is kept as delivered. This document describes how
the service is built, run, tested and delivered, and points to the reasoning behind each
decision.

## Prerequisites
* JDK 17 (the Gradle toolchain downloads one if it is missing)
* Docker (used by Docker Compose for local runs and by Testcontainers for the tests)

## Running locally
The README's instructions still work: start PostgreSQL 16.2 on port 5433 with `admin:admin` and
a `store` database, then `./gradlew bootRun`.

The alternative is Docker Compose, which starts PostgreSQL 16.2 on port 5434 and the service
image:
```shell
docker compose up --build
```
The API is then available on http://localhost:8080, the actuator on http://localhost:8081 and
the database on localhost:5434.

To run the service from Gradle against that database instead:
```shell
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Liquibase migrates the schema on start-up. Sample data (100 customers, 10,000 orders, 100
products) is loaded when the `seed` Liquibase context is active, which is the default; set
`LIQUIBASE_CONTEXTS=prod` to skip it. More data can be generated with the script described in
`utils/README.md`.

## Configuration
| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/store` (`5434` with the `local` profile) | JDBC URL |
| `DB_USERNAME` | `admin` | Database user |
| `DB_PASSWORD` | `admin` | Database password |
| `LIQUIBASE_CONTEXTS` | `seed` | Liquibase contexts to apply |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size |
| `MANAGEMENT_PORT` | `8081` | Port of the management connector that serves the actuator |
| `CACHE_TYPE` | `caffeine` | Spring cache provider for the by-id reads; `none` disables caching |
| `CACHE_TTL` | `60s` | Time an `orders` or `products` entry stays cached (`expireAfterWrite`) |
| `NESTED_ORDERS_LIMIT` | `20` | Newest orders embedded in each customer and newest order ids in each product (1 to 100); `orderCount` always carries the total |

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
  requests cover the 200 offset and cursor pages of the three list endpoints and the two by-id
  reads, and the customer-filtered page and the product-filtered cursor page of `/order`; the
  201 and the rejected-body 400 of each `POST` (including a whitespace-only `name`);
  the 400 of a non-numeric id on both by-id paths, of an unknown sort property on `/order` and
  `/products`, of a cursor combined with a page, of a negative `after`, of a `customerId` of 0
  and of `customerId` combined with `productId` on `/order`, and of a multi-word `name` on
  `/customer`; the 404 of an unknown order, of an unknown product and of
  an order for an unknown customer; and the 304 of a conditional `GET /order/{id}`,
  `GET /products/{id}` and `GET /customer` page. The 409 and 500 bodies are validated
  synthetically against `POST /order` and `GET /order`, because no real request reaches them
  (there are no unique constraints and no injected failure). An undocumented response field, a
  missing required field, an undeclared status or a request the specification would reject
  fails the build, so the specification cannot drift from the implementation.

`build` also checks formatting (`spotlessCheck`) and fails below 80% line coverage. Reports land
in `build/reports`.

## Continuous integration
`.github/workflows/ci.yml` runs on every push to `main` and on every pull request:
1. `build`: `./gradlew spotlessCheck build test --rerun`; a failing test fails the pipeline.
   `--rerun` makes the test suite execute on every run instead of being replayed from Gradle's
   cache when only documentation changed.
2. `docker`: builds the image, scans it with Trivy (a critical, fixable vulnerability fails the
   pipeline; results appear under Security), attaches an SPDX SBOM as an artifact, starts the
   built image with Compose (`STORE_IMAGE=store:ci docker compose up -d --no-build`) and runs
   `scripts/smoke-test.sh` against it: the container must report readiness `UP` on 8081 within
   120 seconds, serve `GET /order/1`, `POST /customer`, `GET /products/2` and a cursor page on
   8080, answer 404 for `/actuator/health` on 8080 and 200 for the liveness probe on 8081. A
   failing check prints the container logs and fails the job; the stack is always torn down. On
   `main` the job then pushes the image to GitHub Container Registry as
   `ghcr.io/<owner>/store:<sha>` and `:latest`.

Both jobs must be green and the change reviewed before a merge; GitHub cannot enforce required
checks on a private free-plan repository, so that is a procedural gate.

The same smoke test runs locally against the Compose stack:
```shell
docker compose up -d --build && sh scripts/smoke-test.sh http://localhost:8080 http://localhost:8081
```
The script is POSIX `sh` and needs only `curl`. A local run leaves the `Smoke Test` customer it
creates in the Compose volume; `docker compose down -v` clears it.

Dependabot keeps Gradle dependencies, GitHub Actions and the base images current with weekly
grouped pull requests, which the same pipeline validates.

`.gitlab-ci.yml` is the equivalent pipeline for GitLab: `build`, `package` (image saved as an
artifact), `smoke` (loads the image, starts the stack with Compose inside `docker:27-dind` and
runs the same script against `http://docker:8080` and `http://docker:8081`) and `scan`.

## Data model
An order has an id, a description, belongs to the customer that placed it, and contains one or
more products. A customer has an id, a name, and zero or more orders. A product has an id and a
description and can appear in any number of orders.

## API
The contract is documented in `OpenAPI.yaml`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/customer?page=&size=&sort=` | Page of customers, each with its newest orders and `orderCount` |
| `GET` | `/customer?after=&size=` | Cursor page of customers with an id greater than `after` |
| `POST` | `/customer` | Create a customer: `{"name": "..."}` |
| `GET` | `/customer?name=onne` | Customers whose name contains the word fragment (single word, case-insensitive) |
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
`id` and `name` on `/customer`; any other property is a 400 problem detail (ADR 0006).
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
never carry an id; unknown properties are rejected with 400. An order carries between 1 and 100
products.
Every successful `GET` carries a weak `ETag` and `Cache-Control: no-cache`; sending that ETag
back in `If-None-Match` yields `304 Not Modified` with no body when the representation is
unchanged (ADR 0007). `GET /order/{id}` and `GET /products/{id}` are additionally cached in the
service for `CACHE_TTL`; `products` entries are evicted when an order containing the product is
created, so its order ids and `orderCount` reflect the new order on the next read.

## Operations
The service listens on two ports. The API is served on 8080; the actuator is served on a
separate management connector, 8081 by default (`MANAGEMENT_PORT`), and is absent from 8080
(`/actuator/health` on the application port is a 404). The ingress exposes 8080 only, so the
probes and metrics are reachable from inside the cluster but never from the public network.
* `GET :8081/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`;
  readiness is Spring's `ReadinessState`, which flips to `ACCEPTING_TRAFFIC` once the context
  has started and back on shutdown, so it does not prove that the API connector answers; the
  container smoke test of the pipeline does that
* `GET :8081/actuator/prometheus` for scraping; `cache_gets_total{cache=...,result=hit|miss}`
  shows the hit rate of the `orders` and `products` caches, and `http_server_requests_seconds`
  covers the API port only (management traffic is not recorded)
* `server.compression` applies to both connectors; the `ETag` filter and the `Cache-Control`
  interceptor are registered for the API paths only
* Logs are ECS-formatted JSON inside the container (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)
* Shutdown is graceful with a 20 second drain

## Further reading
* `docs/ASSUMPTIONS.md`: the judgement calls behind each task and how they can be defended.
* `docs/PERFORMANCE.md`: the analysis of the slow GET endpoints and the measures taken.
* `docs/adr/`: architecture decision records.
