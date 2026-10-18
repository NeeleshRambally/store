# Performance analysis of the GET endpoints

## Symptom
Users reported that `GET /customer` and `GET /order` are very slow in production, where the
database is not co-located with the service and every round trip pays a high network latency.

## Measurements on the original code
Taken against PostgreSQL 16.2 with Hibernate statistics enabled and the seeded data set
(100 customers, 10,000 orders).

| Request | Rows returned | Payload | JDBC statements | Why |
|---------|---------------|---------|-----------------|-----|
| `GET /order` | 10,000 | 950 KB | 11 | 1 query for all orders, then `default_batch_fetch_size: 10` loaded the 100 customers in batches of 10 |
| `GET /customer` | 100 (+10,000 nested orders) | 503 KB | 11 | 1 query for all customers, then 10 batch loads of the lazy `orders` collections |

Round trips are the dominant cost with a remote database: with 30 ms of latency each request
spent at least 330 ms waiting on the network before any serialisation. Both endpoints also
materialised the entire table on every call, so cost grew linearly with data volume. The lazy
loading only worked because `spring.jpa.open-in-view` was left at its default, which keeps a
database session open for the duration of the HTTP request.

Two further findings: the foreign key `order.customer_id` had no index, so any lookup of orders
by customer was a sequential scan of the order table, and the serial sequences had not been
advanced past the seeded ids, so the first insert failed outright.

## Measures taken

| Measure | Effect |
|---------|--------|
| Pagination on both list endpoints (default 20, maximum 100, sorted by `id`) | Payload and work per request are bounded and no longer grow with the table |
| `GET /order`: a constructor-expression query for the page rows (`id`, `description`, customer `id` and `name` through the to-one join), the count, then one row query for the products of exactly those orders (`where o.id in (...)`) | 3 statements per request (page + count + products); the to-one join lets the database apply the limit, and no entity is instantiated (see "Hydration" below) |
| `GET /customer`: the same shape, rows then one row query for the orders of exactly those customers | 3 statements per request; avoids Hibernate's in-memory pagination (`HHH90003004`) that a fetch-joined collection with `Pageable` would trigger |
| `GET /products`: the same shape, rows then one row query for the order ids of exactly those products | 3 statements per request |
| `GET /order/{id}`: entity graph on `findById` | 1 statement; this path still loads the entity and maps it with MapStruct |
| `GET /products/{id}`: the product row plus the bounded order-id window below | 2 statements, no entity loaded |
| Nested collections bounded: a customer carries its newest `NESTED_ORDERS_LIMIT` orders (default 20) and a product the ids of its newest `NESTED_ORDERS_LIMIT` orders, each with `orderCount`; one native window query per page (`row_number() over (partition by customer_id order by id desc)` with `count(*) over (partition by customer_id)`, the same over `order_product`) returns the newest rows and the total of every parent on the page | Statement counts unchanged (3 per page, 2 per cursor page); the orders per customer and the ids per product no longer grow with the table, `orderCount` carries the total and `GET /order?customerId=` / `?productId=` the full list (ADR 0003, "Nested collections") |
| `GET /customer?name=`: same two-query shape plus a trigram GIN index on `lower(name)` | 3 statements; the `ILIKE '%...%'` predicate uses the index instead of scanning |
| `sort` is checked against a per-endpoint list of properties (orders and products: `id`, `description`; customers: `id`, `name`) before any query runs | A nested path such as `customer.name`, which the JPQL queries would otherwise turn into an unindexed join, or an unknown property is a 400 problem detail instead of a 500 (ADR 0006) |
| Index on `order.customer_id` | Lookups of a customer's orders are index scans |
| `spring.jpa.open-in-view: false`; services are `@Transactional(readOnly = true)` and return DTOs | No query can run outside a service transaction; the connection is returned before serialisation starts |
| `default_batch_fetch_size: 100` | Safety net only; no endpoint relies on it |
| HTTP response compression (gzip for JSON above 1 KB) | A 100-item order page shrinks from roughly 10 KB to under 4 KB on the wire |
| HikariCP pool size and timeouts made explicit and configurable | Predictable behaviour under load; fail fast when the database is unreachable |
| Sequence-based ids (`allocationSize = 50`, `pooled-lo`) instead of `GenerationType.IDENTITY`, with `jdbc.batch_size: 50`, `order_inserts`, `order_updates` and PgJDBC `reWriteBatchedInserts=true` | Inserts are deferred to flush and batched: the join-table rows of `POST /order` go from one prepared statement per row to one batched statement, and several entities of one type flushed together become one batched insert per table; `nextval` runs once per 50 ids (see the write path below and ADR 0005) |
| `GET /order?customerId=` and `GET /order?productId=`: the same page, count and products queries with a `where c.id = :customerId` predicate on `idx_order_customer_id` or a `join o.products p where p.id = :productId` on `idx_order_product_product_id` (the join-table primary key makes the join row-preserving) | 3 statements per filtered page and 2 per filtered cursor page, pinned by `QueryCountIT`; the full list of one customer's or one product's orders is reachable without raising the per-request cost |
| Keyset (cursor) pagination: `after=<id>` on every list endpoint runs `where id > :after order by id` with a limit of `size + 1` and skips the count query | 2 statements per cursor page at any depth, and the page query seeks into the primary key index instead of reading and discarding `OFFSET` rows (see "Deep pages" below and ADR 0003) |
| Weak `ETag` and `Cache-Control: no-cache` on every API `GET`; a matching `If-None-Match` is answered with `304 Not Modified` and no body | A client or intermediary that re-reads an unchanged resource receives headers only (see "Caching" below and ADR 0007) |
| Caffeine cache in front of `GET /order/{id}` and `GET /products/{id}` (`@Cacheable`, 60 s TTL, 10,000 entries, cache advice outside the transaction advice); `products` entries are evicted after `POST /order` commits | A repeated by-id read within the TTL runs no statement and opens no Hibernate session |
| `QueryCountIT` pins the statement count of every read endpoint and of `POST /order`, the zero entity loads of every list page and the zero statements and sessions of a repeated by-id read | A reintroduced N+1, a list page that starts loading entities again or a by-id read that stops being cached fails the build, not production |

