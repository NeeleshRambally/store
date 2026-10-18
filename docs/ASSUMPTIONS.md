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
- `GET /order` filters by `customerId` or `productId` (each at least 1, otherwise 400 with the
  field named). The filters are filters, not lookups: an id that matches no order, including an
  unknown customer or product, is a 200 with an empty page, not a 404. They work in both paging
  modes under the same `sort` whitelist and cost the same statements as the unfiltered list
  (`idx_order_customer_id` and `idx_order_product_product_id` serve them). The two cannot be
  combined (`customerId and productId cannot be combined`, 400): a client that wants the orders
  of one customer containing one product filters the smaller list itself, which keeps the
  query shapes to the two indexed ones. These endpoints are the uncapped lists behind the
  nested `orders` of a customer and of a product.
- Every successful `GET` carries a weak `ETag` and `Cache-Control: no-cache`; a request whose
  `If-None-Match` matches is answered with `304 Not Modified` and no body. Caches may store a
  response but must revalidate it. `POST` responses carry no ETag. See ADR 0007.
- Errors are RFC 7807 problem details. Validation failures list the offending fields, for a
  request body and for a query parameter alike (`name`, `after`); nothing else about the
  failure is exposed. A whitespace-only `name` or `description` is a validation failure, and the
  specification says so (`pattern: '\S'`) so that a client built from it rejects the same
  value.
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
- A "word" is defined as a whitespace-delimited token, as `\S+` (ASCII whitespace) defines it: a
  hyphenated name such as `Jean-Pierre` is one word, so `n-P` matches it, and a non-ASCII
  whitespace character such as U+00A0 is not a separator, so it is accepted in the query and
  matched literally. The empty query (`?name=`) is a 400, not an unfiltered list.
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
- An order carries at most 100 products (`@Size(max = 100)` on `productIds`, `maxItems: 100`
  in the specification). The bound keeps a single request to one `IN` clause of at most 100
  ids and one insert batch of at most 100 join rows; without it the only limit was the 2 MB
  request body.
- The invariant is enforced for orders created through the API from this release. The seed
  changeset runs only under the `seed` context, so in a production database an order that
  pre-dates changeset 5 has no `order_product` rows and is returned with `products: []`; such
  orders need a one-off data migration (a backfill changeset or an operator script) before the
  invariant can be asserted on historical data.
- `GET /products` and `GET /products/{id}` return the product with the ascending ids of the
  orders that contain it, as the task asks. The list is paged like every other list endpoint and
  the order ids are loaded with the same fixed-cost two-query pattern (3 statements per page,
  1 for a single product). The task's "all products" is delivered through that same paging:
  `size=100` returns the whole seeded catalogue of 100 products in one page, and the cursor walk
  with `after` covers a catalogue of any size.
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
- `OpenAPI.yaml` is the contract and is enforced, not just published: `OpenApiContractIT`
  validates captured request/response pairs of every endpoint against it (response bodies with
  undocumented fields rejected, request parameters checked). Where the specification and the
  implementation disagreed the specification was corrected: `size` above 100 is capped, as
  ADR 0003 states, so the parameter schema no longer declares a `maximum` that a client built
  from the specification would enforce before the request is sent; and the request schemas
  declare `minLength: 1` for `name` and `description`, because the server rejects blank values
  with 400 and the contract check requires the specification to reject the same request.
- The `customerId` and `productId` filters of `GET /order` are contract-checked with a real
  200 for each (a customer-filtered page and a product-filtered cursor page), the 400 of a
  `customerId` of 0 (the specification's `minimum: 1` rejects the same request) and the 400 of
  the two combined, whose body equals the documented `combinedFilters` example.
- Later contract corrections, each driven by a contract case that failed first: the two by-id
  paths declare the 400 of a non-numeric id; every `POST` declares 409 and every operation 500
  (reusable problem-detail responses, no `default`, which would hide an undeclared status from
  the validator); `Order`, `Customer` and `Product` declare their required properties so a
  dropped `customer`, `products` or `orders` fails the check; the request strings carry
  `pattern: '\S'` next to `minLength: 1` so a whitespace-only value is rejected by the
  specification as it is by the server; and `productIds` declares `maxItems: 100`.

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
acceptance scenarios, the OpenAPI contract check, line coverage, a successful image build, an
image vulnerability scan, an SBOM and a smoke test of the started container. The smoke test runs on every build: the image
that was just built is started with Compose against a fresh PostgreSQL and must become ready
(`/actuator/health/readiness` reports `UP` within 120 seconds) and answer the API on 8080
(`GET /order/1`, `POST /customer`, `GET /products/2`, a cursor page) with the actuator on 8081
only. The gate is worded precisely: a failing smoke test fails the `docker` job (the `smoke` job
on GitLab), the merge procedure requires both jobs green and a review, and the platform cannot
enforce required checks on a private free-plan repository, so the procedure is the gate.
