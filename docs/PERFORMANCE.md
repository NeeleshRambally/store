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
| `GET /order`: `@EntityGraph("customer")` on the paged query, then one `left join fetch o.products where o.id in (...)` | 3 statements per request (page + count + products); the to-one join lets the database apply the limit |
| `GET /customer`: page the customers, then one `left join fetch c.orders where c.id in (...)` | 3 statements per request; avoids Hibernate's in-memory pagination (`HHH90003004`) that a fetch-joined collection with `Pageable` would trigger |
| `GET /order/{id}`: `@EntityGraph("customer")` on `findById` | 1 statement |
| `GET /customer?name=`: same two-query shape plus a trigram GIN index on `lower(name)` | 3 statements; the `ILIKE '%...%'` predicate uses the index instead of scanning |
| Index on `order.customer_id` | Lookups of a customer's orders are index scans |
| `spring.jpa.open-in-view: false`; services are `@Transactional(readOnly = true)` and return DTOs | No query can run outside a service transaction; the connection is returned before serialisation starts |
| `default_batch_fetch_size: 100` | Safety net only; no endpoint relies on it |
| HTTP response compression (gzip for JSON above 1 KB) | A 100-item order page shrinks from roughly 10 KB to under 4 KB on the wire |
| HikariCP pool size and timeouts made explicit and configurable | Predictable behaviour under load; fail fast when the database is unreachable |
| Sequence-based ids (`allocationSize = 50`, `pooled-lo`) instead of `GenerationType.IDENTITY`, with `jdbc.batch_size: 50`, `order_inserts`, `order_updates` and PgJDBC `reWriteBatchedInserts=true` | Inserts are deferred to flush and batched: the join-table rows of `POST /order` go from one prepared statement per row to one batched statement, and several entities of one type flushed together become one batched insert per table; `nextval` runs once per 50 ids (see the write path below and ADR 0005) |
| `QueryCountIT` pins the statement count of every read endpoint and of `POST /order` | A reintroduced N+1 fails the build, not production |

## Results

| Request | Statements before | Statements after | Payload before | Payload after (default page) |
|---------|-------------------|------------------|----------------|------------------------------|
| `GET /order` | 11 | 3 | 950 KB | ~2 KB |
| `GET /customer` | 11 | 3 | 503 KB | ~20 KB |
| `GET /order/{id}` | n/a | 1 | n/a | ~100 B |
| `GET /customer?name=` | n/a | 2 or 3 | n/a | bounded by the page |

With a remote database the request latency is now a small constant number of round trips
regardless of data volume, and the amount of data transferred is bounded by the page size.

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
- **Projections instead of entities for list endpoints.** Would save hydration cost, not round
  trips, which are the bottleneck here. Worth doing if CPU on the service becomes the limit.
- **Keyset (cursor) pagination.** `OFFSET` degrades on deep pages; keyset pagination on `id` is
  the next step if clients page deep into the order table.
- **Separate management port.** Operational hardening rather than performance; planned with the
  authentication phase.
