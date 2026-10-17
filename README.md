# Store Application
The Store application keeps track of customers and orders in a PostgreSQL database and exposes
them through a paged REST API.

# Prerequisites
* JDK 17 (the Gradle toolchain downloads one if it is missing)
* Docker (used by Docker Compose for local runs and by Testcontainers for the tests)

# Running locally
The quickest way is Docker Compose, which starts PostgreSQL 16.2 and the service image:
```shell
docker compose up --build
```
The API is then available on http://localhost:8080 and the database on localhost:5434.

To run the service from Gradle against that database instead:
```shell
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

The application uses Liquibase to migrate the schema. Sample data (100 customers, 10,000
orders) is loaded when the `seed` Liquibase context is active, which is the default; set
`LIQUIBASE_CONTEXTS=prod` to skip it. More data can be generated with the script described in
utils/README.md.

# Configuration
| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/store` (`5434` with the `local` profile) | JDBC URL |
| `DB_USERNAME` | `admin` | Database user |
| `DB_PASSWORD` | `admin` | Database password |
| `LIQUIBASE_CONTEXTS` | `seed` | Liquibase contexts to apply |
| `DB_POOL_SIZE` | `10` | HikariCP maximum pool size |

# Testing
```shell
./gradlew build
```
runs, in one task and in one JVM:
* unit tests (JUnit 5, Mockito),
* controller slice tests (`@WebMvcTest`),
* integration tests against a real PostgreSQL started by Testcontainers,
* Cucumber acceptance scenarios in `src/test/resources/features`.

`build` also checks formatting (`spotlessCheck`) and fails below 80% line coverage. Reports land
in `build/reports`.

# Continuous integration
`.github/workflows/ci.yml` runs on every push to `main` and on every pull request:
1. `build`: `./gradlew spotlessCheck build`; a failing test fails the pipeline.
2. `docker`: builds the image; on `main` it is pushed to GitHub Container Registry as
   `ghcr.io/<owner>/store:<sha>` and `:latest`.

`.gitlab-ci.yml` is the equivalent pipeline for GitLab.

# Data model
An order has an id, a description, belongs to the customer that placed it, and contains one or
more products. A customer has an id, a name, and zero or more orders. A product has an id and a
description and can appear in any number of orders.

# API
The contract is documented in `OpenAPI.yaml`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/customer?page=&size=&sort=` | Page of customers, each with its orders |
| `POST` | `/customer` | Create a customer: `{"name": "..."}` |
| `GET` | `/customer?name=onne` | Customers whose name contains the word fragment (single word, case-insensitive) |
| `GET` | `/order?page=&size=&sort=` | Page of orders, each with its customer and products |
| `GET` | `/order/{id}` | A single order with its customer and products |
| `POST` | `/order` | Create an order: `{"description": "...", "customerId": 1, "productIds": [2, 8]}` |
| `GET` | `/products?page=&size=&sort=` | Page of products, each with the ids of the orders containing it |
| `GET` | `/products/{id}` | A single product with the ids of the orders containing it |
| `POST` | `/products` | Create a product: `{"description": "..."}` |

List responses are a page envelope: `content`, `page`, `size`, `totalElements`, `totalPages`.
The default page size is 20, the maximum 100, and the default sort is by `id`.
Errors are RFC 7807 problem details; validation failures include an `errors` array with the
offending field names. Requests never carry an id; unknown properties are rejected with 400.

# Operations
* `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`
* `GET /actuator/prometheus` for scraping
* Logs are ECS-formatted JSON inside the container (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)
* Shutdown is graceful with a 20 second drain

# Design notes
Decisions and their reasoning are recorded in `docs/adr` and `docs/ASSUMPTIONS.md`.
The analysis of the slow GET endpoints and the measures taken is in `docs/PERFORMANCE.md`.
