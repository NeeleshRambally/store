# Assumptions and judgement calls

The task descriptions were deliberately vague. These are the decisions taken and the reasoning,
so they can be defended or revisited. How the service is run, tested and delivered is described
in `docs/DESIGN.md`; the README at the repository root is the assessment brief and is kept as
delivered.

## Production posture
- The project is treated as a production service: every change ships with tests at the unit,
  integration (real PostgreSQL via Testcontainers) and acceptance (Cucumber) level, and CI fails
  on any failing test, formatting violation or coverage below 80% of lines, measured outside the
  whole `mapper` package (the generated MapStruct implementations and the hand-written
  `ProductMapper.orderIds`, which is tested but not counted) and the launcher class
  (`docs/DESIGN.md`, Testing).
- The service is assumed to run behind an authenticating gateway; see ADR 0002 for why
  authentication is deferred and what is shipped in the meantime.
- Configuration fails closed. The base profile has no default for `DB_USERNAME` or
  `DB_PASSWORD` and applies only the context-free Liquibase changesets (`LIQUIBASE_CONTEXTS`
  defaults to `prod`), so an environment that forgets a variable gets no sample credentials
  and no sample data. The failure is at the first database connection, not at start-up
  parsing: Spring Boot binds an unresolved placeholder literally, Liquibase's first connection
  is refused (`password authentication failed for user "${DB_USERNAME}"`, or `Connection
  refused` when nothing answers) and the process exits before serving a request;
  `FailClosedStartupIT` observes exactly that against a real database. The original
  code hard-coded `admin:admin`; an earlier revision kept the pair as the fallback, which is
  the same defect with an opt-out. The README path (`admin:admin`, port 5433, sample data) lives
  in the `local` profile, which `./gradlew bootRun` selects as the default profile and which the
  bootJar, and so the image, does not contain, so `SPRING_PROFILES_ACTIVE=local` cannot bring
  the sample credentials or the seed into a deployment; the tests take their credentials from
  Testcontainers and `compose.yaml` sets every variable explicitly.
- Sample data is loaded only when the `seed` Liquibase context is active: under the `local`
  profile, in the tests and in the `migrate` job of `compose.yaml`. The seed changesets are
  the only ones with a context, so `prod` (or any non-blank value other than `seed`) applies
  the schema and nothing else; a blank value is rejected at start-up
  (`LiquibaseContextsGuard`, `FailClosedConfigurationTest`, `FailClosedStartupIT`) because an
  empty context set would apply every changeset, seed included.

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
  a 404 and a missing one a 400. A product id listed twice is a 400 naming `productIds`
  (`uniqueItems: true` in the specification, `@UniqueElements` on the request): the earlier
  behaviour collapsed the duplicates and answered 201, which hid a client bug instead of
  surfacing it.
- Every `POST` answers 201 with a `Location` header: the relative path of the created resource
  (`/order/{id}`, `/products/{id}`, `/customer/{id}`). It is relative rather than absolute
  because the service is assumed to sit behind a gateway that may rewrite the host, and
  RFC 9110 (section 10.2.2) allows a relative reference. The header is declared as required on
  the three 201 responses and enforced by the contract check.
- `GET /customer/{id}` exists although the README's list of tasks does not ask for it. It is a
  deliberate addition for REST completeness: once `POST /customer` carries a `Location`, a
  customer would be the only resource whose `Location` could not be dereferenced. The response
  is the same `Customer` object as the list items (the newest `NESTED_ORDERS_LIMIT` orders and
  `orderCount`), it costs 2 statements like `GET /products/{id}` (1 on a miss) and carries the
  same weak ETag, and it is deliberately not cached in the service because `orderCount` changes
  on every `POST /order` and nothing keys customers for eviction.
- `GET /order` filters by `customerId` or `productId` (each at least 1, otherwise 400 with the
  field named). The filters are filters, not lookups: an id that matches no order, including an
  unknown customer or product, is a 200 with an empty page, not a 404. They work in both paging
  modes under the same `sort` whitelist and cost the same statements as the unfiltered list
  (`idx_order_customer_id` and `idx_order_product_product_id` serve them). The two cannot be
  combined (`customerId and productId cannot be combined`, 400): a client that wants the orders
  of one customer containing one product filters the smaller list itself, which keeps the
  query shapes to the two indexed ones. These endpoints are the uncapped lists behind the
  nested `orders` of a customer and of a product.
