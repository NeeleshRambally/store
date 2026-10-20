# Performance analysis of the GET endpoints

## Symptom
Users reported that `GET /customer` and `GET /order` are very slow in production, where the
database is not co-located with the service and every round trip pays a high network latency.

## Measurements on the original code
Taken on the import commit (`57a5fa1`; `git show 57a5fa1:<path>` reproduces any file) against
PostgreSQL 16.2 with the seeded data set (100 customers, 10,000 orders), re-measured on
2026-10-19: the commit's own jar against a throwaway database that its own changesets migrated
and seeded, with Hibernate statistics switched on
(`spring.jpa.properties.hibernate.generate_statistics: true` and
`org.hibernate.engine.internal.StatisticalLoggingSessionEventListener` at INFO, which logs the
JDBC statements of each session, here one per request) and the body size from
`curl -s -o /dev/null -w '%{size_download}'`.

Every figure in this document is either pinned or measured by hand. Pinned, so that a change
which breaks it fails the build: statement and entity counts (`getPrepareStatementCount()` and
`getEntityLoadCount()` in `QueryCountIT`, prepared statements and inserts in
`InsertBatchingIT`) and the plan shapes that `MigrationIT` asserts with sequential scans
disabled (for the filtered pages, of the statements the application sends). Measured by hand,
with the commit, the date and the method beside each: payloads, query timings and the plans the
planner picks unaided; nothing re-checks them. A "before" figure comes from the original code
(this section) or, for a measure introduced later, from the parent of the commit that
introduced it, which its section names.

| Request | Rows returned | Payload | JDBC statements | Why |
|---------|---------------|---------|-----------------|-----|
| `GET /order` | 10,000 | 950,440 B | 11 | 1 query for all orders, then `default_batch_fetch_size: 10` loaded the 100 customers in batches of 10 |
| `GET /customer` | 100 (+10,000 nested orders) | 503,458 B | 11 | 1 query for all customers, then 10 batch loads of the lazy `orders` collections |

Round trips are the dominant cost with a remote database, and each statement costs at least
one: with 30 ms of latency each request spent at least 330 ms waiting on the network before any
serialisation, where a full page now costs 3 statements plus the commit of its read-only
transaction, about 120 ms ("Results" derives it). Both endpoints also materialised the entire
table on every call, so cost grew linearly with data volume. The lazy loading only worked
because `spring.jpa.open-in-view` was left at its default, which keeps a database session open
for the duration of the HTTP request.

Two further findings: the foreign key `order.customer_id` had no index, so any lookup of orders
by customer was a sequential scan of the order table, and the serial sequences had not been
advanced past the seeded ids, so the first insert failed outright.

## Measures taken