## Results

| Request | Statements before | Statements after | Payload before | Payload after (default page) |
|---------|-------------------|------------------|----------------|------------------------------|
| `GET /order` | 11 | 3 | 950 KB | ~2 KB |
| `GET /customer` | 11 | 3 | 503 KB | ~20 KB |
| `GET /order/{id}`, `GET /products/{id}` (cold) | n/a | 1, 2 | n/a | ~100 B |
| `GET /order/{id}`, `GET /products/{id}` (same id within 60 s) | n/a | 0, no session | n/a | ~100 B, or 0 B with a matching `If-None-Match` |
| `GET /customer?name=` | n/a | 2 or 3 | n/a | bounded by the page |
| `GET /order?after=`, `GET /customer?after=` (with or without `name`), `GET /products?after=` | n/a | 2 | n/a | bounded by the page |
| `GET /order?customerId=`, `GET /order?productId=` | n/a | 3 (2 with `after`) | n/a | bounded by the page |

With a remote database the request latency is now a small constant number of round trips
regardless of data volume, and the amount of data transferred is bounded by the page size and,
within a page, by the nested-orders limit.

## Hydration
The list endpoints originally loaded entities (the page, their to-one associations and the
fetch-joined children) and mapped them to DTOs. Every loaded entity costs a persistence-context
entry, a collection wrapper per association and the hydration of columns the response does not
use. The list endpoints now select exactly the columns the DTOs need with JPQL constructor
expressions (`select new ...Row(...)`) and build the DTOs from those rows, so nothing is tracked
by the session. Measured with `getEntityLoadCount()` (`QueryCountIT`) on the seeded data set;
the statement counts are unchanged.

| Request | Entities loaded before | Entities loaded after | Statements |
|---------|------------------------|-----------------------|------------|
| `GET /order?size=100` | 263 (100 orders, their customers, the products of those orders) | 0 | 3 → 3 |
| `GET /customer?size=50` | 4,999 (50 customers and their 4,949 orders) | 0 | 3 → 3 |
| `GET /customer?name=a&size=5` | 502 | 0 | 3 → 3 |
| `GET /products?size=20` | 3,422 (20 products and the 3,402 orders containing them) | 0 | 3 → 3 |
| `GET /products/2` | 203 (the product and the orders containing it, through the entity graph) | 0 | 1 → 2 |

This saves CPU and allocation on the service, not round trips. It does not save a dirty check:
the read services were already `@Transactional(readOnly = true)`, which disables flushing.
`GET /order/{id}` keeps its single-statement entity-graph query and MapStruct mapping; one order
with its customer and its products is cheap. `GET /products/{id}` no longer loads the product
with every order containing it (203 entities for product 2 on the seed): it reads the product
row and the bounded order ids, 2 statements and no entity (`QueryCountIT`), and is cached by id
and evicted on order creation as before.

The nested collections are bounded by the same two child queries. A customer page used to embed
every order of every customer on the page (1,941 orders for the default page of 20 on the seed,
about 98 KB) and a product every order containing it (about 200 ids each), so the payload grew
with the orders per customer and per product even though the statement count was fixed. The
child query is now a native window query: `row_number() over (partition by customer_id order by
id desc)` keeps the newest `NESTED_ORDERS_LIMIT` orders of each customer on the page and
`count(*) over (partition by customer_id)` supplies `orderCount`, in one statement over
`idx_order_customer_id`; the same shape over `order_product` serves the products. What is bounded
now: rows per page (`size`, at most 100), orders per customer and order ids per product
(`NESTED_ORDERS_LIMIT`, 1 to 100, default 20) and products per order (at most 100 at creation).
The full lists are `GET /order?customerId=` and `GET /order?productId=`, at the usual 3 or 2
statements per page. The payload figures above and in "Results" pre-date this bound and are
re-measured in a later change.