- The nested `orders` of a customer and of a product are bounded: the newest
  `NESTED_ORDERS_LIMIT` (20 by default, 1 to 100) by highest id, returned in ascending id order,
  next to `orderCount`, the total. The README's "0 or more orders" and the task's "list of the
  order IDs" are delivered in bounded form: the newest `NESTED_ORDERS_LIMIT` ids (raisable to
  100) plus `orderCount`, which deviates from the literal wording for performance reasons; the
  complete list is `GET /order?productId={id}` (and `GET /order?customerId={id}` for a
  customer), one request away. Originally the DTOs carried every order; see ADR 0003, "Nested
  collections", for why that could not stay.
- Every successful `GET` carries a weak `ETag` and `Cache-Control: no-cache`; a request whose
  `If-None-Match` matches is answered with `304 Not Modified` and no body. Caches may store a
  response but must revalidate it. `POST` responses carry no ETag. See ADR 0007.
- Every error handled by Spring MVC on the API connector is an RFC 7807 problem detail; a
  request that never reaches the `DispatcherServlet`, and a 404 on the management connector,
  get Boot's default error body instead. Validation failures list the offending fields, for a
  request body and for a query parameter alike (`name`, `after`); nothing else about the
  failure is exposed. A whitespace-only `name` or `description` is a validation failure, and the
  specification says so (`pattern: '\S'`) so that a client built from it rejects the same
  value.
- The three `POST`s are not idempotent: each inserts unconditionally, there are no unique
  constraints, and no `Idempotency-Key` header is supported, so a gateway or client that
  retries after a timeout (or after the 503 of a lost database, which does not say whether the
  write was applied) creates a duplicate. That is recorded as a deferral, not an oversight: no
  retrying client is in scope, a correct implementation needs a key store with a TTL and the
  original response to replay, and it is the next API change after authentication (ADR 0002).
- `/customer` and `/order` stay singular to match the original README; `/products` will be
  plural because the task names it so. The inconsistency is acknowledged rather than silently
  changed.

## Order by id (README task 1)
- `GET /order/{id}` returns the order with its customer and its products (ascending by id) in a
  single statement (entity graph on `findById`), cached for `CACHE_TTL` since an order never
  changes; or a 404 problem detail (`Order with id 999999 was not found`, the `unknownOrder`
  example of the contract). A non-numeric id is a 400.

## Customer name search (README task 2)
- "A substring of one of the words in their name" is read literally: the query must be a single
  word (no whitespace; otherwise 400) and matches case-insensitively anywhere inside the name.
  Because the query contains no whitespace, any match necessarily lies within one word.
- A "word" is defined as a whitespace-delimited token, as Java's `\S+` (ASCII whitespace)
  defines it: a hyphenated name such as `Jean-Pierre` is one word, so `n-P` matches it, and a
  non-ASCII whitespace character such as U+00A0 is not a separator, so it is accepted in the
  query and matched literally. The specification spells the same class out as
  `^[^ \t\r\n\f\u000B]+$` rather than `^\S+$`, because the ECMA-262 dialect the OpenAPI
  pattern is written in counts a no-break space as whitespace and a client built from the old
  pattern would have rejected a query the server accepts; a contract case sends `a\u00a0b` and
  both sides accept it. The empty query (`?name=`) is a 400, not an unfiltered list.
- LIKE wildcards in the query (`%`, `_`) are escaped and matched literally. `CustomerApiIT` pins
  the word-boundary and wildcard claims against PostgreSQL rather than against a mock: `n-P`
  and `O'N` find `Jean-Pierre O'Neil`, `%` finds `Hundred%Percent`, and `r_S` finds
  `Under_Score` but not the decoy `Under Score`, which an unescaped `_` would match.