| Measure | Effect |
|---------|--------|
| Pagination on both list endpoints (default 20, maximum 100, sorted by `id`) | Rows per request are bounded by `size`; together with the nested-collection bound below, nothing in a response grows with the tables |
| `GET /order`: a constructor-expression query for the page rows (`id`, `description`, customer `id` and `name` through the to-one join), the count, then one row query for the products of exactly those orders (`where o.id in (...)`) | 3 statements for a full page (page + count + products); the to-one join lets the database apply the limit, and no entity is instantiated (see "Hydration" below) |
| `GET /customer`: the same shape, rows then one row query for the orders of exactly those customers | 3 statements for a full page; avoids Hibernate's in-memory pagination (`HHH90003004`) that a fetch-joined collection with `Pageable` would trigger |
| `GET /products`: the same shape, rows then one row query for the order ids of exactly those products | 3 statements for a full page |
| `GET /order/{id}`: entity graph on `findById` | 1 statement; this path still loads the entity and maps it with MapStruct |
| `GET /products/{id}`: the product row plus the bounded order-id window below | 2 statements, no entity loaded |
| `GET /customer/{id}`: the customer row plus the bounded order window below | 2 statements, no entity loaded; 1 on a miss; deliberately not cached in the service because `orderCount` changes on every `POST /order` |
| Nested collections bounded: a customer carries its newest `NESTED_ORDERS_LIMIT` orders (default 20) and a product the ids of its newest `NESTED_ORDERS_LIMIT` orders, each with `orderCount`; one native window query per page (`row_number() over (partition by customer_id order by id desc)` with `count(*) over (partition by customer_id)`, the same over `order_product`) returns the newest rows and the total of every parent on the page | Statement counts unchanged (3 per page, 2 per cursor page); the orders per customer and the ids per product no longer grow with the table, `orderCount` carries the total and `GET /order?customerId=` / `?productId=` the full list (ADR 0003, "Nested collections") |
| `GET /customer?name=`: same two-query shape plus a trigram GIN index on `lower(name)` | 3 statements for a full page, 2 for a short first page; the predicate is `lower(name) LIKE lower('%...%')`, which the index serves on a large table for the count, rare words and deep pages, but cannot narrow when the pattern is shorter than three characters (see "Substring search" below) |
| `sort` is checked against a per-endpoint list of properties (orders and products: `id`, `description`; customers: `id`, `name`) before any query runs, and `id` is appended as a tie-break in the direction of the last property unless the client already sorts by it | A nested path such as `customer.name`, which the JPQL queries would otherwise turn into an unindexed join, or an unknown property is a 400 problem detail instead of a 500 (ADR 0006); equal values no longer leave the page boundary to the planner, so pages 0 and 1 of a sorted walk never share or skip a row (`OrderApiIT.sortedPagesHaveDeterministicBoundaries`, `CustomerApiIT`, the Cucumber scenario) |
| Index on `order.customer_id` | Lookups of a customer's orders are index scans |
| B-tree indexes on the whitelisted sort columns with `id` as the trailing column: `idx_order_description_id`, `idx_product_description_id`, `idx_customer_name_id` (changeset 7) | A shallow sorted offset page is a backward index scan over the entries it returns instead of a top-N sort of the whole table; a deep sorted offset page still pays the offset (see "Sorted pages" below); `MigrationIT` pins the three plans |
| `spring.jpa.open-in-view: false`; services are `@Transactional(readOnly = true)` and return DTOs | No query can run outside a service transaction; the connection is returned before serialisation starts |
| `default_batch_fetch_size: 100` | Safety net only; no endpoint relies on it |
| HTTP response compression (gzip for JSON above 1 KB) | A 100-order page shrinks from 18,747 B to 3,060 B on the wire and the 100-customer page from 106,253 B to 17,145 B (measured below); the seeded by-id bodies of `/order/1` (178 B) and `/products/2` (165 B) stay below the threshold and are sent as is, while `/customer/1`, with 20 nested orders, is just above it and is compressed (1,049 B / 424 B); the threshold applies per response, so an order is compressed once it carries enough products (with 25 of the seeded products `GET /order/{id}` is 1,098 B and is sent as 290 B, measured on 2026-10-19 with the jar of `f0b1595` on the seed) |
| HikariCP pool size configurable (`DB_POOL_SIZE`), connection and validation timeouts explicit (5 s, 2 s) | Predictable behaviour under load; fail fast when the database is unreachable |
| Statement, socket and transaction timeouts on every connection (`DB_STATEMENT_TIMEOUT` 10 s on the server, `DB_SOCKET_TIMEOUT` 15 s, `TX_TIMEOUT` 15 s, `cancelSignalTimeout` 3 s) | A slow statement or a stalled remote database holds its connection and its request thread for a bounded time and then answers a retryable 503: after 10.0 s for a statement still waiting at the limit and after 15.0 to 18.1 s for a paused database, measured by hand on 2026-10-19 on the Compose stack built from the branch of #80, whose timeout settings and exception handler are those of `60529f6` (`docs/DESIGN.md`, Configuration, gives the method); no statement is added, so `QueryCountIT` is unchanged |
| Sequence-based ids (`allocationSize = 50`, `pooled-lo`) instead of `GenerationType.IDENTITY`, with `jdbc.batch_size: 50`, `order_inserts`, `order_updates` and PgJDBC `reWriteBatchedInserts=true` | Inserts are deferred to flush and batched: the join-table rows of `POST /order` go from one prepared statement per row to one batched statement, and several entities of one type flushed together become one batched insert per table; `nextval` runs once per 50 ids (see the write path below and ADR 0005) |
| `GET /order?customerId=` and `GET /order?productId=`: the page, count and products queries restricted to one customer or one product, each served by an index that leads with the filtered id and ends with the order id (changeset 8): the customer's page, cursor and count read `idx_order_customer_id_id` on `"order" (customer_id, id)`; a product's page in `id` order and its cursor take the page's order ids from `idx_order_product_product_id_order_id` on `order_product (product_id, order_id)` in a limited subquery before reading those orders, and its count reads that index alone | 3 statements per filtered page and 2 per filtered cursor page, pinned by `QueryCountIT`; on 1,000,000 orders every filtered first page and cursor page in `id` order that was measured reads at most 172 buffers (an offset page still reads its offset), where the earlier plans walked up to 999,501 entries of `order_pkey` for a product (up to 163 ms) or read all 1,000 orders of a large account on every page (see "Filtered pages" below); `MigrationIT` pins the plans of the statements the application sends |
| Keyset (cursor) pagination: `after=<id>` on every list endpoint runs `where id > :after order by id` with a limit of `size + 1` and skips the count query | 2 statements per cursor page at any depth, and the page query seeks into the primary key index instead of reading and discarding `OFFSET` rows, a filtered cursor into the index of its filter (see "Deep pages" and "Filtered pages" below and ADR 0003) |
| Weak `ETag` and `Cache-Control: no-cache` on every API `GET`; a matching `If-None-Match` is answered with `304 Not Modified` and no body | A client or intermediary that re-reads an unchanged resource receives headers only (see "Caching" below and ADR 0007) |
| Caffeine cache in front of `GET /order/{id}` and `GET /products/{id}` (`@Cacheable`, 60 s TTL, 10,000 entries, cache advice outside the transaction advice); `products` entries are evicted after `POST /order` commits | A repeated by-id read within the TTL runs no statement and opens no Hibernate session |
| `QueryCountIT` pins the statement count of every read endpoint, of an empty filtered page (1 on the first offset page and in cursor mode, 2 beyond the end) and of `POST /order` with three and with 100 products (4, or 5 on the request that allocates the next block of ids), the zero entity loads of every list page and the zero statements and sessions of a repeated by-id read (and the 2 statements of a repeated `GET /customer/{id}`, which is not cached); `ProductApiIT` walks the cursor to the null `nextCursor` and checks the collected ids cover a snapshot of the product table | A reintroduced N+1, a list page that starts loading entities again, an empty page that starts running its child query, a by-id read that stops being cached or a cursor that stops early fails the build, not production |

## Results

| Request | Statements before | Statements after | Payload before | Payload after, uncompressed / gzip |
|---------|-------------------|------------------|----------------|------------------------------------|
| `GET /order` | 11 | 3 | 950,440 B | 3,795 B / 968 B (default page of 20 orders, 40 products); 18,747 B / 3,060 B with `size=100` |
| `GET /customer` | 11 | 3 | 503,458 B | 21,343 B / 4,104 B (default page of 20 customers, 400 nested orders); 106,253 B / 17,145 B with `size=100` |
| `GET /products` | n/a | 3 | n/a | 3,427 B / 975 B (default page); 16,901 B / 4,529 B with `size=100` |
| `GET /order/{id}`, `GET /products/{id}` (cold) | n/a | 1, 2 | n/a | 178 B (`/order/1`), 165 B (`/products/2`, 20 of its 200 order ids); under the 1 KB compression threshold, so sent as is |
| `GET /order/{id}`, `GET /products/{id}` (same id within 60 s) | n/a | 0, no session | n/a | the same bodies, or 0 B with a matching `If-None-Match` |
| `GET /customer/{id}` | n/a | 2 (1 on a miss); every read, there is no service cache | n/a | 1,049 B / 424 B (`/customer/1`, 20 of its 96 orders) |
| `GET /customer?name=` | n/a | 3 for a full page, 2 for a short first page | n/a | 1,115 B / 467 B for `name=onne` (one customer on the seed) |
| `GET /order?after=`, `GET /customer?after=` (with or without `name`), `GET /products?after=` | n/a | 2 | n/a | 3,763 B / 949 B, 21,315 B / 4,088 B, 3,399 B / 956 B (`after=0`, default size) |
| `GET /order?customerId=`, `GET /order?productId=` | n/a | 3 (2 with `after`) | n/a | 3,836 B / 740 B (`customerId=1`, default page); 18,095 B / 2,132 B with `size=100` |

