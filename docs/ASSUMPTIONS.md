# Assumptions and judgement calls

The task descriptions were deliberately vague. These are the decisions taken and the reasoning,
so they can be defended or revisited.

## Production posture
- The project is treated as a production service: every change ships with tests at the unit,
  integration (real PostgreSQL via Testcontainers) and acceptance (Cucumber) level, and CI fails
  on any failing test, formatting violation or coverage below 80% of lines.
- The service is assumed to run behind an authenticating gateway; see ADR 0002 for why
  authentication is deferred and what is shipped in the meantime.
- Sample data is loaded only when the `seed` Liquibase context is active. It is active by
  default for a developer's convenience and must be switched off in production with
  `LIQUIBASE_CONTEXTS=prod` (any value other than `seed`).

## API contract
- Clients can never supply an id. Request bodies are explicit records; unknown properties are
  rejected with 400 so a client that still sends `id` learns immediately instead of silently
  overwriting data (which the original code allowed).
- The list endpoints are paged (default 20, maximum 100, sorted by id). This is a breaking
  change relative to the original "return everything" behaviour and is the correct trade for a
  table of 10,000 rows that only grows. See ADR 0003.
- `POST /order` takes `customerId` rather than a nested customer object; an unknown customer is
  a 404 and a missing one a 400.
- Errors are RFC 7807 problem details. Validation failures list the offending fields; nothing
  else about the failure is exposed.
- `/customer` and `/order` stay singular to match the original README; `/products` will be
  plural because the task names it so. The inconsistency is acknowledged rather than silently
  changed.

## Performance (README task 3)
- The database is remote with high latency, so the metric that matters is round trips per
  request, not rows. Each list endpoint now performs a fixed number of statements (2 for orders,
  3 for customers) regardless of page size, and an integration test pins those numbers.
- The foreign key `order.customer_id` is indexed. PostgreSQL does not do this automatically.
- Open-in-view is disabled so no query can run outside a service transaction.
- `GenerationType.IDENTITY` prevents JDBC insert batching; moving to a sequence generator with
  an allocation size is a later optimisation because it changes the id-assignment strategy.

## Testing strategy
- TDD for every production change: each test was observed failing before the code that makes
  it pass was written.
- One PostgreSQL container per test run. All integration tests and the Cucumber scenarios share
  a single Spring context; tests therefore never assert on absolute seed counts after writes
  and never truncate tables.

## Delivery
- Every change arrives on a branch, goes through a pull request with a review, and is
  squash-merged once CI is green. Branch rulesets are not available on a private repository of
  the free GitHub plan, so the gate is enforced by process rather than by the platform.
- The container image runs as a non-root user on a JRE, with layered jar extraction for
  efficient caching. Liveness and readiness probes are exposed for Kubernetes; no Docker
  `HEALTHCHECK` is declared because orchestrators ignore it.
- GitHub Actions is the live pipeline; `.gitlab-ci.yml` is an equivalent for GitLab.