- The search shares the list endpoint (`GET /customer?name=`) and its paging, sorting and
  order-fetching behaviour, so a full page costs the same 3 statements as the unfiltered list
  (page, count, orders of the page), 2 when the first page is shorter than `size` because
  Spring Data skips the count, and 2 in cursor mode; `QueryCountIT` pins all three.
- The predicate is `lower(name) LIKE lower('%...%')` (wildcards in the word escaped) and a
  trigram GIN index on `lower(name)` (`pg_trgm`) makes it indexable: on the 100-row seed the
  planner still prefers a sequential scan, on 200,000 rows it uses the index unaided, and a
  pattern shorter than three characters always falls back to a scan because `pg_trgm` extracts
  no trigrams from it (`docs/PERFORMANCE.md`, "Substring search", has the plans; `MigrationIT`
  pins that the predicate shape is served by the index). Creating the extension needs a
  privileged role on first migration, which the Compose `migrate` job has (`admin`); in
  production the DBA installs it once and the changeset is a no-op thereafter (ADR 0008
  records the migration job, the runtime role and what each may do).

## Products (README task 4)
- A product has an id and a description. Orders and products are many-to-many through an
  `order_product` join table with a composite primary key and an index on `product_id`, so both
  directions of the relationship are index scans.
- "An order contains 1 or more products" is enforced at creation: `POST /order` requires a
  non-empty `productIds` list without repetition (a repeated id is a 400) and every id must
  exist (otherwise 404 naming the lowest missing id, whatever the request order). Seeded
  orders are linked to one or two sample products by the seed changeset so the invariant also
  holds for the sample data.
- An order carries at most 100 products (`@Size(max = 100)` on `productIds`, `maxItems: 100`
  in the specification). The bound keeps a single request to one `IN` clause of at most 100
  ids and one insert batch of at most 100 join rows.
- Until this release no server-side bound on a JSON body existed, and an earlier revision of
  this document wrongly cited Tomcat's 2 MB default, which applies to form-parameter parsing
  only (`server.tomcat.max-http-form-post-size`); an oversized `description` was parsed into
  memory before `@Size` rejected it. `RequestBodyLimitFilter` now rejects any body over
  `REQUEST_MAX_BODY_BYTES` (64 KiB by default, at least 1 KiB) with a 413 problem detail
  (`Request body exceeds 65536 bytes`) before it is parsed: by the declared `Content-Length`
  when there is one, otherwise by counting the bytes of a chunked body as they are read. The
  largest valid body (a 255-character description and 100 product ids) is about 2 KB, so the
  bound is thirty times the largest request the API accepts. The 413 response is guaranteed
  up to Tomcat's 2 MB swallow limit (`server.tomcat.max-swallow-size`): Tomcat appends
  `Connection: close` and discards the unread remainder; beyond 2 MB it closes the connection
  without reading the rest and the client may see a reset instead of the problem detail.
  `RequestBodyLimitFilterSliceTests`, `RequestBodyLimitIT` (declared and chunked), the contract
  case, the Cucumber scenario and the container smoke test pin it; there is no 411, because a
  chunked body is legal HTTP/1.1 and is what the test client sends.
- The invariant is enforced for orders created through the API from this release. The seed
  changeset runs only under the `seed` context, so in a production database an order that
  pre-dates changeset 5 has no `order_product` rows and is returned with `products: []`; such
  orders need a one-off data migration (a backfill changeset or an operator script) before the
  invariant can be asserted on historical data.
- `GET /products` and `GET /products/{id}` return the product with `orderCount` and the
  ascending ids of the newest `NESTED_ORDERS_LIMIT` orders that contain it (20 by default); the
  complete list is `GET /order?productId=`. The list is paged like every other list endpoint and
  the order ids come from one window query over `order_product` (3 statements per page, 2 for a
  single product: the product row and that window). The task's "all products" is delivered through that same paging:
  `size=100` returns the whole seeded catalogue of 100 products in one page, and the cursor walk
  with `after` covers a catalogue of any size (`ProductApiIT.cursorWalkReturnsTheWholeCatalogue`
  walks `/products?after=` to the null cursor past a product it creates and checks the collected
  ids cover a snapshot of the product table).
