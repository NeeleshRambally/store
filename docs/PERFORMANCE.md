# Performance analysis of the GET endpoints

[Notes for the assessor](../NOTES.md) · [Design](DESIGN.md) · [Assumptions](ASSUMPTIONS.md) · [Performance](PERFORMANCE.md) · [Decision records](adr/README.md)

This document answers [README task 3](../README.md#tasks): why `GET /customer` and `GET /order`
were slow with a remote database, what was measured, what was changed and what the changes
measure. The summary below and the first four sections tell the whole story; each later section
takes one topic in depth, and the last lists what was deliberately not done. The decisions are in
the [decision records](adr/README.md), the settings and the API in [Design](DESIGN.md), and the
judgement calls in [Performance (README task 3)](ASSUMPTIONS.md#performance-readme-task-3).

**In short.** The production database is far from the service, so every statement costs at least
one network round trip (one exchange between service and database). Round trips dominate the time
of a request. The original list endpoints sent 11 statements per call and returned the whole
table. Now every list is paged, and a page costs at most 3 statements however large the tables
grow:

| Request | Original code | Now |
|---------|---------------|-----|
| `GET /order` | 11 statements, all 10,000 orders, 950,440 B | 3 statements, a page of 20 orders, 3,795 B (968 B gzipped) |
| `GET /customer` | 11 statements, all 100 customers with all 10,000 orders, 503,458 B | 3 statements, a page of 20 customers with the newest 20 orders of each, 21,343 B (4,104 B gzipped) |
| Time a list call waits on the network, at 30 ms per round trip | at least 330 ms (11 round trips) | about 120 ms (4 round trips: the 3 statements and the commit; see [Results](#results)) |

What was done, with the section that describes each measure:

- **Paging.** Every list returns 20 rows by default and 100 at most. Cursor paging (`after=<id>`:
  the page starts after the last id seen) costs 2 statements at any depth
  ([Deep pages](#deep-pages)).
- **A fixed query shape.** A page reads its rows, their count and the children of exactly those
  rows, as plain rows instead of entities ([Query shape](#query-shape), [Hydration](#hydration)).
- **Bounded nested lists.** A customer carries its newest 20 orders and a product the ids of its
  newest 20 orders, plus `orderCount`, the total ([Nested collections](#nested-collections)).
- **Indexes** for a customer's orders, the name search, every sortable column and the two order
  filters ([Indexes](#indexes)).
- **Short sessions, a bounded pool and timeouts.** No database session outlives the service call
  ([Sessions, connections and timeouts](#sessions-connections-and-timeouts)).
- **Compression and caching.** Large responses are gzipped, a client can revalidate with an
  `ETag` and get a bodiless `304`, and single orders and products are cached for 60 s
  ([Caching](#caching)).
- **Batched inserts**, from ids allocated in blocks of 50 ([Write path](#write-path)).

`QueryCountIT` pins the statement count of every endpoint, so a change that adds a query to a
read fails the build. [Results](#results) gives the figures for every endpoint.

## Symptom
Users reported that `GET /customer` and `GET /order` were very slow in production. The database
there is not co-located with the service, so every round trip between the two pays a high network
latency. This document uses 30 ms per round trip as its example figure.

## Measurements on the original code
Each list call ran 11 statements and returned the whole table. At 30 ms per round trip that is
at least 330 ms of waiting before any serialisation, and the cost grew with the data.

| Request | Rows returned | Payload | JDBC statements | Why |
|---------|---------------|---------|-----------------|-----|
| `GET /order` | 10,000 | 950,440 B | 11 | 1 query for all orders, then `default_batch_fetch_size: 10` loaded the 100 customers in batches of 10 |
| `GET /customer` | 100 (+10,000 nested orders) | 503,458 B | 11 | 1 query for all customers, then 10 batch loads of the lazy `orders` collections |

Method (2026-10-19):

- Code: the jar of the import commit `d0532d8` (`git show d0532d8:<path>` reproduces any file).
- Database: a throwaway PostgreSQL 16.2 that the jar's own changesets migrated and seeded
  (100 customers, 10,000 orders).
- Statement counts: Hibernate statistics, one session per request
  (`spring.jpa.properties.hibernate.generate_statistics: true` and
  `org.hibernate.engine.internal.StatisticalLoggingSessionEventListener` at INFO).
- Body sizes: `curl -s -o /dev/null -w '%{size_download}'`.

Why it was slow:

- Each statement costs at least one round trip, and round trips dominate with a remote database.
- Both endpoints loaded the whole table on every call, so the cost grew linearly with the data.
- The lazy loading (loading an association when it is first touched) only worked because
  `spring.jpa.open-in-view` was at its default, which keeps a database session open for the
  whole HTTP request.
- The foreign key `order.customer_id` had no index, so any lookup of orders by customer scanned
  the whole order table.

[Defects found in the original code](ASSUMPTIONS.md#defects-found-in-the-original-code-and-what-was-done)
lists these with the other defects of the import, such as the id sequences that made the first
insert fail, and how each was fixed.

### How to read the figures
A measured figure in this document is either pinned by a test or measured by hand.

- **Pinned figures** fail the build when they change. They are the statement and entity counts
  (`getPrepareStatementCount()` and `getEntityLoadCount()` in `QueryCountIT`, prepared statements
  and inserts in `InsertBatchingIT`) and the plan shapes that `MigrationIT` asserts with
  sequential scans disabled. For the filtered pages these are the plans of the statements the
  application sends; elsewhere, of hand-written statements of the same shape.
- **Hand-measured figures** are the payloads, the query timings and the plans the planner picks
  unaided. Each carries its commit, date and method, and nothing re-checks it.
- **A "before" figure** comes from the original code or, for a later measure, from the parent of
  the commit that introduced it, which its section names.
- **Buffers** are the database pages a statement reads, as `EXPLAIN (ANALYZE, BUFFERS)` reports
  them. They are the figure to compare, because times vary with the host and its load.

## Measures taken
Every list endpoint now returns a bounded page in at most 3 statements, and a cursor page in 2. A
read by id costs 1 or 2 statements, or none when it is cached. Each measure below names the
section with its details and measurements.

### Query shape
Each request runs a fixed number of statements, whatever the size of the tables.
[Paging, sorting and filters](DESIGN.md#paging-sorting-and-filters) describes the parameters a
client sends.

- **Paging.** Every list endpoint is paged: 20 rows by default, 100 at most, sorted by `id`
  ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **Three statements per page, no entities.** A page reads its rows with a constructor-expression
  query: JPQL that builds a plain row object instead of an entity. It then runs the count, then
  one child query for exactly the rows on the page ([Hydration](#hydration);
  [ADR 0006](adr/0006-list-projections-and-sort-whitelist.md)).
  - `GET /order` reads `id`, `description` and the customer's `id` and `name` through the
    to-one join. A to-one join does not multiply rows, so the database can apply the limit. The
    child query reads the products of those orders (`where o.id in (...)`).
  - `GET /customer` reads the orders of the page's customers in the child query. This avoids
    Hibernate's in-memory pagination (`HHH90003004`) of a fetch-joined collection with
    `Pageable`.
  - `GET /products` reads the order ids of the page's products.
- **Bounded nested lists.** A customer carries its newest `NESTED_ORDERS_LIMIT` orders (default
  20) and a product the ids of its newest `NESTED_ORDERS_LIMIT` orders, each with `orderCount`,
  the total. One native window query per page returns them. `GET /order?customerId=` and
  `GET /order?productId=` give the full lists ([Nested collections](#nested-collections);
  [ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Nested collections").
- **Reads by id.** `GET /order/{id}` runs `findById` with an entity graph (a declared set of
  associations loaded with the entity): 1 statement. This path still loads the entity and maps
  it with MapStruct. `GET /products/{id}` and `GET /customer/{id}` read the row and a bounded
  window of its orders: 2 statements (1 for an unknown id) and no entity. The customer is not
  cached in the service, because `orderCount` changes on every `POST /order`.
- **Name search.** `GET /customer?name=` has the same shape. A trigram index on `lower(name)` (a
  GIN index that lists, for every three-character fragment, the rows containing it) serves
  `lower(name) LIKE lower('%...%')`. A full page costs 3 statements, a short first page 2
  ([Substring search](#substring-search)).
- **Order filters.** `GET /order?customerId=` and `GET /order?productId=` run the page, count and
  products queries restricted to one customer or product. Each is served by an index that leads
  with the filtered id and ends with the order id ([Filtered pages](#filtered-pages)).
- **Cursor paging.** Keyset (cursor) pagination pages by the last id seen instead of by page
  number. `after=<id>` works on every list endpoint. It skips the count and seeks into the
  primary key index, or the index of its filter, instead of reading and discarding `OFFSET` rows:
  2 statements at any depth ([Deep pages](#deep-pages), [Filtered pages](#filtered-pages);
  [ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Keyset pagination").
- **Sort whitelist.** `sort` accepts `id` and `description` on orders and products, and `id` and
  `name` on customers. Any other property is a 400 problem detail instead of a 500, including a
  nested path such as `customer.name` that would become an unindexed join
  ([ADR 0006](adr/0006-list-projections-and-sort-whitelist.md)). An `id` tie-break keeps pages 0
  and 1 of a sorted walk from sharing or skipping a row. The
  `sortedPagesHaveDeterministicBoundaries` tests of `OrderApiIT` and `CustomerApiIT` and the
  Cucumber scenario "Sorted pages break ties by id in the same direction" check it
  ([Sorted pages](#sorted-pages)).

### Indexes
Four changesets add the indexes these queries need; PostgreSQL does not index a foreign key by
itself. [Data model](DESIGN.md#data-model) lists every index of the schema, and
[ADR 0009](adr/0009-index-changesets.md) covers building an index on a table in use.

| Index | Changeset | Effect |
|-------|-----------|--------|
| `idx_order_customer_id` on `"order" (customer_id)` | 3 | Lookups of a customer's orders are index scans |
| `idx_customer_name_trgm`, GIN with `gin_trgm_ops` on `lower(name)` | 4 | Serves the `name` search on a large table ([Substring search](#substring-search)) |
| `idx_order_description_id`, `idx_product_description_id`, `idx_customer_name_id`: the whitelisted sort columns with `id` trailing | 7 | A shallow sorted offset page is an index scan instead of a top-N sort of the whole table ([Sorted pages](#sorted-pages)) |
| `idx_order_customer_id_id` on `"order" (customer_id, id)` and `idx_order_product_product_id_order_id` on `order_product (product_id, order_id)`, replacing `idx_order_product_product_id` | 8 | Each filtered query is served by an index that leads with the filtered id and ends with the order id; every measured first page and cursor page in `id` order reads at most 172 buffers ([Filtered pages](#filtered-pages)) |

### Sessions, connections and timeouts
No database session outlives the service call that opened it, and every wait on the database is
bounded. [Configuration](DESIGN.md#configuration) documents each setting.

- **No open-in-view.** `spring.jpa.open-in-view: false`. The services are
  `@Transactional(readOnly = true)` and return DTOs, so no query runs outside a service
  transaction and the connection is returned before serialisation starts.
  `QueryCountIT.noSessionIsHeldOpenForTheView` pins that no `OpenEntityManagerInViewInterceptor`
  exists. `default_batch_fetch_size: 100` is a safety net only; no endpoint relies on it.
- **A bounded pool.** HikariCP keeps at most `DB_POOL_SIZE` connections (default 10), with a
  connection timeout of 5 s and a validation timeout of 2 s. Behaviour under load is
  predictable, and the service fails fast when the database is unreachable
  ([Pool and threads](DESIGN.md#pool-and-threads)).
- **Timeouts.** Every connection has a server-side statement timeout (`DB_STATEMENT_TIMEOUT`,
  10 s) and a socket timeout (`DB_SOCKET_TIMEOUT`, 15 s). Every transaction has a timeout
  (`TX_TIMEOUT`, 15 s), and the driver's cancel request gives up after 3 s
  (`cancelSignalTimeout`). A slow statement or a stalled database therefore holds its connection
  and request thread for a bounded time, then answers a retryable 503.
  - Measured on 2026-10-19: 10.0 s for a statement waiting at the limit, 15.0 to 18.1 s for a
    paused database. The stack was the Compose build of the branch of #80, whose timeout
    settings and exception handler are those of `82f6119`. [Timeouts](DESIGN.md#timeouts) gives
    the method.
  - The timeouts add no statement, so `QueryCountIT` is unchanged.

### Responses, caching and writes
Responses are compressed and can be revalidated, single orders and products are cached, and
inserts are batched.

- **Compression.** gzip for JSON above 1 KB, decided per response. `/order/1` (178 B) and
  `/products/2` (165 B) are sent as is, and an order with 25 of the seeded products (1,098 B) is
  sent as 290 B (2026-10-19, the jar of `173f958`, the seed). The list sizes are under
  [Results](#results).
- **Revalidation.** Every API `GET` carries a weak `ETag` (a hash of the body, which the client
  sends back in `If-None-Match`) and `Cache-Control: no-cache`. A matching `If-None-Match` gets
  `304 Not Modified` without a body, so a re-read of an unchanged resource receives headers only
  ([Caching](#caching); [ADR 0007](adr/0007-caching.md)).
- **Service cache.** A Caffeine cache in front of `GET /order/{id}` and `GET /products/{id}`
  (60 s TTL, 10,000 entries) answers a repeated read by id with no statement and no Hibernate
  session. `products` entries are evicted after a `POST /order` commits ([Caching](#caching)).
- **Batched inserts.** Ids come from sequences in blocks of 50 instead of
  `GenerationType.IDENTITY`, so Hibernate defers inserts to the flush and batches them per table
  ([Write path](#write-path); [ADR 0005](adr/0005-sequence-id-generation.md)).
- **Tests.** `QueryCountIT` pins every count above. `ProductApiIT` walks the cursor to the null
  `nextCursor` and checks that the collected ids cover a snapshot of the product table. So each
  of these fails the build, not production: a reintroduced N+1 (one query for the list, then one
  per row), a page that loads entities again, an empty page that runs its child query, a read by
  id that stops being cached, or a cursor that stops early.

## Results
A list page went from 11 statements and the whole table to 3 statements and a bounded page. At
30 ms per round trip it waits about 120 ms on the network instead of at least 330 ms.

| Request | Statements before | Statements after | Payload before | Payload after, uncompressed / gzip |
|---------|-------------------|------------------|----------------|------------------------------------|
| `GET /order` | 11 | 3 | 950,440 B | 3,795 B / 968 B (default page of 20 orders, 40 products); 18,747 B / 3,060 B with `size=100` |
| `GET /customer` | 11 | 3 | 503,458 B | 21,343 B / 4,104 B (default page of 20 customers, 400 nested orders); 106,253 B / 17,145 B with `size=100` |
| `GET /products` | n/a | 3 | n/a | 3,427 B / 975 B (default page); 16,901 B / 4,529 B with `size=100` |
| `GET /order/{id}`, `GET /products/{id}` (cold) | n/a | 1, 2 | n/a | 178 B (`/order/1`), 165 B (`/products/2`, 20 of its 200 order ids); under the 1 KB compression threshold, so sent as is |
| `GET /order/{id}`, `GET /products/{id}` (same id within 60 s) | n/a | 0, no session | n/a | the same bodies, or 0 B with a matching `If-None-Match` |
| `GET /customer/{id}` | n/a | 2 (1 for an unknown id); every read, there is no service cache | n/a | 1,049 B / 424 B (`/customer/1`, 20 of its 96 orders) |
| `GET /customer?name=` | n/a | 3 for a full page, 2 for a short first page | n/a | 1,115 B / 467 B for `name=onne` (one customer on the seed) |
| `GET /order?after=`, `GET /customer?after=` (with or without `name`), `GET /products?after=` | n/a | 2 | n/a | 3,763 B / 949 B, 21,315 B / 4,088 B, 3,399 B / 956 B (`after=0`, default size) |
| `GET /order?customerId=`, `GET /order?productId=` | n/a | 3 (2 with `after`) | n/a | 3,836 B / 740 B (`customerId=1`, default page); 18,095 B / 2,132 B with `size=100` |

The "after" payloads were measured on 2026-10-19 against the Compose stack
([Running locally](DESIGN.md#running-locally)) built from `main` at `c4bfe77`
(`docker compose up -d --build` on the seeded database, removed afterwards with
`docker compose down -v`). Each row's exact path was fetched with
`curl -s -o /dev/null -w '%{size_download}'`, once as is and once with `Accept-Encoding: gzip`.

**When fewer statements run.** 3 is the full-page cost and the maximum. `QueryCountIT` pins each
case that costs less:

| Case | Statements | Why | Pinned by |
|------|------------|-----|-----------|
| Short first page | 2 | Spring Data skips the count when a page is shorter than `size` and derives the total from the offset and the rows | `QueryCountIT.shortFirstPageSkipsTheCountQuery` (`name=onne&size=5` on the seed) |
| Empty first page | 1 | The services also skip the child query of an empty page | `emptyOrderPageFilteredByCustomerUsesOneStatement` (`/order?customerId=999999`) |
| Empty page beyond the end | 2 | The page and the count, because Spring Data cannot derive that total | `emptyOrderPageBeyondTheEndUsesTwoStatements` (`/order?customerId=1&page=50&size=20`) |
| Cursor page | 2, or 1 when empty | No count | The six cursor tests ([Deep pages](#deep-pages)); `emptyOrderKeysetPageFilteredByCustomerUsesOneStatement` (`customerId=999999&after=0`) for the empty page |

**Bounded bodies.** A response body is bounded by `size` (rows per page, at most 100),
`NESTED_ORDERS_LIMIT` (orders per customer and order ids per product, 1 to 100, default 20) and
the 100 products an order can carry.

**Latency in production.** Each route's latency is in `/actuator/prometheus` on the management
port. `http_server_requests_seconds_bucket` has bounds from 5 ms to 10 s
([Ports, probes and metrics](DESIGN.md#ports-probes-and-metrics)), so a p95 or p99 is a
`histogram_quantile` over those buckets, not an average.

**Where the 120 ms comes from.** A full page makes 4 round trips: its 3 statements and the
commit of its read-only transaction (PgJDBC sends the `BEGIN` with the first statement). One
round trip more is added when HikariCP validates a connection that was idle for more than 500 ms.
Even then the wait is inside the 250 ms bucket, where the original 11 statements waited at least
330 ms.

## Hydration
The list endpoints, `GET /products/{id}` and `GET /customer/{id}` load no entities, so the
session tracks nothing. This saves CPU time and memory allocation in the service, not round
trips. The decision is [ADR 0006](adr/0006-list-projections-and-sort-whitelist.md).

Hydration is Hibernate turning result-set columns into managed entity objects. Every loaded
entity costs an entry in the persistence context, a collection wrapper per association and the
hydration of columns the response does not use. The queries instead select exactly the columns
the DTOs need with JPQL constructor expressions (`select new ...Row(...)`), and the services build
the DTOs from those rows.

| Request | Entities loaded before | Entities loaded after | Statements |
|---------|------------------------|-----------------------|------------|
| `GET /order?size=100` | 263 (100 orders, their 63 customers and the 100 products of those orders) | 0 | 3 → 3 |
| `GET /customer?size=50` | 4,996 (50 customers and their 4,946 orders) | 0 | 3 → 3 |
| `GET /customer?name=a&size=5` | 502 (5 customers and their 497 orders) | 0 | 3 → 3 |
| `GET /products?size=20` | 3,420 (20 products and the 3,400 orders containing them) | 0 | 3 → 3 |
| `GET /products/2` | 201 (the product and its 200 orders, through the entity graph, in 1 statement) | 0 | 1 → 2 |
| `GET /customer/1` | n/a (the endpoint is new) | 0 | 2 |

"After" is pinned by `QueryCountIT` (`getEntityLoadCount()`). "Before" was counted on 2026-10-19
with Hibernate statistics in a fresh Testcontainers database on the seed, on the parent of each
change checked out with `git archive`:

- the four list requests on `8bc8bfc`, the parent of `f2cd542` (#34), which introduced the
  projections;
- `GET /products/2` on `2364f09`, the parent of `ed01d7f` (#43), which moved `GET /products/{id}`
  to the product row and the bounded order ids.

The statement counts of the list requests did not change. No dirty check (Hibernate comparing
each managed entity with its snapshot at flush) is saved either, because the read services were
already `@Transactional(readOnly = true)`, which disables flushing.

`GET /order/{id}` keeps its single-statement entity-graph query and MapStruct mapping, since one
order with its customer and products is cheap. `GET /products/{id}` no longer loads the product
with every order containing it (201 entities for product 2 on the seed). It reads the product
row and the bounded order ids: 2 statements and no entity, cached by id and evicted on order
creation.

### Nested collections
A customer now carries its newest `NESTED_ORDERS_LIMIT` orders (default 20) and a product the
ids of its newest orders, each with `orderCount`, the total. A page's size therefore no longer
grows with the orders per customer or product, and one window query per page keeps the statement
count. [ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Nested collections", records the
decision and the full lists that make the bound safe.

Before, a customer page embedded every order of every customer on it: 1,941 orders and 97,740 B
for the default page of 20 on the seed. A product embedded every order containing it (200 ids
for product 2). The payload grew with the orders per parent although the statement count was
fixed.

The child query is now a native window query, one that ranks rows within groups without
collapsing them. In one statement,
`row_number() over (partition by customer_id order by id desc)` keeps the newest
`NESTED_ORDERS_LIMIT` orders of each customer on the page, and
`count(*) over (partition by customer_id)` supplies `orderCount`. The same shape over
`order_product` serves the products and reads `idx_order_product_product_id_order_id`
(`MigrationIT.productOrderWindowIsServedByTheProductIdOrderIdIndex`).

The bound's effect on the body ("before" measured on `2364f09` as under [Hydration](#hydration),
"after" under [Results](#results)):

| Response | Before | After |
|----------|--------|-------|
| Default customer page | 97,740 B, 1,941 nested orders | 21,343 B, 400 nested orders (20 per customer; `orderCount` still totals 1,941) |
| `GET /products/2` | 1,025 B, 200 order ids | 165 B, 20 order ids, `orderCount` 200 |

**Plans on the seed.** A `size=100` page legitimately scans the whole order table, because the
100 seeded customers own all of it. Unaided, the window statement for all 100 customers is a
`Seq Scan on "order"`. For the 20 customers of the default page it is a
`Bitmap Index Scan on idx_order_customer_id` returning their 1,941 orders. Measured on
2026-10-19 with the jar of `173f958`, and again with the same result on 2026-10-20 with the jar
of `1bd1c00`, which has changeset 8.
`MigrationIT.customerOrderWindowForTheDefaultPageIsServedByTheCustomerIdIndex` pins that bitmap
scan with sequential scans disabled.

Changeset 8's `idx_order_customer_id_id` also leads with `customer_id`, so the planner can pick
either index. For one to five customers it costs the two the same and reads
`idx_order_customer_id_id`. From six customers up to the 100 of a full page it rates
`idx_order_customer_id` (11 pages against 41) as cheaper. Measured on the seed on 2026-10-20
with the jar of `1bd1c00`, sequential scans disabled, each index dropped in turn in a rolled-back
transaction. The result was the same before and after `ANALYZE` or `VACUUM`.

**Known limit.** The window's work is bounded by `size` in parents but not in orders per parent,
because `count(*) over (partition by customer_id)` reads every order of each parent for
`orderCount`. A customer with a million orders costs a million index entries on every page it
appears on, although the response carries twenty of them. On the table of
[1,000,000 orders](#the-production-sized-table-of-1000000-orders) (the jar of `1bd1c00`,
2026-10-20), the first page of products holds the ten best-sellers. The window for that page
reads its 1,010,000 order lines from 3,930 buffers of `idx_order_product_product_id_order_id`.
It sorts them in 457 to 483 ms, spilling to temporary
files under the default 4 MB `work_mem` (the memory a sort may use before it spills). If a
parent's orders ever grow without bound, the remedy is a cached or approximate `orderCount`, not
a different window.

## Deep pages
A cursor page costs the same at any depth, while an offset page costs more the deeper it is. An
offset page (`LIMIT/OFFSET`) reads and discards every row before it, and pays the count query
too. A cursor page (`after=<id>`) seeks into the primary key index and skips the count. At page
499 of the seeded orders, the cursor page took 0.023 to 0.039 ms, against 2.5 to 3.1 ms plus the
count for the offset page.

Method (2026-10-19):

- Code and data: the jar of `173f958` (`main` at the time) on a throwaway PostgreSQL 16.2
  migrated with the seed.
- Statements: the ones the application sends, taken from the server's `auto_explain` log (a
  PostgreSQL module that logs the plan of each statement). They were run as
  `EXPLAIN (ANALYZE, BUFFERS)` of prepared copies, warm.
- Times: the range over 15 runs in two sessions. The database was local, so the absolute times
  only show the ratio; the production database adds a network round trip to each statement.

| Request | Page query | Rows the plan walks | Query time | Statements |
|---------|------------|---------------------|------------|------------|
| `GET /order?page=499&size=20` | `order by o1_0.id offset 9980 rows fetch first 20 rows only` | 10,000 index entries of `order_pkey`, 9,980 of them thrown away, each joined to its customer through a `Memoize` node that answers 9,900 of the 10,000 lookups from its cache | 2.5 to 3.1 ms, plus 0.6 to 1.4 ms for the count, a sequential scan of the order table | 3 |
| `GET /order?after=9980&size=20` | `where o1_0.id>9980 order by o1_0.id fetch first 21 rows only` | 20 index entries of `order_pkey`, hash-joined to the 100-row customer table | 0.023 to 0.039 ms, no count | 2 |

The first page costs the same in both modes; the saving is proportional to the depth. The cursor
is the last id of the page (`nextCursor`), so a client walks the whole table in `ceil(n / size)`
requests of constant cost. `QueryCountIT` pins the 2 statements of all six cursor queries:
orders, customers, the name search, products and the two order filters.
[ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Keyset pagination", explains why the
cursor is the plain id.

## Sorted pages
Sorting by `description` or `name` used to sort the whole table before applying the limit.
Changeset 7's indexes make a shallow sorted page an index scan: 0.024 to 0.067 ms instead of 2.1
to 2.8 ms for the first page of orders. A deep sorted offset page still pays the offset, and an
`id` tie-break makes page boundaries deterministic.

The whitelisted sort properties (`description` on orders and products, `name` on customers) had
no B-tree index, so `GET /order?sort=description,desc` sorted the whole order table before the
limit. Equal descriptions (5,317 distinct among the 10,000 seeded orders) came out in whatever
order the sort produced, so a page boundary could split or repeat a tie. Two changes followed
([ADR 0006](adr/0006-list-projections-and-sort-whitelist.md)):

- **An `id` tie-break.** `SortPolicy` appends `id` in the direction of the last client property
  (`description desc` becomes `description desc, id desc`) unless the client already sorts by
  `id`. An unsorted request keeps the default `id` order. `order by description desc` alone
  already gets the index scan below. A tie-break in the same direction keeps that plan, while a
  mixed `description desc, id asc` produces an `Incremental Sort` (both plans checked on
  2026-10-19 the same way as the table below).
- **Three indexes.** Changeset 7 adds `idx_order_description_id` on `"order" (description, id)`,
  `idx_product_description_id` on `product (description, id)` and `idx_customer_name_id` on
  `customer (name, id)`: 512 kB, 16 kB and 16 kB on the seed (`pg_relation_size`, 2026-10-19).

Plans were measured as under [Deep pages](#deep-pages) (2026-10-19, the jar of `173f958`, the
seed). The "before" row drops changeset 7's three indexes inside a rolled-back transaction. That
gives the schema of `56d87c7`, the parent of `33314f3` (#53) that added them (the two changelogs
differ by changeset 7 alone). The row runs that commit's statement, which had no `id` tie-break.

| Request | Page query | Plan | Query time |
|---------|------------|------|------------|
| `GET /order?sort=description,desc&size=20` before | `order by o1_0.description desc fetch first 20 rows only` | `Limit → Sort (top-N heapsort, 29 kB) → Hash Join → Seq Scan` over 10,000 rows | 2.1 to 2.8 ms |
| the same after | `order by o1_0.description desc,o1_0.id desc fetch first 20 rows only` | `Limit → Nested Loop → Index Scan Backward using idx_order_description_id`, 20 entries, their customers through `Memoize` | 0.024 to 0.067 ms |
| `GET /order?sort=description,desc&page=499&size=20` after, default planner | `... offset 9980 rows fetch first 20 rows only` | `Limit → Sort (quicksort, 1,705 kB) → Hash Join → Seq Scan` (the planner prefers it) | 14 to 34 ms |
| the same with the index forced (`enable_seqscan = off`) | | `Index Scan Backward using idx_order_description_id` walking 10,000 entries, customers through `Memoize` | 3.2 to 6.6 ms |

```
Limit
  ->  Nested Loop
        ->  Index Scan Backward using idx_order_description_id on "order" o1_0
        ->  Memoize
              ->  Index Scan using customer_pkey on customer c1_0
```

`Memoize` caches the customer lookups by `customer_id` within one query, so a page looks up each
distinct customer once (19 lookups for the 20 orders of the first page).

Cursor mode remains the constant-cost walk in `id` order only: `after` with a `sort` whose first
property is not `id` ascending is a 400
([Paging, sorting and filters](DESIGN.md#paging-sorting-and-filters)). For `product` and
`customer` the page selects only indexed columns, so the plans are
`Index Only Scan Backward using idx_product_description_id` and
`Index Only Scan using idx_customer_name_id` (answered from the index without reading the
table).

`MigrationIT` pins the three plans with `SET LOCAL enable_seqscan = off` on the seed
(`sortedOrderPageIsServedByTheDescriptionIndex`, `sortedProductPageIsServedByTheDescriptionIndex`,
`sortedCustomerPageIsServedByTheNameIndex`). Like the trigram pin, each `EXPLAIN`s a hand-written
statement of the shape the JPQL renders to (`order by description desc, id desc` and the other
two), not the SQL Hibernate renders, which is not captured.
`SortPolicyTest.appendsAnIdTieBreakInTheDirectionOfTheLastProperty` holds the tie-break
direction that keeps that shape index-servable. A filtered page sorted by `description` is
measured under [What the filters still cost](#what-the-filters-still-cost); the name search keeps
the trigram index.

#### The plan cache
A deep page's plan also depends on PostgreSQL's plan cache. PgJDBC turns a statement into a named
server-side prepared statement on its fifth execution on a connection (`prepareThreshold` 5).
PostgreSQL plans the first five executions of that prepared statement with their parameter
values (custom plans), and may then keep a generic plan made without them. So from its tenth
execution on a connection a statement can change plan. On one connection (`DB_POOL_SIZE=1`,
`auto_explain` logging every plan), the first nine
`GET /order?sort=description,desc&page=499&size=20` sorted the table in 14 to 26 ms. From the
tenth, the generic plan, which does not know the offset, walked the index in 3.5 to 3.6 ms.

## Substring search
The trigram index is right for a production-sized table (200,000 customers) and irrelevant on the
100-row seed. For words of three or more characters it serves the count, rare words and deep
pages of a common word. A one- or two-character search gets no help from it, because `pg_trgm`
extracts no trigram from such a pattern.

`GET /customer?name=<word>` runs `lower(c.name) like lower(concat('%', :word, '%')) escape '!'`
(`CustomerRepository`), which PostgreSQL plans as `lower(name) ~~ '%word%'`. Changeset 4 creates
`idx_customer_name_trgm`, a GIN index with `gin_trgm_ops` on the same expression. A trigram is a
three-character fragment of the text, and the index holds every fragment of every name.
[Customer name search](ASSUMPTIONS.md#customer-name-search-readme-task-2) explains what the
search matches and why.

Method: `EXPLAIN (ANALYZE, BUFFERS)`, warm, the range over five runs, with the jar of `173f958`
on a throwaway PostgreSQL 16.2, on 2026-10-19. The words `zq`, `xw`, `7`, `a`, `q` and `z` were
measured on 2026-10-20, on the 200,000-row table rebuilt the same way.

### The seed: 100 customers
On the seed the planner never picks the index unaided, because a sequential scan of the table's
one page is cheaper than a GIN lookup.

- Unaided, the predicate for `onne` is a `Seq Scan on customer` that removes 99 rows: 1 buffer,
  0.025 to 0.037 ms. The search's own page statement is the same scan under a sort and a limit
  (0.023 to 0.046 ms).
- With `SET LOCAL enable_seqscan = off` it is a `Bitmap Heap Scan` fed by a
  `Bitmap Index Scan on idx_customer_name_trgm`: 6 buffers, 5 of them in the index, 0.009 to
  0.019 ms.

`MigrationIT.customerNameSearchPredicateIsServedByTheTrigramIndex` pins that forced plan. It
`EXPLAIN`s a hand-written statement of the same shape (`lower(name) LIKE lower(...) ESCAPE '!'`),
so it holds the predicate shape, not the SQL Hibernate renders from the JPQL (which matches
today). Since changeset 7 the pin also drops `idx_customer_name_id` inside its rolled-back
transaction. With sequential scans off and that index present, the planner prefers an
`Index Only Scan using idx_customer_name_id` that filters all 100 entries (cost 14.1) to the
GIN lookup (cost 17.0).

### The production-sized table of 200,000 customers
On 200,000 rows the index serves the count, the pages of a rare word and the deep pages of a
common one. The first pages of a common word walk the primary key until the page is full, and a
one- or two-character word gets no help.

The table was made by this statement, in a database the jar migrated with
`LIQUIBASE_CONTEXTS=prod` (no seed). The two name indexes were dropped before the load and
created again after it as changesets 4 and 7 define them, then `ANALYZE customer` ran.

```sql
INSERT INTO customer (id, name)
SELECT g,
       (ARRAY['Muriel','Lance','Denise','Dianne','Jean','Vicki','Winifred','Robin','Leticia',
              'Natalie','Velma','Verna','Salvatore','Irma','Zachary','Alexander','Hattie',
              'Angelo','Moses','Della'])[((g - 1) / 30) % 20 + 1]
       || ' ' ||
       (ARRAY['Stiedemann','Harris','Lemke','Daniel','Kutch','Morissette','Steuber','MacGyver',
              'Oberbrunner','Mosciski','Beahan','Feil','Stoltenberg','Adams','Mayer','Quigley',
              'Pfannerstill','Johnson','Rutherford','Hoeger','Murray','Stark','Donnelly','Kling',
              'Ritchie','Gutmann','Bernier','Schaefer','Wolff','Carter'])[((g - 1) % 30) + 1]
FROM generate_series(1, 200000) AS g;
```

It holds 600 distinct names (20 first names by 30 surnames): 6,666 rows contain `onne`, 13,333
contain `on` and none contains `zqxw` or `zq`. A search runs two statements, which Hibernate
renders as:

```sql
select c1_0.id,c1_0.name from customer c1_0 where lower(c1_0.name) like lower(('%'||?||'%')) escape '!' order by c1_0.id fetch first ? rows only
select count(c1_0.id) from customer c1_0 where lower(c1_0.name) like lower(('%'||?||'%')) escape '!'
```

The page adds `offset ? rows` from page 1 on, and Spring Data skips the count when the first page
is short. They were measured as `EXPLAIN (ANALYZE, BUFFERS) EXECUTE` of prepared copies under
`plan_cache_mode = force_custom_plan`, the plans a connection's first executions get. The
server's `auto_explain` log of the application's own requests against the same table showed the
same plans.

| Statement | Plan | Buffers | Time |
|-----------|------|---------|------|
| Page 0 of `onne` (3.3% of the rows) | `Limit → Index Scan using customer_pkey`, the filter removing 573 rows before 20 match | 8 | 0.17 to 0.19 ms |
| Page 1 of `onne` | the same walk of `customer_pkey`, 1,153 rows removed | 14 | 0.26 to 0.28 ms |
| Page 332 of `onne` (offset 6,640, the last full page) | `Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm` over all 6,666 matches | 1,346 | 2.7 to 2.9 ms |
| Count of `onne` | `Aggregate → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm`, 6,666 rows, none removed by the recheck (1,334 heap blocks and 12 index buffers; the planner estimated 13,919 matches) | 1,346 | 2.6 to 3.1 ms |
| Page 0 of `zqxw` (no match, so no count) | `Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm` | 3 | 0.005 to 0.008 ms |
| Page 1 and count of `zqxw` | the same trigram lookup | 3 | 0.006 to 0.021 ms |
| Page 0 of `on` (two letters) | the walk of `customer_pkey`, 273 rows removed | 5 | 0.07 to 0.09 ms |
| Count of `on` | `Parallel Seq Scan on customer`: `pg_trgm` extracts no trigram from a pattern shorter than three characters, so a trigram scan would have to read the whole index, and for a word this common the planner prefers the parallel sequential scan | 1,334 | 19.5 to 20.0 ms |
| Page 0 of `zq` (two letters, no match, so no count) | `Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm`, but, with no trigram to look up, the index scan reads the whole index (651 of the buffers) and returns all 200,000 rows, every one removed by the recheck | 1,985 | 50.4 to 50.6 ms |
| Page 1 and count of `zq` | the same scan of the whole index on each statement | 1,985 | 48.0 to 51.3 ms |

**Short words.** A one- or two-character search, which the API accepts, gets no help from the
index:

- Its count reads every row: a parallel sequential scan for a common word, or the whole trigram
  index for a word the planner expects to be rare (and on a generic plan for any such word).
- Its page walks the primary key until `size` rows past the offset match for a common word,
  which costs little while the matches are dense. For a rare word it reads the whole trigram
  index and rechecks every row: about 50 ms per statement, more than twice the parallel
  sequential scan of a common word's count.

`xw` and the single character `7`, which match nothing either, take the same plans as `zq`; the
single letters `a`, `q` and `z` (165,971, 6,667 and 9,990 matches) take the common-word ones.
The page limit still bounds what is returned. If such searches turn out to be common, the remedy
is a minimum word length in the API, not another index.

**On a busy connection.** [The plan cache](#the-plan-cache) changes the count's plan. With one
pooled connection:

- The count switched to a generic plan from its tenth execution and kept the trigram index for
  `onne` (3.8 to 6.1 ms in the application's requests).
- The page statement with an offset stayed on custom plans through all sixteen of its
  executions.
- A generic count cannot tell that a two-letter word yields no trigram. For `on` it therefore
  reads the whole trigram index, and the recheck removes 186,667 of the 200,000 rows: 1,985
  buffers, 76 to 78 ms under `force_generic_plan` and 82 and 92 ms in the application's
  requests. That is about four times the parallel sequential scan of the custom plan.

Forcing generic plans everywhere would hurt rare words instead. The generic page plan walks
`customer_pkey` for `zqxw` and filters out all 200,000 rows (1,883 buffers, 67 to 69 ms instead
of under 0.01 ms). `prepareThreshold` and `plan_cache_mode` therefore stay at their defaults,
which let PostgreSQL choose per statement.

## Filtered pages
Changeset 8 gives each order filter an index that leads with the filtered id and ends with the
order id. With it, every measured first page and cursor page in `id` order reads at most 172
buffers. Before it, the planner either read and sorted every order of a large account on every
page, or walked most of the order table for a product.

The filters are `GET /order?customerId=` and `GET /order?productId=`. They page the orders of one
customer or one product, at 3 statements for a full page and 2 for a cursor page
(`QueryCountIT`). [Paging, sorting and filters](DESIGN.md#paging-sorting-and-filters) describes
them.

Method (2026-10-20):

- Jars: `1b05e6b` ("before") and `1bd1c00` ("after"; its changeset 8 and filtered statements are
  unchanged since `c9d56d7` completed the changeset).
- Database: throwaway PostgreSQL 16.2 containers, Docker Desktop on arm64, default settings;
  autovacuum off for the "after" runs, which followed an explicit `VACUUM ANALYZE`.
- Statements: taken from each jar's `org.hibernate.SQL` log and run as
  `EXPLAIN (ANALYZE, BUFFERS) EXECUTE` of prepared copies under
  `plan_cache_mode = force_custom_plan`; times are the range over five runs after one warm-up.
  The "after" statements keep their plans under `force_generic_plan`. The server's
  `auto_explain` log of each jar's own requests showed the same plans.

Reading the tables:

- Where a row gives a first page and the cursor from 0, the time range covers both. A single
  buffer or entry count is then the page's; the cursor's is at most 10 entries and 8 buffers
  higher.
- Buffers were identical across the five runs of each "after" statement (a parallel plan's
  within a few hundred), while times varied by up to 6.8 times under a millisecond and 1.8 times
  above it.

### Before changeset 8
On the seed every filtered statement was cheap, but no page or cursor read only its own rows in
id order. Which plan runs depends on how selective the planner expects the id to be, so a
production-sized table decides.

The customer page was the order page with `where c1_0.id=?`, which the planner carries over to
`o1_0.customer_id`, and its count used `where o1_0.customer_id=?`. The product page joined
`order_product` with `where p1_0.product_id=?`, each cursor added `o1_0.id>?`, and the product
count counted the join. On the seed the two counts used `idx_order_customer_id` and
`idx_order_product_product_id`, and the pages and cursors took other plans:

- customer 1's first page walked `order_pkey` filtering on `customer_id` (2,332 rows removed;
  2,498 for the cursor from 0);
- product 2's page and cursor merge-joined `order_pkey` (943 and 1,001 entries) with an
  index-only scan of `pk_order_product`;
- customer 1's second page, and a customer added with 21 orders holding the highest ids, got a
  bitmap scan of `idx_order_customer_id` and a sort.

### The production-sized table of 1,000,000 orders
On this table a filtered first page or cursor read up to 20,610 buffers before changeset 8 and at
most 172 after it.

The table holds the 200,000 customers of
[the name search](#the-production-sized-table-of-200000-customers), made the same way in a
database the jar migrated with `LIQUIBASE_CONTEXTS=prod`, plus 1,000,000 orders numbered in time
order and 2,001,000 order lines. The statements below loaded them. The order and order-line
indexes were dropped before the load and created again after it, followed by `VACUUM ANALYZE`.

```sql
INSERT INTO "order" (id, description, customer_id)
SELECT id,
       (ARRAY['Handcrafted','Awesome','Handmade','Sleek','Recycled','Rustic','Refined',
              'Ergonomic','Practical','Elegant'])[(id % 10) + 1]
       || ' ' || (ARRAY['Soft','Metal','Frozen','Wooden','Steel','Cotton','Granite',
                        'Plastic','Rubber','Fresh'])[((id / 10) % 10) + 1]
       || ' ' || (ARRAY['Chair','Fish','Salad','Shoes','Table','Lamp','Keyboard','Mouse',
                        'Bike','Hat'])[((id / 100) % 10) + 1],
       customer_id
FROM (SELECT row_number() OVER (ORDER BY t, customer_id) AS id, customer_id
      FROM (SELECT (n + 0.5) * 10 / 9 AS t, k + 1 + k / 1999 AS customer_id
            FROM generate_series(0::bigint, 899999) AS n,
                 LATERAL (SELECT n * 199900 / 900000 AS k) AS s
            UNION ALL
            SELECT (a - 1) * 10000 + (i + 0.5) * (1000000 - (a - 1) * 10000) / 1000, 2000 * a
            FROM generate_series(1::bigint, 100) AS a, generate_series(0, 999) AS i) AS e) AS o;
INSERT INTO product (id, description)
SELECT g,
       (ARRAY['Handcrafted','Rustic','Sleek','Refined','Bespoke','Ergonomic','Practical',
              'Elegant','Durable','Modern'])[((g - 1) % 10) + 1]
       || ' ' || (ARRAY['Chair','Table','Lamp','Keyboard','Mouse','Bike','Shoes','Hat',
                        'Gloves','Watch'])[(((g - 1) / 10) % 10) + 1]
FROM generate_series(1, 1210) AS g;
INSERT INTO order_product (order_id, product_id)
SELECT g, 1 + g % (1 + (g - 1) / 100000) FROM generate_series(1, 1000000) AS g
UNION ALL
SELECT g, 11 + (g - 1) / 1000 FROM generate_series(1, 1000000) AS g
UNION ALL
SELECT g, 1011 + g / 1000 / 5 FROM generate_series(500, 999500, 1000) AS g;
```

The shape of the data:

- 199,900 customers have 4 or 5 orders each, placed together when they sign up (customer 1
  holds orders 1 to 5, customer 199,999 the last ones).
- 100 large accounts, customers 2,000, 4,000 and so on to 200,000, have 1,000 orders each,
  spread from their sign-up to the newest order (customer 2,000's over the whole table, customer
  200,000's over its last 14,175 ids).
- Products 1 to 10 sell best: one is launched every 100,000 orders and each order takes the
  launched ones in turn (product 1 is in 292,899 orders of every age, product 10 in 10,000
  orders above id 900,000).
- Products 11 to 1,010 each sell in one run of 1,000 consecutive orders (product 11 in orders 1
  to 1,000, product 1,010 in the last 1,000).
- Products 1,011 to 1,210 have 5 orders each, 1,000 ids apart (product 1,011 in orders 500 to
  4,500, product 1,210 in 995,500 to 999,500).

Customers and products with few orders at old ids were cheap before and stay so. Customer 1
costs 8, 8 and 4 buffers for its page, cursor and count; the page and the cursor read its five
orders through `idx_order_customer_id` and sort them. Product 1,011 went from 77 buffers to 44.
The rest:

| Request | Before | After |
|---------|--------|-------|
| Customer 2,000 (1,000 orders across the table), first page | `idx_order_customer_id` for all 1,000 orders, then a sort: 1,007 buffers, 0.39 to 0.59 ms | `idx_order_customer_id_id` in id order, 20 entries: 27 buffers, 0.020 to 0.025 ms |
| Customer 2,000, cursor after its median order id | the same 1,000 orders, 500 of them removed by `id > ?`: 1,007 buffers, 0.33 to 0.36 ms | `Index Cond` on both columns, 21 entries: 28 buffers, 0.020 to 0.022 ms |
| Customer 2,000, count | 1,003 buffers, 0.32 to 0.37 ms | index-only scan of `idx_order_customer_id_id`: 8 buffers, 0.098 to 0.120 ms |
| Customer 200,000 (its 1,000 orders among the last 14,175 ids), first page and cursor from 0 | 127 buffers, 0.22 to 0.29 ms | 10 buffers, 0.016 to 0.024 ms |
| Product 1,010 (the last 1,000 ids), first page and cursor from 0 | merge join walking 999,020 entries of `order_pkey`: 11,385 buffers, 86 to 92 ms | the subquery's 20 or 21 entries of `idx_order_product_product_id_order_id`: 164 and 172 buffers, 0.051 to 0.224 ms |
| Product 510 (ids 499,001 to 500,000), the same | merge join walking 499,020 entries: 5,736 buffers, 42 to 62 ms | 164 and 172 buffers, 0.047 to 0.060 ms |
| Product 1,210 (5 orders, ids 995,500 to 999,500), the same | merge join walking 999,501 entries: 11,325 buffers, 85 to 104 ms | 44 buffers, 0.020 to 0.036 ms |
| Product 10 (10,000 orders above id 900,000), the same | merge join walking 900,199 entries of `order_pkey`: 20,610 buffers, 127 to 163 ms; on the statistics sample of a first run, a scan of `pk_order_product` for its second column: 10,511 buffers, 14 to 39 ms | 164 and 172 buffers, 0.047 to 0.134 ms |
| Product 10, page 50 | 14,926 buffers, 80 to 81 ms | 1,020 index entries, of which the subquery skips 1,000: 168 buffers, 0.11 to 0.15 ms |
| Product 10, count | a parallel hash join with a sequential scan of `"order"`: 10,159 buffers, 35 to 37 ms (a nested loop on the first sample: 30,555 buffers, 6.0 to 6.9 ms) | index-only scan of its 10,000 entries: 45 buffers, 1.3 to 1.5 ms |
| Product 1 (292,899 orders of every age), cursor after its median order id | merge join reading its first 146,471 entries of `pk_order_product`: 2,270 buffers, 13 to 15 ms | 172 buffers, 0.047 to 0.074 ms |
| Product 1, count | a parallel hash join: 14,390 buffers, 69 to 78 ms | index-only scan of its 292,899 entries: 1,129 buffers, 38 to 39 ms |

The walk of the primary key assumes the matches are spread over all ids. So it reads most of the
table for a product launched mid-way or lately, and the same statement changed plan with the
statistics sample (product 10 above).

### What changeset 8 changes
Changeset 8 adds `idx_order_customer_id_id` on `"order" (customer_id, id)` and
`idx_order_product_product_id_order_id` on `order_product (product_id, order_id)`, in the
concurrent form of [ADR 0009](adr/0009-index-changesets.md). It also drops
`idx_order_product_product_id` ([Which indexes stay](#which-indexes-stay)).

The customer statements are unchanged. Their count is an index-only scan of the new index. A
large account's first page and cursor read its entries in id order and stop after the page. A
customer with only a few orders has them read through `idx_order_customer_id` and sorted.

For products the index was not enough by itself. With the index alone, the statements of
`1b05e6b` still merge-joined `order_pkey` from the first id for product 10 (900,199 entries,
10,263 buffers, 83 to 91 ms). Nothing tells the planner that a frequent product's orders are
recent. The cursor's `o1_0.id>?` also stayed on `"order"`, because PostgreSQL carries an
equality across a join but not a range. So the cursor after product 1's median order id still
read its first 146,471 index entries (614 buffers, 10.2 to 10.5 ms).

A product page in id order and the product cursor therefore take the page's order ids from the
index first, in a subquery with its own order, limit and offset. Then they read those orders and
their customers. The cursor's statement:

```sql
select o.id as "id", o.description as "description", c.id as "customerId", c.name as "customerName" from "order" o join customer c on c.id = o.customer_id where o.id in (select op.order_id from order_product op where op.product_id = ? and op.order_id > ? order by op.order_id limit ?) order by o.id
```

The page has `order by op.order_id limit ? offset ?` and no range. Both are native statements,
because JPQL reaches `order_product.order_id` only through a join to `"order"`; a product page
sorted by another property keeps the join. The product count,
`select count(po) from Product p join p.orders po where p.id = :productId` in JPQL, is rendered
without `"order"` and reads the product's entries of the index alone.

On the seed the same changes take customer 1's page, cursor and count from 29, 31 and 57 buffers
to 15, 16 and 3. Product 2's pages and cursors now read 103 to 108 buffers, against 55 to 106
before, but no longer walk `order_pkey`, and take at most 0.061 ms. Its count reads 5 buffers
instead of 512.

### What the filters still cost
Offset pages, counts and pages sorted by `description` still read more than the rows they
return. A page in descending id order is cheap on this table, but one case is not measured.

- **An offset page** reads at least its offset. Customer 2,000's pages walk
  `idx_order_customer_id_id` in id order up to page 16 (47 buffers for page 1, 228 for page 10).
  From page 17 on the planner reads all 1,000 orders through `idx_order_customer_id` and sorts
  them: 1,007 buffers for pages 25 and 49, 0.42 to 0.50 ms for page 49. A generic plan walks
  `idx_order_customer_id_id` instead (529 and 1,011 buffers). On the seed, customer 1's pages
  after the first read all 96 of its orders through a bitmap scan of `idx_order_customer_id_id`
  and sort them (58 buffers, against 15 for its first page). Product 10's page 50 is in the
  table above.
- **A count** reads every entry of its customer or product: 38 to 39 ms for product 1.
- **A filtered page sorted by `description`** takes one of two plans. Either it sorts the
  filtered orders (a large account's page: 1,007 buffers, 0.45 to 0.76 ms), or it walks
  `idx_order_description_id` until the page is full. For a product that walk probes
  `idx_order_product_product_id_order_id` once per order walked. Product 11 reads about 163,500
  buffers in 35 to 45 ms (the count varies by a few hundred because parallel workers share the
  walk), and product 10 reads 404,374 buffers in 111 to 122 ms.
  - With the indexes as they were before changeset 8, restored in a rolled-back transaction,
    the same two pages read as many buffers but probe `pk_order_product` instead. That index's
    entries for the orders walked spread over its whole 90 MB. About a fifth of those buffers
    therefore missed the default 128 MB of shared buffers and were read from the operating system
    (115 to 143 ms and 376 to 409 ms).
- **A page in descending id order** read at most 164 buffers for every customer and product
  measured. For a product it keeps the join. That join could walk `order_pkey` backwards from the
  newest order for a frequently ordered product whose orders all hold old ids, the mirror image
  of product 10; this table has no such product.

### Which indexes stay
`idx_order_customer_id` stays, and `idx_order_product_product_id` goes.

Without `idx_order_customer_id` the planner walks `idx_order_description_id` for a large
account's page sorted by description. It rates the heap access of a multi-column index as less
ordered than that of a single-column one.

| Large account's page sorted by description | Without `idx_order_customer_id` | With it |
|--------------------------------------------|---------------------------------|---------|
| Customer 2,000 | 18,542 entries, 18,660 buffers, 8.1 to 13.4 ms | 1,007 buffers |
| Customer 200,000 | 15,995 entries, 16,098 buffers, 6.2 to 11.2 ms | 127 buffers |

Changeset 8 drops `idx_order_product_product_id`, because the new index serves every statement
that used it. The window query behind the products' nested order ids now reads the new index
index-only: 3,930 buffers instead of 30,702 for the twenty products of the first page, which
include the ten best-sellers.

Migration cost on this table ([ADR 0009](adr/0009-index-changesets.md)): the two index builds
took 307 to 447 ms and 584 to 736 ms and the drop 4 to 5 ms, over five runs. They were run
through the service's Liquibase as the `migrate` job runs it
([Start-up and migrations](DESIGN.md#start-up-and-migrations)), with the jar of `1bd1c00` on
2026-10-20, after the three changesets had been reverted by hand. Times vary with the host and
its load.

### What `MigrationIT` pins
`MigrationIT` pins the "after" plans with sequential scans disabled, on the statements the
application sends. Unlike the other pins it does not `EXPLAIN` a hand-written copy. It sends a
filtered request, captures the statements from the `org.hibernate.SQL` logger, binds the
request's values and explains those. A change to the JPQL or to the native statements that loses
the index therefore fails the build. The six tests:

- `customerFilteredPageIsReadInIdOrderFromTheCustomerIdIdIndex`
- `customerFilteredCursorSeeksTheCustomerIdIdIndex`
- `customerFilteredCountUsesTheCustomerIdIdIndex`
- `productFilteredPageTakesItsOrderIdsFromALimitedScanOfTheProductIdOrderIdIndex`
- `productFilteredCursorSeeksTheProductIdOrderIdIndex`
- `productFilteredCountReadsTheProductIdOrderIdIndexWithoutTheOrderTable`

The pins hold before and after the suite's seeded tables are analyzed. The customer cursor is a
bitmap scan of the new index and a sort before, and an index scan in id order after.

## Caching
Caching works in two layers with different savings. HTTP revalidation (a weak `ETag` with
`Cache-Control: no-cache`) saves response bytes, not server work. The service cache (Caffeine, by
id, 60 s) saves the round trips of `GET /order/{id}` and `GET /products/{id}`. `HttpCacheIT`,
`CompressionIT`, `QueryCountIT` and `ProductApiIT` pin both layers.
[ADR 0007](adr/0007-caching.md) records the design and its staleness bounds, and
[Caching](DESIGN.md#caching) the behaviour a client sees.

| Scenario | Before | After | Why |
|----------|--------|-------|-----|
| `GET /order/1` repeated with the `ETag` of the first response in `If-None-Match` | 200 with the 178 B body on every call | `304 Not Modified`, no body, the ETag echoed | `ShallowEtagHeaderFilter` on `/order`, `/order/*`, `/customer`, `/customer/*`, `/products`, `/products/*`; the ETag is weak so Tomcat still compresses, and `Cache-Control: no-cache` makes caches revalidate instead of serving stale bodies |
| `GET /order?size=100` with `Accept-Encoding: gzip` | gzipped, under 4 KB | gzipped, under 4 KB, plus the ETag; a revalidation sends 0 bytes of body | Weak ETags are not excluded from compression |
| `GET /order/1` twice, `GET /products/2` twice | 1 (order) or 2 (product) statements and 1 session per call | second call: 0 statements, 0 sessions (`getPrepareStatementCount()`, `getSessionOpenCount()`) | `@Cacheable` on `findById`; `@EnableCaching(order = HIGHEST_PRECEDENCE)` places the cache advice outside the transaction advice, so a hit never enters the transactional method |
| `GET /products/{id}` after `POST /order` containing that product | n/a | the next read lists the new order (2 statements, then cached again) | `OrderCreatedEvent` → `ProductCacheEvictor`, `AFTER_COMMIT` |

The "Before" column is the same request without the layer in question, at today's sizes and
cold-read costs. Rows 1 and 2 are what every call returned before `1a835f9` (#36) added the ETag
filter; row 2's bound is the one `CompressionIT` asserts. Row 3 is the cold read that
`QueryCountIT` pins (`orderByIdUsesOneStatement`,
`productByIdUsesTwoStatementsAndLoadsNoEntities`), which every call costs with
`CACHE_TYPE=none`.

- **The ETag filter is shallow.** The controller and the service still run on a conditional
  request, so a 304 saves response bytes, not server work. The filter also buffers the whole
  response body in memory to hash it before the first byte is written. It does so on every
  `GET` it covers, whether or not the client sent `If-None-Match`. That is one extra copy of the
  body per request; on the seed the largest is the 106 KB of `GET /customer?size=100`. The copy
  is accepted because the pages are bounded, so the copy is too, and the hash is what makes the
  304 possible.
- **The cache keeps immutable DTOs.** Each record copies its lists when it is built, so every
  hit can share one instance. Entries are keyed by id, bounded to 10,000 entries and 60 s
  (`CACHE_TTL`); `CACHE_TYPE=none` disables the cache.
- **The TTL rises only on evidence.** The hit rate is
  `cache_gets_total{cache="orders"|"products",result="hit"|"miss"}` in `/actuator/prometheus`.
  The TTL should only be raised on that evidence, because a product can be stale for up to the
  TTL in three cases: it is read on another replica; a write bypasses the service; or a read
  that missed the cache before an order's commit stores the old value after the eviction.

## Write path
Ids come from sequences in blocks of 50, so inserts are deferred to the flush and batched.
`POST /order` with three products went from 6 prepared statements to 4 (5 on the request that
allocates the next block), and an order with 100 products costs the same.

The entities use `allocationSize = 50` with the `pooled-lo` optimiser instead of
`GenerationType.IDENTITY`. Each `nextval` gives the first id of a block of 50 that Hibernate then
hands out from memory, so `nextval` runs once per 50 ids. Batching comes from
`jdbc.batch_size: 50`, `order_inserts`, `order_updates` and PgJDBC's
`reWriteBatchedInserts=true`. [ADR 0005](adr/0005-sequence-id-generation.md) records the decision
and how it was rolled out.

A `POST` sent with an `Idempotency-Key` ([ADR 0010](adr/0010-idempotency-keys.md)) runs two
statements more: the claim of the key before the create and, in the create's transaction, the
completion that stores the response. An unkeyed `POST` is unchanged. `QueryCountIT` pins:

- a keyed `POST /customer` or `POST /products` at 3 prepared statements, and a keyed
  `POST /order` with three products at 6, each plus the block allocation;
- a replay at 2 (the claim and the read of the stored row), with no entity loaded;
- a key reused with a different body (a 422) at 2 as well.

`getPrepareStatementCount()` counts prepared statements; PgJDBC pipelines a JDBC batch and, with
`reWriteBatchedInserts=true`, sends it as one multi-row `INSERT`. "After" is pinned by
`QueryCountIT` and `InsertBatchingIT`. "Before" was counted on 2026-10-19 on `6163c42`, the
parent of `8bc8bfc` (#33) that introduced the sequences and the batching, with the same two
scenarios, with Hibernate statistics, in a fresh Testcontainers database.

| Scenario | Prepared statements before | Prepared statements after | Why |
|----------|----------------------------|---------------------------|-----|
| `POST /order` with three products | 6 (customer + products lookups, 1 order insert, 3 join-table inserts) | 4 (5 on the request that allocates the next block of 50 ids) | `jdbc.batch_size` turns the three `order_product` inserts into one batched statement |
| Three orders with three products each in one flush | 12 (3 order inserts + 9 join-table inserts) | 2 (3 on the flush that allocates the block): 1 batched order insert, 1 batched join-table insert | Ids come from the sequence block, so the inserts are deferred and batched per table |

- `QueryCountIT` asserts 4 to 5 and `InsertBatchingIT` 2 to 3. The suite creates well under 50
  orders per JVM, so exactly one request or flush per run pays the `nextval`, and test order
  decides which one. With `jdbc.batch_size: 1` the two counts rise to 7 and 12.
- The API inserts one order per request today, so the second row matters for future bulk paths
  (an import endpoint, a migration job) rather than for the current endpoints. It is also the
  test that proves the sequence switch, because `IDENTITY` would issue three separate order
  inserts regardless of the batch size.
- An order with the maximum of 100 products has 100 join rows, which exceed
  `jdbc.batch_size: 50`. Hibernate therefore executes the one prepared join-row insert as two
  JDBC batches of 50 (`org.hibernate.orm.jdbc.batch` at `TRACE` shows
  `Executing JDBC batch (50 / 50)` twice for `Order.products`; 2026-10-19, the jar of `173f958`
  on the seed). The request stays at 4 prepared statements, 5 when it allocates the block
  (`QueryCountIT.createOrderWithOneHundredProductsUsesFourStatementsPlusTheBlockAllocation`).
  The pin does not see how many batches there are: it still passes with `jdbc.batch_size: 100`,
  one batch, and fails at 103 with `jdbc.batch_size: 1`, which prepares every join row on its
  own.

## Deliberately not done
These were considered and left out on purpose.

- **Caching the list pages.** The by-id reads are cached ([Caching](#caching)); the pages are
  not. A page key would have to include page, size, sort, cursor and the `name`, `customerId`
  and `productId` filters. Every `POST /order`, `POST /customer` or `POST /products` changes the
  totals of at least one list, so the entries would be invalidated as fast as they are filled.
  The lists are already at most 2 or 3 statements, and the HTTP revalidation covers the client
  that polls the same page.
- **A minimum word length for the name search.** A one- or two-character search gets no help
  from the trigram index, but the page limit bounds what it returns. If such searches turn out
  to be common, a minimum length in the API is the remedy, not another index
  ([Substring search](#substring-search)).
- **Different plan cache settings.** `prepareThreshold` and `plan_cache_mode` stay at their
  defaults, which let PostgreSQL choose per statement. Forcing generic plans would make a rare
  word's page walk the primary key ([Substring search](#substring-search)).
- **A cached or approximate `orderCount`.** The window behind the nested lists still reads every
  order of each parent on the page for the total. A cached or approximate count is the remedy if
  a parent's orders ever grow without bound ([Nested collections](#nested-collections)).