3 is the full-page cost and the maximum: Spring Data skips the count query when a page is
shorter than `size` (the total is then the offset plus the rows on the page), except for an
empty page after the first, whose total it cannot derive, and the services skip the child query
when the page is empty. So a short first page costs 2
(`QueryCountIT.shortFirstPageSkipsTheCountQuery`, `name=onne&size=5` on the seed), an empty
first page 1 (`emptyOrderPageFilteredByCustomerUsesOneStatement`, `/order?customerId=999999`)
and an empty page beyond the end 2, the page and the count
(`emptyOrderPageBeyondTheEndUsesTwoStatements`, `/order?customerId=1&page=50&size=20`); a cursor
page is 2, or 1 when empty (`emptyOrderKeysetPageFilteredByCustomerUsesOneStatement`,
`customerId=999999&after=0`). The statement counts of the "after" column are pinned by
`QueryCountIT`. Every figure in the "after" payload column was measured by hand on 2026-10-19
against the Compose stack built from `main` at `5215fba` (`docker compose up -d --build`, the
seeded database), with `curl -s -o /dev/null -w '%{size_download}'` on the exact path of each
row, once as is and once with `Accept-Encoding: gzip`, and the stack was removed with
`docker compose down -v` afterwards. The "before" columns repeat the measurement on the
original code at the top of this document.

With a remote database the request latency is now a small constant number of round trips
regardless of data volume, and the body of a response is bounded by `size` (rows per page, at
most 100), `NESTED_ORDERS_LIMIT` (orders per customer and order ids per product, 1 to 100,
default 20) and the 100 products an order can carry; nothing in a response grows with the
tables.

In production the latency of each route can be read from `/actuator/prometheus`:
`http_server_requests_seconds_bucket` has bounds from 5 ms to 10 s (`docs/DESIGN.md`,
Operations), so the p95 or p99 of `GET /order` or `GET /customer` is a `histogram_quantile`
over those buckets rather than an average. At 30 ms per round trip a full page now waits about
120 ms on the network: its 3 statements plus the commit of its read-only transaction (PgJDBC
sends the `BEGIN` together with the first statement), and one round trip more when HikariCP
validates a connection that was idle for more than 500 ms. That is inside the 250 ms bucket,
where the original 11 statements waited at least 330 ms.