- `GET /order` and `GET /order/{id}` return the products in the order (`id` and `description`),
  loaded with one extra statement per page and none for a single order.
- The path is `/products` (plural) because the task names it so; see the note on `/customer`
  and `/order` above.
- The delivered `OpenAPI.yaml` described `GET /products` as a single object rather than a list,
  typed `Product.id` as `string` while every other id is `int64`, let `POST /products` accept an
  `id` and had no `/products/{id}`. That section was treated as a draft and corrected: `/products`
  is a paged list of `Product` with an `int64` id, consistent with `Order` and `Customer`, the
  request body carries no id (clients never supply ids), and `/products/{id}` exists.
  `OpenApiContractIT` enforces the corrected version.

## Performance (README task 3)
- The database is remote with high latency, so the metric that matters is round trips per
  request, not rows. Each list endpoint now performs at most 3 statements regardless of page
  size (the page, its count and the children of exactly that page, for orders, customers and
  products alike), and an integration test pins those numbers. 3 is the full-page cost: the
  count is skipped when the first page is shorter than `size` and the child query when the page
  is empty (a short first page of a name search is 2 and an empty filtered page 1, both pinned).
  In cursor mode (`after`) the
  count query is always skipped and the page query seeks to `id > after` instead of discarding
  `OFFSET` rows, so a cursor page is 2 statements whatever its depth (also pinned), 1 when it is
  empty (`QueryCountIT.emptyOrderKeysetPageFilteredByCustomerUsesOneStatement`,
  `customerId=999999&after=0`).
- The list endpoints select their columns into records with JPQL constructor expressions and
  build the DTOs from those rows, so a page loads no entities (`getEntityLoadCount()` is 0,
  also pinned by the integration test). `GET /order/{id}` and the create paths keep the
  entity-and-mapper route; `GET /products/{id}` reads the product row and the bounded order ids
  (2 statements, no entities, pinned).
- The nested collections are bounded as well as the pages. A customer embeds its newest
  `NESTED_ORDERS_LIMIT` orders (default 20) and a product the ids of its newest
  `NESTED_ORDERS_LIMIT` orders, each with `orderCount`; one native window query
  (`row_number() over (partition by parent order by id desc)` and `count(*) over (partition by
  parent)`) returns the newest rows and the total of every parent on the page in one statement,
  so the statement counts are unchanged. What a response is bounded by is now exact: rows per
  page (`size`, at most 100), orders per customer and order ids per product
  (`NESTED_ORDERS_LIMIT`, 1 to 100, default 20) and products per order (at most 100 at
  creation); nothing in a response grows with the tables, whereas before the bound a customer
  carried about 100 orders and a product about 200 ids on the seed. See ADR 0003, "Nested
  collections", and the measured payloads in `docs/PERFORMANCE.md`.
- The foreign key `order.customer_id` is indexed. PostgreSQL does not do this automatically.
- Every whitelisted sort property is indexed together with `id` (changeset 7), and the service
  appends `id` as a tie-break in the direction of the last sort property, so a sorted offset page
  has a deterministic boundary and a shallow one is an index scan rather than a sort of the whole
  table; deep sorted offset pages still pay the offset, and cursor mode stays `id`-ordered only
  (ADR 0006, `docs/PERFORMANCE.md` "Sorted pages").
- Open-in-view is disabled so no query can run outside a service transaction; `QueryCountIT`
  pins that the context has no `OpenEntityManagerInViewInterceptor`, because nothing lazy-loads
  any more and the suite would otherwise stay green if the property were dropped.
- Ids are allocated from the database sequences in blocks of 50 (`pooled-lo` optimiser, see
  ADR 0005), so inserts are deferred to flush and batched (`jdbc.batch_size: 50`, multi-row
  rewrite in the driver). `POST /order` with three products went from 6 to 4 prepared
  statements plus one `nextval` per 50 orders; several entities of one type flushed together
  become one batched insert per table. Ids are therefore no longer gapless: a restart can skip up
  to 49 ids.
