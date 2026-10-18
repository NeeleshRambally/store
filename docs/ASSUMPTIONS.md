# Assumptions and judgement calls

The task descriptions were deliberately vague. These are the decisions taken and the reasoning,
so they can be defended or revisited. How the service is run, tested and delivered is described
in `docs/DESIGN.md`; the README at the repository root is the assessment brief and is kept as
delivered.

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
- `sort` accepts only the properties listed in `OpenAPI.yaml` for each endpoint (`id` and
  `description` for orders and products, `id` and `name` for customers). Anything else, including
  nested paths such as `customer.name`, is a 400 problem detail with the detail
  `Unknown sort property: <property>`. See ADR 0006.
- The list endpoints also accept `after` for keyset pagination (elements with an id greater than
  `after`, ascending, with a `nextCursor` instead of totals). The cursor is deliberately a plain
  id rather than an opaque token: it is only valid with the default `id` ascending order, which
  is why `after` combined with `page` or another `sort` is a 400 problem detail
  (`after cannot be combined with page or sort`). See ADR 0003.
- `POST /order` takes `customerId` rather than a nested customer object; an unknown customer is
  a 404 and a missing one a 400.
- Every successful `GET` carries a weak `ETag` and `Cache-Control: no-cache`; a request whose
  `If-None-Match` matches is answered with `304 Not Modified` and no body. Caches may store a
  response but must revalidate it. `POST` responses carry no ETag. See ADR 0007.
- Errors are RFC 7807 problem details. Validation failures list the offending fields; nothing
  else about the failure is exposed.
- `/customer` and `/order` stay singular to match the original README; `/products` will be
  plural because the task names it so. The inconsistency is acknowledged rather than silently
  changed.

## Order by id (README task 1)
- `GET /order/{id}` returns the order with its customer in a single statement (entity graph on
  `findById`), or a 404 problem detail. A non-numeric id is a 400.

## Customer name search (README task 2)
- "A substring of one of the words in their name" is read literally: the query must be a single
  word (no whitespace; otherwise 400) and matches case-insensitively anywhere inside the name.
  Because the query contains no whitespace, any match necessarily lies within one word.
- LIKE wildcards in the query (`%`, `_`) are escaped and matched literally.
- The search shares the list endpoint (`GET /customer?name=`) and its paging, sorting and
  order-fetching behaviour, so it costs the same fixed 3 statements per request.
- A trigram GIN index on `lower(name)` (`pg_trgm`) serves the `ILIKE '%...%'` predicate.
  Creating the extension needs a privileged role on first migration; in production the DBA
  installs it once and the changeset is a no-op thereafter.

## Products (README task 4)
- A product has an id and a description. Orders and products are many-to-many through an
  `order_product` join table with a composite primary key and an index on `product_id`, so both
  directions of the relationship are index scans.
- "An order contains 1 or more products" is enforced at creation: `POST /order` requires a
  non-empty `productIds` list and every id must exist (otherwise 404 naming the first missing
  id). Seeded orders are linked to one or two sample products by the seed changeset so the
  invariant also holds for the sample data.
- `GET /products` and `GET /products/{id}` return the product with the ascending ids of the
  orders that contain it, as the task asks. The list is paged like every other list endpoint and
  the order ids are loaded with the same fixed-cost two-query pattern (3 statements per page,
  1 for a single product).
- `GET /order` and `GET /order/{id}` return the products in the order (`id` and `description`),
  loaded with one extra statement per page and none for a single order.
- The path is `/products` (plural) because the task names it so; see the note on `/customer`
  and `/order` above.

## Performance (README task 3)
- The database is remote with high latency, so the metric that matters is round trips per
  request, not rows. Each list endpoint now performs a fixed number of statements (3 for orders,
  3 for customers and 3 for products) regardless of page size, and an integration test pins
  those numbers. In cursor mode (`after`) the count query is skipped and the page query seeks
  to `id > after` instead of discarding `OFFSET` rows, so a cursor page is 2 statements whatever
  its depth (also pinned).
- The list endpoints select their columns into records with JPQL constructor expressions and
  build the DTOs from those rows, so a page loads no entities (`getEntityLoadCount()` is 0,
  also pinned by the integration test). The by-id endpoints and the create paths keep the
  entity-and-mapper route.
- The foreign key `order.customer_id` is indexed. PostgreSQL does not do this automatically.
- Open-in-view is disabled so no query can run outside a service transaction.
- Ids are allocated from the database sequences in blocks of 50 (`pooled-lo` optimiser, see
  ADR 0005), so inserts are deferred to flush and batched (`jdbc.batch_size: 50`, multi-row
  rewrite in the driver). `POST /order` with three products went from 6 to 4 prepared
  statements plus one `nextval` per 50 orders; several entities of one type flushed together
  become one batched insert per table. Ids are therefore no longer gapless: a restart can skip up
  to 49 ids.
- `GET /order/{id}` and `GET /products/{id}` are cached in memory for 60 s (`CACHE_TTL`,
  `CACHE_TYPE=none` to disable): a repeated read runs no statement and opens no session (pinned
  by the integration test). An order never changes after creation, so `orders` entries are never
  evicted; a product's order ids change on `POST /order`, so those `products` entries are
  evicted after the commit on the node that served the write. On another replica, or after a
  write that bypasses the service, a product can be stale for up to the TTL; that bound is the
  reason the default is short. See ADR 0007.

## Testing strategy
- TDD for every production change: each test was observed failing before the code that makes
  it pass was written.
- One PostgreSQL container per test run. All integration tests and the Cucumber scenarios share
  a single Spring context; tests therefore never assert on absolute seed counts after writes
  and never truncate tables.
- The context also shares one cache. A test never assumes a cold or a warm cache for an id it
  did not populate itself: `QueryCountIT` clears every cache before each test so its statement
  counts are the cold case, and the cache-related tests create the product or order they read.

## Delivery
- Every change arrives on a branch, goes through a pull request with a review, and is
  squash-merged once CI is green. Branch rulesets are not available on a private repository of
  the free GitHub plan, so the gate is enforced by process rather than by the platform.
- The container image runs as a non-root user on a JRE, with layered jar extraction for
  efficient caching. Liveness and readiness probes are exposed for Kubernetes on the management
  port (8081), which the ingress does not publish; no Docker `HEALTHCHECK` is declared because
  orchestrators ignore it.
- GitHub Actions is the live pipeline; `.gitlab-ci.yml` is an equivalent for GitLab.
- The image is scanned on every pull request and a critical, fixable vulnerability blocks the
  merge; unfixable findings are reported but do not block, because there is nothing a code
  change can do about them. An SBOM is produced for every build so the deployed dependency set
  is auditable.
- Dependency updates arrive as weekly pull requests through the same gate as any other change.
  Major upgrades are excluded from automation and planned explicitly.
- A scan finding that cannot be fixed on the supported line is handled as a documented,
  time-boxed exception (ADR 0004), never by lowering the gate.

## Things the pipeline should verify before promotion (README note)
Formatting, compilation, unit and slice tests, integration tests against a real PostgreSQL,
acceptance scenarios, line coverage, a successful image build, an image vulnerability scan and
an SBOM. Deployment promotion would additionally need a smoke test of the started container
against its readiness probe, which the Compose setup demonstrates locally.