## Hydration
The list endpoints originally loaded entities (the page, their to-one associations and the
fetch-joined children) and mapped them to DTOs. Every loaded entity costs a persistence-context
entry, a collection wrapper per association and the hydration of columns the response does not
use. The list endpoints now select exactly the columns the DTOs need with JPQL constructor
expressions (`select new ...Row(...)`) and build the DTOs from those rows, so nothing is tracked
by the session. The "after" column is pinned by `QueryCountIT` (`getEntityLoadCount()`). The
"before" column was measured on 2026-10-19 on the parent of each change: `a8499f4`, the parent
of `965ce93` (#34) that introduced the projections, for the four list requests, and `1da6fa0`,
the parent of `ca861cb` (#43) that moved `GET /products/{id}` to the product row and the bounded
order ids. Each commit was checked out with `git archive` and the requests were counted alone,
with Hibernate statistics, in a fresh Testcontainers database on the seed. The projections left
the statement counts of the list requests unchanged.

| Request | Entities loaded before | Entities loaded after | Statements |
|---------|------------------------|-----------------------|------------|
| `GET /order?size=100` | 263 (100 orders, their 63 customers and the 100 products of those orders) | 0 | 3 → 3 |
| `GET /customer?size=50` | 4,996 (50 customers and their 4,946 orders) | 0 | 3 → 3 |
| `GET /customer?name=a&size=5` | 502 (5 customers and their 497 orders) | 0 | 3 → 3 |
| `GET /products?size=20` | 3,420 (20 products and the 3,400 orders containing them) | 0 | 3 → 3 |
| `GET /products/2` | 201 (the product and its 200 orders, through the entity graph, in 1 statement) | 0 | 1 → 2 |
| `GET /customer/1` | n/a (the endpoint is new) | 0 | 2 |

This saves CPU and allocation on the service, not round trips. It does not save a dirty check:
the read services were already `@Transactional(readOnly = true)`, which disables flushing.
`GET /order/{id}` keeps its single-statement entity-graph query and MapStruct mapping; one order
with its customer and its products is cheap. `GET /products/{id}` no longer loads the product
with every order containing it (201 entities for product 2 on the seed): it reads the product
row and the bounded order ids, 2 statements and no entity (`QueryCountIT`), and is cached by id
and evicted on order creation as before.

The nested collections are bounded by the same two child queries. A customer page used to embed
every order of every customer on the page (1,941 orders for the default page of 20 on the seed,
97,740 B) and a product every order containing it (200 ids for product 2), so the payload grew
with the orders per customer and per product even though the statement count was fixed. The
child query is now a native window query: `row_number() over (partition by customer_id order by
id desc)` keeps the newest `NESTED_ORDERS_LIMIT` orders of each customer on the page and
`count(*) over (partition by customer_id)` supplies `orderCount`, in one statement; the same
shape over `order_product` serves the products and reads `idx_order_product_product_id_order_id`
(`MigrationIT.productOrderWindowIsServedByTheProductIdOrderIdIndex`). A `size=100` page on the
100-customer seed legitimately scans the whole table, because those customers own all of it
(measured on 2026-10-19 on the seed with the jar of `f0b1595`, and the same on 2026-10-20 with
the jar of `062b418`, which has changeset 8: unaided, the statement for all 100 customers is a
`Seq Scan on "order"`, and for the 20 customers of the default page a
`Bitmap Index Scan on idx_order_customer_id` returning their 1,941 orders).
`MigrationIT.customerOrderWindowForTheDefaultPageIsServedByTheCustomerIdIndex` pins that bitmap
scan of `idx_order_customer_id` for the default page with sequential scans disabled.
Changeset 8's `idx_order_customer_id_id` also leads with `customer_id`: for one to five customers
the planner costs the two indexes the same and reads `idx_order_customer_id_id`, and from six to
the 100 of a full page it rates `idx_order_customer_id`, 11 pages against 41, as cheaper (on the
seed on 2026-10-20 with the jar of `062b418`, with sequential scans disabled and each index
dropped in turn in a rolled-back transaction; the same before any `ANALYZE` or `VACUUM` and after
either or both).
The statement's work is proportional to the orders of the
parents on the page, bounded in count by `size` and unbounded per parent: `count(*) over
(partition by customer_id)` reads every order of each parent to produce `orderCount`, so a
customer with a million orders costs a million index entries on every page it appears on even
though the response carries twenty of them (on the production-sized table of "Filtered pages",
the window for the first page of products, which holds the ten best-sellers, reads their
1,010,000 order lines from 3,930 buffers of `idx_order_product_product_id_order_id` and sorts
them, spilling to temporary files under the default 4 MB `work_mem`: 457 to 483 ms, measured as
described there). If a parent's orders ever grow without bound, the
remedy is a cached or approximate `orderCount`, not a different window. What is bounded now:
rows per page (`size`, at most 100), orders per customer and order ids per product
(`NESTED_ORDERS_LIMIT`, 1 to 100, default 20) and products per order (at most 100 at creation).
The full lists are `GET /order?customerId=` and `GET /order?productId=`, at the usual 3 or 2
statements per page. The bound's effect on the body, "before" measured on `1da6fa0` the same way
as the entities above (the uncompressed response body) and "after" under "Results": the default
customer page went from 97,740 B with 1,941 nested orders to 21,343 B with 400 (20 per customer,
`orderCount` still totalling 1,941), and `GET /products/2` from 1,025 B with 200 order ids to
165 B with 20 and `orderCount` 200.

## Deep pages
`LIMIT/OFFSET` makes the database read and discard every row before the requested page, so the
cost of an offset page grows with its depth, and each page also pays the count query. With
`after=<id>` the page query seeks into the primary key index and the count is skipped. Plans
measured by hand on 2026-10-19 with the jar of `f0b1595` (`main` at the time): a throwaway
PostgreSQL 16.2 migrated with the seed, the statements as the application sends them (read
from the server's `auto_explain` log), and `EXPLAIN (ANALYZE, BUFFERS)` of prepared copies,
warm, as the range over 15 runs in two sessions (a local database: the absolute times only show
the ratio, the production database adds a network round trip to each statement):

| Request | Page query | Rows the plan walks | Query time | Statements |
|---------|------------|---------------------|------------|------------|
| `GET /order?page=499&size=20` | `order by o1_0.id offset 9980 rows fetch first 20 rows only` | 10,000 index entries of `order_pkey`, 9,980 of them thrown away, each joined to its customer through a `Memoize` node that answers 9,900 of the 10,000 lookups from its cache | 2.5 to 3.1 ms, plus 0.6 to 1.4 ms for the count, a sequential scan of the order table | 3 |
| `GET /order?after=9980&size=20` | `where o1_0.id>9980 order by o1_0.id fetch first 21 rows only` | 20 index entries of `order_pkey`, hash-joined to the 100-row customer table | 0.023 to 0.039 ms, no count | 2 |

The first page costs the same in both modes; the saving is proportional to the depth. The
cursor is the last id of the page (`nextCursor`), so a client walks the whole table in
`ceil(n / size)` requests of constant cost, and `QueryCountIT` pins the 2 statements of all six
cursor queries: orders, customers, the name search, products and the two order filters.

## Sorted pages
The whitelisted sort properties (`description` on orders and products, `name` on customers)
had no B-tree index, so a request such as `GET /order?sort=description,desc` sorted the whole
order table before applying the limit, and the order of equal descriptions (5,317 distinct among
the 10,000 seeded orders) was whatever the sort happened to produce, so a page boundary could
split or repeat a tie. Two changes:

- `SortPolicy` appends `id` in the direction of the last client property (`description desc`
  becomes `description desc, id desc`) unless the client already sorts by `id`; an unsorted
  request keeps the default `id` order. The tie-break is for a deterministic boundary, not for
  the plan: `order by description desc` alone gets the backward index scan below, and keeping
  the tie-break in the same direction keeps the plan a plain index scan (a mixed
  `description desc, id asc` produces an `Incremental Sort`; both plans checked on 2026-10-19
  the same way as the table below).
- Changeset 7 adds `idx_order_description_id` on `"order" (description, id)`,
  `idx_product_description_id` on `product (description, id)` and `idx_customer_name_id` on
  `customer (name, id)`: 512 kB, 16 kB and 16 kB on the seed (`pg_relation_size`, 2026-10-19).

Plans measured by hand the same way as under "Deep pages" (2026-10-19, the jar of `f0b1595`, the
seed). The "before" row is the same database with changeset 7's three indexes dropped inside a
rolled-back transaction, which is the schema of `7054a3b`, the parent of `d83c84c` (#53) that
added them (the changelogs of the two commits differ by changeset 7 alone), running the
statement of that commit, which had no `id` tie-break:

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

`Memoize` caches the customer lookups by `customer_id`, so a page looks up each distinct
customer once (19 lookups for the 20 orders of the first page). The deep page's plan also
depends on PostgreSQL's plan cache. PgJDBC turns a statement into a named server-side prepared
statement on its fifth execution on a connection (`prepareThreshold` 5), and PostgreSQL plans
the first five executions of such a statement with their parameter values and may then keep a
generic plan, made without them, so from its tenth execution on a connection a statement can
change plan. On one connection (`DB_POOL_SIZE=1`, the server's `auto_explain` logging every
plan) the first nine `GET /order?sort=description,desc&page=499&size=20` sorted the table in 14
to 26 ms, and from the tenth on the generic plan, which does not know the offset, walked the
index in 3.5 to 3.6 ms.

So the index removes the full-table work for shallow sorted pages only; a deep sorted offset
page still costs the offset, exactly as "Deep pages" describes for the `id` order, and cursor
mode remains the constant-cost walk in `id` order only (`after` cannot be combined with
`sort`). For `product` and `customer` the page selects only indexed columns, so the plans read
`Index Only Scan Backward using idx_product_description_id` and
`Index Only Scan using idx_customer_name_id`. `MigrationIT` pins the three plans with
`SET LOCAL enable_seqscan = off` on the seed (`sortedOrderPageIsServedByTheDescriptionIndex`,
`sortedProductPageIsServedByTheDescriptionIndex`, `sortedCustomerPageIsServedByTheNameIndex`).
As with the trigram pin below, each `EXPLAIN`s a hand-written statement of the shape the
repositories' JPQL renders to (`order by description desc, id desc` and the other two), so the
pins hold that the sorted shape is served by the index, not the SQL Hibernate renders, which is
not captured; the tie-break direction that keeps the rendered shape index-servable is held by
`SortPolicyTest.appendsAnIdTieBreakInTheDirectionOfTheLastProperty`. A filtered page sorted by
`description` is measured under "Filtered pages", and the name search keeps the trigram index.

## Substring search
`GET /customer?name=<word>` runs `lower(c.name) like lower(concat('%', :word, '%')) escape '!'`
(`CustomerRepository`), which PostgreSQL plans as `lower(name) ~~ '%word%'`. Changeset 4 creates
`idx_customer_name_trgm`, a GIN index with `gin_trgm_ops` on the same expression, `lower(name)`.
Whether the planner uses it depends on the table and the statement: on the 100-row seed a
sequential scan of one page is cheaper than a GIN lookup, so the seed never shows the index.
Every plan below was measured by hand on 2026-10-19 (the words `zq`, `xw`, `7`, `a`, `q` and `z`
on 2026-10-20, on the 200,000-row table rebuilt the same way) with the jar of `f0b1595` on a
throwaway PostgreSQL 16.2, with `EXPLAIN (ANALYZE, BUFFERS)`, warm, the times as the range over
five runs.

The 100 seeded customers, unaided, and then with `SET LOCAL enable_seqscan = off`, which is
what `MigrationIT.customerNameSearchPredicateIsServedByTheTrigramIndex` pins: it `EXPLAIN`s a
hand-written statement of the same shape (`lower(name) LIKE lower(...) ESCAPE '!'`), so it pins
that the predicate shape is served by the index, not the SQL Hibernate renders from the JPQL
(which matches today). Since changeset 7 the pin also drops `idx_customer_name_id` inside its
rolled-back transaction: with sequential scans off and that index present, the planner prefers
an `Index Only Scan using idx_customer_name_id` that filters all 100 entries (cost 14.1) to the
GIN lookup (cost 17.0), so on the seed the trigram path appears only without it.

```
Seq Scan on customer  (cost=0.00..2.50 rows=1 width=23) (actual time=0.003..0.022 rows=1 loops=1)
  Filter: (lower((name)::text) ~~ '%onne%'::text)
  Rows Removed by Filter: 99
  Buffers: shared hit=1
Execution Time: 0.025 ms

Bitmap Heap Scan on customer  (cost=12.99..17.00 rows=1 width=23) (actual time=0.005..0.005 rows=1 loops=1)
  Recheck Cond: (lower((name)::text) ~~ '%onne%'::text)
  Heap Blocks: exact=1
  Buffers: shared hit=6
  ->  Bitmap Index Scan on idx_customer_name_trgm  (cost=0.00..12.99 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
        Index Cond: (lower((name)::text) ~~ '%onne%'::text)
        Buffers: shared hit=5
Execution Time: 0.012 ms
```

The first took 0.025 to 0.037 ms and the second 0.009 to 0.019 ms; the search's own page
statement on the seed is the same sequential scan under a sort and a limit (0.023 to 0.046 ms).

The production-sized case is 200,000 customers made by this statement in a database that the
jar migrated with `LIQUIBASE_CONTEXTS=prod` (no seed), with the two name indexes dropped before
the load and created again after it as changesets 4 and 7 define them, then `ANALYZE customer`:

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

The table holds 600 distinct names (20 first names by 30 surnames): 6,666 rows contain `onne`,
13,333 contain `on` and none contains `zqxw` or `zq`. A search runs two statements, which
Hibernate renders as

```sql
select c1_0.id,c1_0.name from customer c1_0 where lower(c1_0.name) like lower(('%'||?||'%')) escape '!' order by c1_0.id fetch first ? rows only
select count(c1_0.id) from customer c1_0 where lower(c1_0.name) like lower(('%'||?||'%')) escape '!'
```

(the page adds `offset ? rows` from page 1 on, and Spring Data skips the count when the first
page is short). They were measured as `EXPLAIN (ANALYZE, BUFFERS) EXECUTE` of prepared copies
under `plan_cache_mode = force_custom_plan`, the plans a connection's first executions get; the
server's `auto_explain` log of the application's own requests against the same table showed the
same plans.

| Statement | Plan | Buffers | Time |
|-----------|------|---------|------|
| Page 0 of `onne` (3.3% of the rows) | `Limit → Index Scan using customer_pkey`, the filter removing 573 rows before 20 match | 8 | 0.17 to 0.19 ms |
| Page 1 of `onne` | the same walk of `customer_pkey`, 1,153 rows removed | 14 | 0.26 to 0.28 ms |
| Page 332 of `onne` (offset 6,640, the last full page) | `Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm` over all 6,666 matches | 1,346 | 2.7 to 2.9 ms |
| Count of `onne` | `Aggregate → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm`, 6,666 rows, none removed by the recheck | 1,346 | 2.6 to 3.1 ms |
| Page 0 of `zqxw` (no match, so no count) | `Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm` | 3 | 0.005 to 0.008 ms |
| Page 1 and count of `zqxw` | the same trigram lookup | 3 | 0.006 to 0.021 ms |
| Page 0 of `on` (two letters) | the walk of `customer_pkey`, 273 rows removed | 5 | 0.07 to 0.09 ms |
| Count of `on` | `Parallel Seq Scan on customer`: `pg_trgm` extracts no trigram from a pattern shorter than three characters, so a trigram scan would have to read the whole index, and for a word this common the planner prefers the parallel sequential scan | 1,334 | 19.5 to 20.0 ms |
| Page 0 of `zq` (two letters, no match, so no count) | `Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan on idx_customer_name_trgm`, but, with no trigram to look up, the index scan reads the whole index (651 of the buffers) and returns all 200,000 rows, every one removed by the recheck | 1,985 | 50.4 to 50.6 ms |
| Page 1 and count of `zq` | the same scan of the whole index on each statement | 1,985 | 48.0 to 51.3 ms |

So for words of three or more characters the index serves the count, rare words and deep pages
of a common word, while a shallow page of a common word walks the primary key and stops after
`size` matches, which costs little while the matches are dense, and a word that matches nothing
costs three buffers on either statement (a shorter one costs a scan of the whole index and a
recheck of every row, as the `zq` rows show). The page and the count of `onne`:

```
Limit  (cost=0.42..11.26 rows=20 width=23) (actual time=0.015..0.179 rows=20 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using customer_pkey on customer c1_0  (cost=0.42..7541.42 rows=13919 width=23) (actual time=0.015..0.178 rows=20 loops=1)
        Filter: (lower((name)::text) ~~ '%onne%'::text)
        Rows Removed by Filter: 573
        Buffers: shared hit=8
Execution Time: 0.183 ms

Aggregate  (cost=1684.25..1684.26 rows=1 width=8) (actual time=2.628..2.628 rows=1 loops=1)
  Buffers: shared hit=1346
  ->  Bitmap Heap Scan on customer c1_0  (cost=106.67..1649.46 rows=13919 width=8) (actual time=0.418..2.355 rows=6666 loops=1)
        Recheck Cond: (lower((name)::text) ~~ '%onne%'::text)
        Heap Blocks: exact=1334
        Buffers: shared hit=1346
        ->  Bitmap Index Scan on idx_customer_name_trgm  (cost=0.00..103.19 rows=13919 width=0) (actual time=0.360..0.360 rows=6666 loops=1)
              Index Cond: (lower((name)::text) ~~ '%onne%'::text)
              Buffers: shared hit=12
Execution Time: 2.641 ms
```

The plan cache (see "Sorted pages") changes the count on a busy connection. With one pooled
connection the count switched to a generic plan from its tenth execution and kept the trigram
index for `onne` (3.8 to 6.1 ms in the application's requests), while the page statement with an
offset stayed on custom plans through all sixteen of its executions. A generic count cannot tell
that a two-letter word yields no trigram, so for `on` it reads the whole trigram index and the
recheck removes 186,667 of the 200,000 rows (1,985 buffers; 76 to 78 ms under
`force_generic_plan`, 82 and 92 ms in the application's requests), about four times the parallel
sequential scan of the custom plan. Forcing generic plans everywhere would hurt rare words
instead: the generic page plan walks `customer_pkey` for `zqxw` and filters out all 200,000 rows
(1,883 buffers, 67 to 69 ms instead of under 0.01 ms). `prepareThreshold` and `plan_cache_mode`
therefore stay at their defaults, which let PostgreSQL choose per statement.

So the index is right for the production table and irrelevant for the seed. A one- or
two-character search (which the API accepts) gets no help from it, because `pg_trgm` extracts no
trigram from such a pattern and a trigram scan then reads the whole index and rechecks every row.
Its count reads every row, as a parallel sequential scan for a common word or through the whole
trigram index for a word the planner expects to be rare (and on a generic plan for any such word).
Its page walks the primary key until `size` rows past the offset match for a common word, while
for a rare word it reads the whole trigram index and rechecks every row: about 50 ms for each
statement, more than twice the parallel sequential scan of a common word's count. The `zq` rows
show the rare case; `xw` and the single character `7`, which match nothing either, take the same
plans, and the single letters `a`, `q` and `z` (165,971, 6,667 and 9,990 matches) the common-word
ones. The page limit still bounds what is returned. If such searches turn out to be common the
remedy is a minimum word length in the API, not another index.

## Filtered pages
`GET /order?customerId=` and `GET /order?productId=` page the orders of one customer or of one
product, at 3 statements for a full page and 2 for a cursor page (`QueryCountIT`). Their plans
were measured by hand on 2026-10-20 with the jar of `992cd7e` ("before", whose filtered
statements are the ones the code had until changeset 8) and with the jar of `062b418` ("after",
whose changeset 8 and filtered statements, below, are unchanged since `2d8b68e` completed the
changeset), on throwaway PostgreSQL 16.2 containers (Docker Desktop on arm64, default settings,
with autovacuum off for the "after" runs, which followed an explicit `VACUUM ANALYZE`): the
statements captured from each jar's `org.hibernate.SQL` log, run as
`EXPLAIN (ANALYZE, BUFFERS) EXECUTE` of prepared copies under
`plan_cache_mode = force_custom_plan`, warm, the time as the range over five runs after one
warm-up. The buffer counts are the figure to compare: for the same data and plan they repeat
exactly from run to run (a parallel plan varies by a few hundred), while the times vary with the
host and its load: within one set of five runs the slowest took up to 6.8 times as long as the
fastest for a statement under a millisecond and up to 1.8 times for a longer one. The server's
`auto_explain` log of each jar's own requests showed the same plans, and the statements of the
table below keep their plans under `force_generic_plan`.

Before, the customer page was the order page with `where c1_0.id=?`, which the planner carries
over to `o1_0.customer_id`, and its count `where o1_0.customer_id=?`; the product page joined
`order_product` with `where p1_0.product_id=?`, each cursor added `o1_0.id>?`, and the product
count counted the join. On the seed, for customer 1 and product 2, only the two counts used
`idx_order_customer_id` and `idx_order_product_product_id`: customer 1's first page walked
`order_pkey` and filtered on `customer_id`, removing 2,332 rows (2,498 for the cursor from 0),
and product 2's page and cursor merge-joined `order_pkey` (943 and 1,001 entries) with an
index-only scan of `pk_order_product`, while customer 1's second page and a customer added with
21 orders holding the highest ids got a bitmap scan of `idx_order_customer_id` and a sort. Which
plan runs depends on how selective the planner expects the id to be, and every plan is cheap on
the seed, so a production-sized table decides.

That table holds the 200,000 customers of "Substring search", made the same way in a database
the jar migrated with `LIQUIBASE_CONTEXTS=prod`, 1,000,000 orders numbered in time order and
2,001,000 order lines, loaded by these statements with the order and order-line indexes dropped
before the load and created again after it, then `VACUUM ANALYZE`:

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

So 199,900 customers have 4 or 5 orders each, placed together when they sign up (customer 1
holds orders 1 to 5, customer 199,999 the last ones), and 100 large accounts, customers 2,000,
4,000 and so on to 200,000, have 1,000 orders each, spread from their sign-up to the newest
order (customer 2,000's over the whole table, customer 200,000's over its last 14,175 ids).
Products 1 to 10 sell best: one is launched every 100,000 orders and each order takes the
launched ones in turn (product 1 is in 292,899 orders of every age, product 10 in 10,000 orders
above id 900,000). Products 11 to 1,010 each sell in one run of 1,000 consecutive orders
(product 11 in orders 1 to 1,000, product 1,010 in the last 1,000), and products 1,011 to 1,210
have 5 orders each, 1,000 ids apart (product 1,011 in orders 500 to 4,500, product 1,210 in
995,500 to 999,500). Customers and products with few orders at old ids were cheap before and
stay so (customer 1: 8, 8 and 4 buffers for its page, cursor and count; product 1,011: 77
buffers before, 44 after). The rest:

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

The plans before came from the planner's estimates. Either it read the foreign-key index and
sorted all the matches, which reads every order of a large account on every page, or it walked
the primary key in id order until the page was full, which assumes that the matches are spread
over all ids and walks most of the table for a product launched mid-way or lately. The same
statement also changed plan with the statistics sample (product 10 above).

Changeset 8 adds `idx_order_customer_id_id` on `"order" (customer_id, id)` and
`idx_order_product_product_id_order_id` on `order_product (product_id, order_id)`, in the
concurrent form of ADR 0009. The customer statements are unchanged: the page and the cursor read
the customer's entries in id order and stop after the page, and the count is an index-only scan.
For products the index was not enough by itself. With it, the statements of `992cd7e` still
merge-joined `order_pkey` from the first id for product 10 (900,199 entries, 10,263 buffers, 83
to 91 ms), because nothing tells the planner that a frequent product's orders are recent, and
the cursor's `o1_0.id>?` stays on `"order"`, because PostgreSQL carries an equality across the
join but not a range, so the cursor after product 1's median order id still read its first
146,471 index entries (614 buffers, 10.2 to 10.5 ms). A product page in id order and the
product cursor therefore take the page's order ids from the index first, in a subquery with its
own order, limit and offset, and then read those orders and their customers:

```sql
select o.id as "id", o.description as "description", c.id as "customerId", c.name as "customerName" from "order" o join customer c on c.id = o.customer_id where o.id in (select op.order_id from order_product op where op.product_id = ? and op.order_id > ? order by op.order_id limit ?) order by o.id
```

The page has `order by op.order_id limit ? offset ?` and no range. Both are native statements,
because JPQL reaches `order_product.order_id` only through a join to `"order"`; a product page
sorted by another property keeps the join. The product count, `select count(po) from Product p
join p.orders po where p.id = :productId` in JPQL, is rendered without `"order"` and reads the
product's entries of the index alone. On the seed the same changes take customer 1's page,
cursor and count from 29, 31 and 57 buffers to 15, 16 and 3, and product 2's pages and cursors
to 103 to 108 buffers (55 to 106 before, now without the walk of `order_pkey`) in at most
0.061 ms, its count from 512 buffers to 5.

What the filters still cost: an offset page reads its offset (a large account's page 49 reads
all of its 1,000 orders, 1,007 buffers, 0.42 to 0.50 ms, and product 10's page 50 above); a
count reads every entry of its customer or product, 38 to 39 ms for product 1; and a filtered
page sorted by `description` either sorts the filtered orders (a large account's page: 1,007
buffers, 0.45 to 0.76 ms) or walks `idx_order_description_id` until the page is full, which for
a product probes `idx_order_product_product_id_order_id` once per order walked (product 11:
about 163,500 buffers, varying by a few hundred because parallel workers share the walk, 35 to
45 ms; product 10: 404,374 buffers, 111 to 122 ms). With the indexes as they were before
changeset 8, restored in a rolled-back transaction, the same two pages read as many buffers but
probe `pk_order_product`, where the entries of the orders walked spread over the whole 90 MB
index, so about a fifth of those buffers missed the default 128 MB of shared buffers and were
read in from the operating system (115 to 143 ms and 376 to 409 ms). A page in descending id
order read at most 164 buffers for every customer and product measured. For a product it keeps
the join, which could walk `order_pkey` backwards from the newest order for a frequently ordered
product whose orders all hold old ids, the mirror image of product 10; this table has no such
product.

`idx_order_customer_id` stays: without it the planner walks `idx_order_description_id` for a
large account's page sorted by description (customer 2,000: 18,542 entries, 18,660 buffers, 8.1
to 13.4 ms; customer 200,000: 15,995 entries, 16,098 buffers, 6.2 to 11.2 ms; with it 1,007 and
127 buffers), because it rates the heap access of an index on more than one column as less
ordered than that of a single-column index. `idx_order_product_product_id` is dropped by
changeset 8: the new index serves every statement that used it, and the window query behind the
products' nested order ids reads it index-only (3,930 buffers for the twenty products of the
first page, which include the ten best-sellers, instead of 30,702). Through the service's
Liquibase, run as the `migrate` job runs it with the jar of `062b418` on 2026-10-20 after the
three changesets had been reverted by hand, the two builds took 307 to 447 ms and 584 to 736 ms
on this table and the drop 4 to 5 ms in five runs (ADR 0009).

`MigrationIT` pins the "after" plans with sequential scans disabled. Unlike the other pins it
does not `EXPLAIN` a hand-written copy: it sends a filtered request, captures the statements it
runs from the `org.hibernate.SQL` logger, binds the request's values and explains those
(`customerFilteredPageIsReadInIdOrderFromTheCustomerIdIdIndex`,
`customerFilteredCursorSeeksTheCustomerIdIdIndex`, `customerFilteredCountUsesTheCustomerIdIdIndex`,
`productFilteredPageTakesItsOrderIdsFromALimitedScanOfTheProductIdOrderIdIndex`,
`productFilteredCursorSeeksTheProductIdOrderIdIndex` and
`productFilteredCountReadsTheProductIdOrderIdIndexWithoutTheOrderTable`), so a change to the
JPQL or to the native statements that loses the index fails the build. In the suite's seeded
database, with sequential scans off, the customer cursor is a bitmap scan of the new index and a
sort of its few rows until the tables are analyzed, and an index scan in id order afterwards; the
pins hold in both states.

## Caching
Two layers, both measured in the integration tests (`HttpCacheIT`, `CompressionIT`,
`QueryCountIT`, `ProductApiIT`); the design and its staleness bounds are in ADR 0007.

| Scenario | Before | After | Why |
|----------|--------|-------|-----|
| `GET /order/1` repeated with the `ETag` of the first response in `If-None-Match` | 200 with the 178 B body on every call | `304 Not Modified`, no body, the ETag echoed | `ShallowEtagHeaderFilter` on `/order`, `/order/*`, `/customer`, `/customer/*`, `/products`, `/products/*`; the ETag is weak so Tomcat still compresses, and `Cache-Control: no-cache` makes caches revalidate instead of serving stale bodies |
| `GET /order?size=100` with `Accept-Encoding: gzip` | gzipped, under 4 KB | gzipped, under 4 KB, plus the ETag; a revalidation sends 0 bytes of body | Weak ETags are not excluded from compression |
| `GET /order/1` twice, `GET /products/2` twice | 1 (order) or 2 (product) statements and 1 session per call | second call: 0 statements, 0 sessions (`getPrepareStatementCount()`, `getSessionOpenCount()`) | `@Cacheable` on `findById`; `@EnableCaching(order = HIGHEST_PRECEDENCE)` places the cache advice outside the transaction advice, so a hit never enters the transactional method |
| `GET /products/{id}` after `POST /order` containing that product | n/a | the next read lists the new order (2 statements, then cached again) | `OrderCreatedEvent` → `ProductCacheEvictor`, `AFTER_COMMIT` |

The "Before" column is the same request without the layer in question, at today's sizes and
cold-read costs: rows 1 and 2 are what every call returned before `b0495cf` (#36) added the
ETag filter (row 2's bound is the one `CompressionIT` asserts), and row 3 is the cold read that
`QueryCountIT` pins (`orderByIdUsesOneStatement`,
`productByIdUsesTwoStatementsAndLoadsNoEntities`), which every call costs with
`CACHE_TYPE=none`.

The ETag filter is shallow: the controller and the service still run on a conditional request,
so a 304 saves response bytes and client parsing, not server work; the service cache is what
saves the round trips. The filter also buffers the whole response body in memory to hash it
before the first byte is written, on every `GET` it covers, whether or not the client sent
`If-None-Match`: one extra copy of the body per request, at most the 106 KB of
`GET /customer?size=100`. That is accepted because the pages are bounded (above), so the copy is
too, and the hash is what makes the 304 possible. The cache keeps DTOs, which are immutable
(each record copies its lists when it is built, so every hit can share one instance), keyed by
id, bounded to 10,000 entries and 60 s (`CACHE_TTL`); `CACHE_TYPE=none` disables it.
The hit rate is visible as
`cache_gets_total{cache="orders"|"products",result="hit"|"miss"}` in `/actuator/prometheus`;
the TTL should only be raised on that evidence, because a product can be stale for up to the
TTL: when it is read on another replica, after a write that bypasses the service, or when a read
that missed the cache before an order's commit stores the old value after the eviction.

## Write path
`getPrepareStatementCount()` counts prepared statements; PgJDBC pipelines a JDBC batch, and
with `reWriteBatchedInserts=true` it sends a batch as one multi-row `INSERT`. The "after" column
is pinned by `QueryCountIT` and `InsertBatchingIT`. The "before" column was measured on
2026-10-19 on `d3c298b`, the parent of `a8499f4` (#33) that introduced the sequences and the
batching, with the same two scenarios counted alone, with Hibernate statistics, in a fresh
Testcontainers database:

| Scenario | Prepared statements before | Prepared statements after | Why |
|----------|----------------------------|---------------------------|-----|
| `POST /order` with three products | 6 (customer + products lookups, 1 order insert, 3 join-table inserts) | 4 (5 on the request that allocates the next block of 50 ids) | `jdbc.batch_size` turns the three `order_product` inserts into one batched statement |
| Three orders with three products each in one flush | 12 (3 order inserts + 9 join-table inserts) | 2 (3 on the flush that allocates the block): 1 batched order insert, 1 batched join-table insert | Ids come from the sequence block, so the inserts are deferred and batched per table |

`QueryCountIT` asserts 4 to 5 and `InsertBatchingIT` 2 to 3: the suite creates well under 50
orders per JVM, so exactly one request or flush per run pays the `nextval`, and which one it is
depends on test order. With `jdbc.batch_size: 1` the two counts rise to 7 and 12.

The API inserts one order per request today, so the second row matters for future bulk paths
(an import endpoint, a migration job) rather than for the current endpoints; it is also the
test that proves the sequence switch, because `IDENTITY` would issue three separate order
inserts regardless of the batch size.

An order with the maximum of 100 products costs the same as one with three. Its 100 join rows
exceed `jdbc.batch_size: 50`, so Hibernate executes the one prepared join-row insert as two
JDBC batches of 50 (its batch log, `org.hibernate.orm.jdbc.batch` at `TRACE`, shows
`Executing JDBC batch (50 / 50)` twice for `Order.products`, observed on 2026-10-19 with the jar
of `f0b1595` on the seed), and the request stays at 4
prepared statements, 5 when it allocates the block
(`QueryCountIT.createOrderWithOneHundredProductsUsesFourStatementsPlusTheBlockAllocation`). The
pin does not see how many batches there are: it still passes with `jdbc.batch_size: 100`, one
batch, and fails at 103 with `jdbc.batch_size: 1`, which prepares every join row on its own.

## Deliberately not done
- **Caching the list pages.** The by-id reads are cached (above); the pages are not. A page
  key would have to include page, size, sort, cursor and the `name`, `customerId` and
  `productId` filters, and every `POST /order`, `POST /customer` or `POST /products` changes the
  totals of at least one list, so the entries would be invalidated as fast as they are filled.
  The lists are already at most 2 or 3 statements and the HTTP revalidation covers the client
  that polls the same page.