- `GET /order/{id}` and `GET /products/{id}` are cached in memory for 60 s (`CACHE_TTL`,
  `CACHE_TYPE=none` to disable): a repeated read runs no statement and opens no session (pinned
  by the integration test). An order never changes after creation, so `orders` entries are never
  evicted; a product's order ids and `orderCount` change on `POST /order`, so those `products` entries are
  evicted after the commit on the node that served the write. On another replica, or after a
  write that bypasses the service, a product can be stale for up to the TTL; that bound is the
  reason the default is short. See ADR 0007.

## Testing strategy
- TDD for every production change: each test was observed failing before the code that makes
  it pass was written. A test that pins behaviour which already existed (for example
  `FailClosedStartupIT`) is labelled as such in its pull request, with the temporary change
  that made it fail.
- One PostgreSQL container per test run. All integration tests and the Cucumber scenarios share
  a single Spring context; tests therefore never assert on absolute seed counts after writes
  and never truncate tables. A test that writes outside the API rolls its transaction back: the
  sequence checks in `MigrationIT` (a `nextval` is never rolled back, so the returned id still
  shows the sequence position) and the batching check in `InsertBatchingIT`. The rows the suite
  commits are therefore the ones its API requests create.
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
  specification as it is by the server; `productIds` declares `maxItems: 100`; and `Customer`
  and `Product` declare `orderCount` as required, which failed eight contract cases until the
  DTOs carried it. A later round tightened what the specification enforces rather than what it
  describes: the nested `customer`, `products` and `orders` objects declare their required
  properties, `productIds` declares `uniqueItems`, `X-Request-Id` is declared as required on
  every response (the synthetic 409 and 500 cases failed with `Header 'X-Request-Id' is
  expected ... but not found` until they carried it, proof that the validator checks declared
  headers) and `Location` on every 201 (the three `POST` captures failed the same way until the
  controllers sent it). The 503 of a lost database is declared on every operation with
  `Retry-After` required (the synthetic case failed with `Response status 503 not defined for
  path '/order'` until it was, and a second case pins that a 503 without `Retry-After` is
  rejected). The 415 of a `POST` whose `Content-Type` is not `application/json` is declared on
  the three `POST`s the same way (the captured `text/plain` case failed with `Response status
  415 not defined for path '/order'` until it was). A 405 for a method a path does not list
  cannot be declared, since the method does not exist in the contract: `info.description`
  says so and the smoke test checks `DELETE /order/1`; the `servers` entry says that the public
  base URL is set by the gateway (ADR 0002) rather than listing a placeholder.

## Delivery
- Every change arrives on a branch, goes through a pull request with a review, and is
  squash-merged once CI is green. Branch rulesets are not available on a private repository of
  the free GitHub plan, so the gate is enforced by process rather than by the platform.
- The container image runs as a non-root user on a JRE, with layered jar extraction for
  efficient caching. Liveness and readiness probes are exposed for Kubernetes on the management
  port (8081), which the ingress does not publish; readiness includes the database indicator
  (ADR 0008); no Docker `HEALTHCHECK` is declared because orchestrators ignore it.
- Migrations are a job and the runtime role cannot alter the schema. The Compose stack runs the
  image once as `migrate` (no web server, schema owner `admin`, `seed` context) and starts
  `store` only after it has exited 0; `store` connects as `store_app`, a role created by
  `scripts/postgres-init/01-roles.sql` with connect, `SELECT`, `INSERT` and sequence usage on
  everything `admin` creates and nothing else, with `SPRING_LIQUIBASE_ENABLED=false` so no
  Liquibase bean exists at runtime. The smoke test's `compose` mode asserts the job's exit code
  and the grants (a `create table` and an `update` as `store_app` must be denied). The two
  passwords stay inline as README-path development values; an `env_file` split is a deployment
  concern left open. `./gradlew bootRun` keeps in-process Liquibase as `admin` for development
  (ADR 0008).
- Every response carries an `X-Request-Id` (echoed when well-formed, generated otherwise) that
  is also in the MDC of every log line of that request, so a client-quoted id finds the logs;
  the contract declares it as required on every response and the contract check enforces it.
  Distributed tracing is deferred with the same reasoning as authentication: nothing in scope
  consumes a trace, and the header is the hook a tracer attaches to (ADR 0008).
