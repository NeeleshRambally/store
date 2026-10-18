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
| `GET /order/{id}`, `GET /products/{id}`: entity graph on `findById` | 1 statement; these paths still load entities and map them with MapStruct |
| `GET /customer?name=`: same two-query shape plus a trigram GIN index on `lower(name)` | 3 statements; the `ILIKE '%...%'` predicate uses the index instead of scanning |
| `sort` is checked against a per-endpoint list of properties (orders and products: `id`, `description`; customers: `id`, `name`) before any query runs | A nested path such as `customer.name`, which the JPQL queries would otherwise turn into an unindexed join, or an unknown property is a 400 problem detail instead of a 500 (ADR 0006) |
| Index on `order.customer_id` | Lookups of a customer's orders are index scans |
| `spring.jpa.open-in-view: false`; services are `@Transactional(readOnly = true)` and return DTOs | No query can run outside a service transaction; the connection is returned before serialisation starts |
| `default_batch_fetch_size: 100` | Safety net only; no endpoint relies on it |
| HTTP response compression (gzip for JSON above 1 KB) | A 100-item order page shrinks from roughly 10 KB to under 4 KB on the wire |
| HikariCP pool size and timeouts made explicit and configurable | Predictable behaviour under load; fail fast when the database is unreachable |
| Sequence-based ids (`allocationSize = 50`, `pooled-lo`) instead of `GenerationType.IDENTITY`, with `jdbc.batch_size: 50`, `order_inserts`, `order_updates` and PgJDBC `reWriteBatchedInserts=true` | Inserts are deferred to flush and batched: the join-table rows of `POST /order` go from one prepared statement per row to one batched statement, and several entities of one type flushed together become one batched insert per table; `nextval` runs once per 50 ids (see the write path below and ADR 0005) |
| Keyset (cursor) pagination: `after=<id>` on every list endpoint runs `where id > :after order by id` with a limit of `size + 1` and skips the count query | 2 statements per cursor page at any depth, and the page query seeks into the primary key index instead of reading and discarding `OFFSET` rows (see "Deep pages" below and ADR 0003) |
| `QueryCountIT` pins the statement count of every read endpoint and of `POST /order`, and the zero entity loads of every list page | A reintroduced N+1, or a list page that starts loading entities again, fails the build, not production |

## Results

| Request | Statements before | Statements after | Payload before | Payload after (default page) |
|---------|-------------------|------------------|----------------|------------------------------|
| `GET /order` | 11 | 3 | 950 KB | ~2 KB |
| `GET /customer` | 11 | 3 | 503 KB | ~20 KB |
| `GET /order/{id}` | n/a | 1 | n/a | ~100 B |
| `GET /customer?name=` | n/a | 2 or 3 | n/a | bounded by the page |
| `GET /order?after=`, `GET /customer?after=` (with or without `name`), `GET /products?after=` | n/a | 2 | n/a | bounded by the page |

With a remote database the request latency is now a small constant number of round trips
regardless of data volume, and the amount of data transferred is bounded by the page size.

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

This saves CPU and allocation on the service, not round trips. It does not save a dirty check:
the read services were already `@Transactional(readOnly = true)`, which disables flushing. The
by-id endpoints keep their single-statement entity-graph queries and MapStruct mapping; one
entity with its children is cheap and the mapper is the only place that code lives.

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

## Considered and not done (yet)
- **Second-level or HTTP caching.** Orders and customers are mutable and the read patterns are
  paged scans; caching would add invalidation complexity without a demonstrated hit rate.
- **Separate management port.** Operational hardening rather than performance; planned with the
  authentication phase.