## Deep pages
`LIMIT/OFFSET` makes the database read and discard every row before the requested page, so the
cost of an offset page grows with its depth, and each page also pays the count query. With
`after=<id>` the page query seeks into the primary key index and the count is skipped. Plans
measured with `EXPLAIN (ANALYZE)` on PostgreSQL 16.2 with the seeded 10,000 orders (local
database, warm cache; the absolute times only show the ratio, the production database adds a
network round trip to each statement):

| Request | Page query | Rows the plan walks | Query time | Statements |
|---------|------------|---------------------|------------|------------|
| `GET /order?page=499&size=20` | `order by id offset 9980 rows fetch first 20 rows only` | 10,000 index entries of `order_pkey` and 10,000 customer lookups, 9,980 of them thrown away | 6.4 ms, plus 0.9 ms for `select count(*)` | 3 |
| `GET /order?after=9980&size=20` | `where id > 9980 order by id fetch first 21 rows only` | 20 index entries and 20 customer lookups | 0.07 to 0.09 ms, no count | 2 |

The first page costs the same in both modes; the saving is proportional to the depth. The
cursor is the last id of the page (`nextCursor`), so a client walks the whole table in
`ceil(n / size)` requests of constant cost, and `QueryCountIT` pins the 2 statements for all
four cursor queries.

## Caching
Two layers, both measured in the integration tests (`HttpCacheIT`, `CompressionIT`,
`QueryCountIT`, `ProductApiIT`); the design and its staleness bounds are in ADR 0007.

| Scenario | Before | After | Why |
|----------|--------|-------|-----|
| `GET /order/1` repeated with the `ETag` of the first response in `If-None-Match` | 200 with the ~100 B body on every call | `304 Not Modified`, no body, the ETag echoed | `ShallowEtagHeaderFilter` on `/order`, `/order/*`, `/customer`, `/customer/*`, `/products`, `/products/*`; the ETag is weak so Tomcat still compresses, and `Cache-Control: no-cache` makes caches revalidate instead of serving stale bodies |
| `GET /order?size=100` with `Accept-Encoding: gzip` | gzipped, under 4 KB | gzipped, under 4 KB, plus the ETag; a revalidation sends 0 bytes of body | Weak ETags are not excluded from compression |
| `GET /order/1` twice, `GET /products/2` twice | 1 (order) or 2 (product) statements and 1 session per call | second call: 0 statements, 0 sessions (`getPrepareStatementCount()`, `getSessionOpenCount()`) | `@Cacheable` on `findById`; `@EnableCaching(order = HIGHEST_PRECEDENCE)` places the cache advice outside the transaction advice, so a hit never enters the transactional method |
| `GET /products/{id}` after `POST /order` containing that product | n/a | the next read lists the new order (1 statement, then cached again) | `OrderCreatedEvent` → `ProductCacheEvictor`, `AFTER_COMMIT` |

The ETag filter is shallow: the controller and the service still run on a conditional request,
so a 304 saves response bytes and client parsing, not server work; the service cache is what
saves the round trip. The cache keeps DTOs, which are immutable, keyed by id, bounded to 10,000
entries and 60 s (`CACHE_TTL`); `CACHE_TYPE=none` disables it. The hit rate is visible as
`cache_gets_total{cache="orders"|"products",result="hit"|"miss"}` in `/actuator/prometheus`;
the TTL should only be raised on that evidence, because a product read on another replica, or
after a write that bypasses the service, can be stale for up to the TTL.

## Write path
`getPrepareStatementCount()` counts prepared statements; PgJDBC pipelines a JDBC batch, and
with `reWriteBatchedInserts=true` it sends a batch as one multi-row `INSERT`. Measured with
Hibernate statistics (`QueryCountIT`, `InsertBatchingIT`):

| Scenario | Prepared statements before | Prepared statements after | Why |
|----------|----------------------------|---------------------------|-----|
| `POST /order` with three products | 6 (customer + products lookups, 1 order insert, 3 join-table inserts) | 4, plus 1 `nextval` once per 50 orders | `jdbc.batch_size` turns the three `order_product` inserts into one batched statement |
| Three orders with three products each in one flush | 12 (3 order inserts + 9 join-table inserts) | 3 (`nextval`, 1 batched order insert, 1 batched join-table insert) | Ids come from the sequence block, so the inserts are deferred and batched per table |

The API inserts one order per request today, so the second row matters for future bulk paths
(an import endpoint, a migration job) rather than for the current endpoints; it is also the
test that proves the sequence switch, because `IDENTITY` would issue three separate order
inserts regardless of the batch size.

## Deliberately not done
- **Caching the list pages.** The by-id reads are cached (above); the pages are not. A page
  key would have to include page, size, sort, cursor and name filter, and every `POST /order`,
  `POST /customer` or `POST /products` changes the totals of at least one list, so the entries
  would be invalidated as fast as they are filled. The lists are already a fixed 2 or 3 statements
  and the HTTP revalidation covers the client that polls the same page.