- GitHub Actions is the live pipeline; `.gitlab-ci.yml` runs the same stages for GitLab (with
  `smoke` and `scan` in parallel after `package`, where GitHub runs them one after the other
  in a single job; the gate before a publish is the same) on a local `store:ci` tag that is
  retagged for the registry only in `publish`, but it is provided for portability and has not
  been executed in this project. Both jobs of the live pipeline carry a timeout (20 and 30
  minutes, several times a measured run) so a hung pull or daemon cannot hold a runner for
  hours.
- The supply chain of the pipeline is pinned: every GitHub Action to the commit SHA of its
  release with the version in a comment, the `Dockerfile` base images and the Compose
  `postgres:16.2` image by digest, and the Gradle distribution by `distributionSha256Sum` in
  the wrapper, so the image build stage verifies what it downloads. Dependabot moves each pin
  in its weekly pull request (the `github-actions`, `docker` and `docker-compose` ecosystems),
  which is the review path, because its security alerts do not cover SHA-pinned actions.
- The Compose `store` and `migrate` services run with a read-only root, a tmpfs `/tmp`, no
  capabilities, `no-new-privileges` and a 512 MiB memory limit measured against the service's
  footprint after the smoke run; `postgres` stays unhardened because `initdb` needs root
  capabilities on first start (ADR 0008).
- The image is scanned on every pull request and a critical or high fixable vulnerability
  blocks the merge; findings without a fix are excluded from the gate (`--ignore-unfixed`) and
  are not listed, because there is nothing a code change can do about them; the SBOM is the
  record of what the image contains. The delivered image runs on Spring Boot 3.5.16, the last
  BOM of a line that left open-source support on 2026-06-30: an unsupported line, stated
  plainly. Until the upgrade every fixable finding in a Boot-managed library is closed by a
  per-library version override in `build.gradle` (one `ext` line per library, two today:
  `jackson-bom.version` and `postgresql.version`, pinned by `DependencyOverridesTest`),
  reviewed weekly through the Dependabot pull requests and removed at the upgrade; the two
  Framework 6 advisories without a 6.x fix are time-boxed exceptions (ADR 0004). The upgrade to
  Spring Boot 4.1.x is tracked as
  [issue #57](https://github.com/neeleshambally/store/issues/57), due before the 2027-01-31
  expiry of those exceptions. An SBOM is produced for every build so the deployed dependency
  set is auditable. The data generator's one dependency carries an accepted npm advisory,
  GHSA-qxc2-j82w-r537 on `@faker-js/faker` 7.6.0, through a code path the script never calls;
  `utils/README.md` ("Known advisory") records it with the reasoning and the review date. The
  image published from `main` is the one that was scanned and smoke-tested:
  GitHub retags and pushes the loaded `store:ci` image, GitLab pushes the saved `image.tar`.
- Dependency updates arrive as weekly pull requests through the same gate as any other change.
  Gradle majors and the Java base-image major are excluded from automation and planned
  explicitly; GitHub Actions majors (as SHA moves with a rewritten version comment) and the
  other image updates arrive automatically in grouped pull requests because the pipeline
  validates them.
- A scan finding that cannot be fixed on the supported line is handled as a documented,
  time-boxed exception (ADR 0004), never by lowering the gate.

## Things the pipeline should verify before promotion (README note)
Formatting, compilation, unit and slice tests, integration tests against a real PostgreSQL,
acceptance scenarios, the OpenAPI contract check, line coverage, a successful image build, an
image vulnerability scan, an SBOM and a smoke test of the started stack. The smoke test runs on
every build: the image that was just built migrates a fresh PostgreSQL as the `migrate` job,
is started with Compose as the restricted runtime role and must become ready
(`/actuator/health/readiness` reports `UP` with the `db` component within 120 seconds), answer
the API on 8080 (`GET /order/1`, `POST /customer`, `GET /products/2`, a cursor page, a 413 for
an oversized body) with the actuator on 8081 only, and the job's exit code and the runtime
role's grants are checked in the database container. The image that is published from the
default branch is that scanned and smoke-tested image, retagged, never a rebuild. The gate is worded precisely: a failing smoke test fails the
`docker` job (the `smoke` job on GitLab), the merge procedure requires both jobs green and a
review, and the platform cannot enforce required checks on a private free-plan repository, so
the procedure is the gate.

## Defects found in the original code and what was done
The README says bad choices were made deliberately. This is the consolidated list, one line per
defect: where it was in the imported code (`git show 57a5fa1:<path>`, line numbers of that
revision), what was done, and where the reasoning lives.
- Controllers wired to repositories and entities (`CustomerController.java:20-31`,
  `OrderController.java:20-31`): replaced by controller → service interface → repository, with
  request and response records; ADR 0001.
- Client-supplied ids bound straight from the request body (`@RequestBody Customer` and
  `@RequestBody Order` at `CustomerController.java:30`, `OrderController.java:30`; `POST /customer`
  with `{"id": 5}` overwrote row 5) and `CascadeType.ALL` from customer to orders
  (`Customer.java:19`): request records have no id, unknown properties are rejected with 400,
  the cascade is removed and only the order service writes orders; ADR 0001.
- `@Data` on both bidirectional entities (`Customer.java:11`, `Order.java:8`), which generates
  `equals`, `hashCode` and `toString` over the lazy collection: replaced by `@Getter`/`@Setter`,
  id-based equality, a constant hash code and an id-only `toString`; ADR 0001.
- Entity columns nullable while the schema is `NOT NULL` (`Customer.java:17`, `Order.java:15-18`
  against `schema.sql:4,10-11`): `@Column(nullable = false)`, `optional = false` and
  `@JoinColumn(nullable = false)`, so the mapping states what the schema enforces and
  `ddl-auto: validate` checks the two against each other at start-up; a null never reaches the
  entity because the request records reject it first; reasoning here.
- `GenerationType.IDENTITY` (`Customer.java:14`, `Order.java:12`) and the serial sequences left
  at 1 after the explicit-id seed (`data.sql`, `db.changelog-2.yaml`), so the first insert
  failed: changeset 3 moves each sequence past the highest seeded id, and the entities now use
  `SEQUENCE` with block allocation; ADR 0005 and `docs/PERFORMANCE.md`.
- No index on `order.customer_id` (`schema.sql:8-13`): changeset 3 adds
  `idx_order_customer_id`; `docs/PERFORMANCE.md` and ADR 0003.
- Open-in-view left at its default and no transactions (the controllers called the
  repositories directly): `spring.jpa.open-in-view: false` and transactional services that
  return DTOs; ADR 0003.
- `default_batch_fetch_size: 10` (`application.yaml:13`), which turned each list request into 11
  round trips: the list endpoints run a fixed number of statements per page and the property is
  raised to 100 as a safety net only; `docs/PERFORMANCE.md` and ADR 0003.
- Unbounded `findAll()` with no pagination (`CustomerController.java:25`,
  `OrderController.java:25`): paged and keyset list endpoints, bounded nested collections;
  ADR 0003.
- No validation and no error handling, so a constraint failure was a 500 with a stack trace:
  Bean Validation on the request records and `GlobalExceptionHandler` mapping everything to
  RFC 7807 problem details; ADR 0001 and "API contract" above.
- Hard-coded `admin:admin` credentials (`application.yaml:4-5`) in the base profile, beside
  `format_sql` and `use_sql_comments` (`application.yaml:11-12`), which shape SQL but log none:
  nothing set `org.hibernate.SQL` to DEBUG, so no statement was ever logged. The base profile
  now has no credential defaults and neither option; the README path lives in the `local`
  profile, which keeps the two options, sets `org.hibernate.SQL` to DEBUG so that `bootRun`
  logs every statement, and is left out of the bootJar; "Production posture" above,
  `docs/DESIGN.md` (Running locally, Configuration) and ADR 0008.
- Seed data loaded in every environment (`db.changelog-2.yaml` without a context): the seed
  changesets carry `context: seed` and `LIQUIBASE_CONTEXTS` defaults to `prod`; "Production
  posture" above.
- Spring Boot 3.4.2 (`build.gradle:3`), dependency-management 1.1.3 (`build.gradle:4`) and
  Gradle 8.11.1 (`gradle-wrapper.properties`) behind their lines: now 3.5.16, 1.1.7 and 8.14.6,
  with Dependabot keeping them current through the pipeline; "Delivery" above, ADR 0004 for the
  two advisories the current line cannot fix.
- An unused Liquibase Gradle plugin (`build.gradle:5`), `lombok-mapstruct-binding` as
  `implementation` instead of `annotationProcessor` (`build.gradle:43`), a duplicate
  `spring-boot-starter-data-jpa` test dependency (`build.gradle:49`), JaCoCo with an HTML report
  only, no gate and an `afterEvaluate` hack (`build.gradle:62-74`): removed or moved, XML report
  and an 80% line-coverage gate on `check` (PR #1); `docs/DESIGN.md` (Testing).
- `OrderMapper.orderToOrderCustomerDTO(Customer)` misnamed for what it maps
  (`OrderMapper.java:18`): renamed `customerToOrderCustomerDTO` (PR #3).
- Wildcard imports (`CustomerController.java:11`, `OrderController.java:11`, `Customer.java:3`,
  `Order.java:3` and both tests): explicit imports under Spotless's import order (PR #3).
- Test hygiene: the misspelled `OrderContollerTests.java`, `@RequiredArgsConstructor` on a test
  class (`OrderContollerTests.java:30`), repositories mocked inside a controller slice
  (`OrderContollerTests.java:39-43`, `CustomerControllerTests.java:33-34`), `@ComponentScan` of
  the mappers into the slice (`OrderContollerTests.java:29`, `CustomerControllerTests.java:24`),
  a stray `;` (`CustomerControllerTests.java:63`) and a `findById` stub for a call the controller
  never made (`OrderContollerTests.java:62`): the class is renamed, the slices mock the service
  interface only, and the stray statement and the dead stub are gone (PRs #1 and #3);
  `docs/DESIGN.md` (Testing).
- Changesets without a rollback (`db.changelog-1.yaml`, `db.changelog-2.yaml`): every changeset
  added since declares one; the two imported `sqlFile` changesets are kept as imported, because
  both are already applied in any existing database and a `DROP TABLE` rollback for the schema
  changeset would be more dangerous than its absence.
- `OpenAPI.yaml`: `descriptioon` (`:52`), `Order.customer` declaring `description` instead of
  `name` (`:153`), bare arrays with no 4xx response (`:29`, `:65`), no `required` anywhere, request
  bodies not marked `required` and carrying an `id` (`:43-53`, `:79-89`, `:123-135`),
  `x-stoplight` noise (`:134`, `:174`) and the license URL ending in `.htm` (`:13`): all corrected,
  and the specification is enforced by `OpenApiContractIT`; "Testing strategy" above and
  `docs/DESIGN.md` (Testing). The products section (`:90-135`) is covered under "Products" above.
- `utils/generateData.js`: unescaped quotes in the generated SQL (`:8`, `:14`; Faker's English
  surnames include `O'Keefe`, so a regenerated file could fail to load), explicit ids (`:8`, `:14`), an unseeded `Math.random()` for the
  customer of each order (`:13`) and the `faker.name` module (`:8`): quotes are escaped, the
  generator is seeded end to end and tested for it in the pipeline, the explicit ids are kept because changeset 3
  aligns the sequences after the load, and `faker.name` is kept because the locked Faker 7.6.0
  has no `faker.person` (the rename arrived in Faker 8, whose upgrade would change every
  generated name); `utils/README.md`.
- The reserved table name `order` (`Order.java:9`, `schema.sql:8`) was kept: renaming it is a
  migration of the table, its sequence, its foreign key and the join table for no functional
  gain, and the quoted identifier is confined to the entity mapping, the native queries, the
  changesets and the SQL the integration tests run; reasoning here.
