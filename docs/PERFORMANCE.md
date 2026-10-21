# Performance analysis of the GET endpoints

This document records why `GET /customer` and `GET /order` were slow, what was changed and what
the changes measure. It opens with the symptom, the measurements on the original code, the
measures and the results. The later sections each cover one topic in depth, and the last lists
what was deliberately not done.

## Symptom
Users reported that `GET /customer` and `GET /order` were very slow in production. The database
there is not co-located with the service, so every round trip (one exchange between service and
database) pays a high network latency.

## Measurements on the original code
Each list call ran 11 statements and returned the whole table. At 30 ms per round trip that is
at least 330 ms of waiting before any serialisation, and the cost grew with the data.

Method (2026-10-19): the jar of the import commit `d0532d8` (`git show d0532d8:<path>` reproduces
any file) against a throwaway PostgreSQL 16.2 that its own changesets migrated and seeded
(100 customers, 10,000 orders).

- Statement counts: Hibernate statistics
  (`spring.jpa.properties.hibernate.generate_statistics: true` and
  `org.hibernate.engine.internal.StatisticalLoggingSessionEventListener` at INFO, one session per
  request).
- Body sizes: `curl -s -o /dev/null -w '%{size_download}'`.

| Request | Rows returned | Payload | JDBC statements | Why |
|---------|---------------|---------|-----------------|-----|
| `GET /order` | 10,000 | 950,440 B | 11 | 1 query for all orders, then `default_batch_fetch_size: 10` loaded the 100 customers in batches of 10 |
| `GET /customer` | 100 (+10,000 nested orders) | 503,458 B | 11 | 1 query for all customers, then 10 batch loads of the lazy `orders` collections |

Why it was slow:

- Each statement costs at least one round trip, and round trips dominate with a remote database.
- Both endpoints materialised the whole table on every call, so cost grew linearly with data
  volume.
- The lazy loading (loading an association when it is first touched) only worked because
  `spring.jpa.open-in-view` was at its default, which keeps a database session open for the
  whole HTTP request.
- The foreign key `order.customer_id` had no index, so any lookup of orders by customer scanned
  the whole order table.
- The serial sequences had not been advanced past the seeded ids, so the first insert failed
  outright.

How to read the figures in this document. Pinned figures fail the build when they change. They
are the statement and entity counts (`getPrepareStatementCount()` and `getEntityLoadCount()` in
`QueryCountIT`; prepared statements and inserts in `InsertBatchingIT`) and the plan shapes that
`MigrationIT` asserts with sequential scans disabled (for the filtered pages, of the statements
the application sends). Hand-measured figures (payloads, query timings, the plans the planner
picks unaided) carry their commit, date and method, and nothing re-checks them. A "before" figure
comes from the original code or, for a later measure, from the parent of the commit that
introduced it, which its section names.

## Measures taken
Every list endpoint now returns a bounded page in at most 3 statements and a cursor page in 2;
a by-id read costs 1 or 2 statements, or 0 when cached. The details and measurements of each
measure are in the section it names.

### Query shape
- Every list endpoint is paginated: default 20 rows, maximum 100, sorted by `id`.
- A list page runs a constructor-expression query (JPQL that builds a plain row object instead
  of an entity) for the rows, the count, then one child query for exactly the rows on the page:
  3 statements, no entity loaded ("Hydration").
  - `GET /order` reads `id`, `description` and the customer's `id` and `name` through the to-one
    join, which lets the database apply the limit, then the products of those orders
    (`where o.id in (...)`).
  - `GET /customer` reads the orders of those customers in the child query, which avoids
    Hibernate's in-memory pagination (`HHH90003004`) of a fetch-joined collection with
    `Pageable`.
  - `GET /products` reads the order ids of those products.
- `GET /order/{id}`: an entity graph (a declared set of associations loaded with the entity) on
  `findById`, 1 statement; this path still loads the entity and maps it with MapStruct.
- `GET /products/{id}` and `GET /customer/{id}`: the row plus a bounded window of its orders,
  2 statements (1 on a miss), no entity. The customer is not cached in the service, because
  `orderCount` changes on every `POST /order`.
- Nested collections are bounded to the newest `NESTED_ORDERS_LIMIT` orders of a customer and
  order ids of a product (default 20), each with `orderCount`, by one native window query per
  page; `GET /order?customerId=` and `GET /order?productId=` give the full lists (ADR 0003,
  "Nested collections"; "Hydration").
- `GET /customer?name=`: the same shape plus a trigram GIN index on `lower(name)` for
  `lower(name) LIKE lower('%...%')`; 3 statements per full page, 2 for a short first page
  ("Substring search").
- `GET /order?customerId=` and `GET /order?productId=`: the page, count and products queries
  restricted to one customer or product, each served by an index that leads with the filtered
  id and ends with the order id ("Filtered pages").
- Keyset (cursor) pagination, paging by the last id seen instead of by page number: `after=<id>`
  on every list endpoint runs `where id > :after order by id` with a limit of `size + 1` and
  skips the count, so 2 statements at any depth. The page seeks into the primary key index, or
  the index of its filter, instead of reading and discarding `OFFSET` rows (ADR 0003; "Deep
  pages", "Filtered pages").
- `sort` is checked against a per-endpoint list before any query runs (orders and products:
  `id`, `description`; customers: `id`, `name`). An unknown property, or a nested path such as
  `customer.name` that would become an unindexed join, is a 400 problem detail instead of a 500
  (ADR 0006). `id` is appended as a tie-break in the direction of the last property unless the
  client already sorts by it, so pages 0 and 1 of a sorted walk never share or skip a row
  (`OrderApiIT.sortedPagesHaveDeterministicBoundaries`, `CustomerApiIT`, the Cucumber scenario;
  "Sorted pages").

### Indexes
| Index | Changeset | Effect |
|-------|-----------|--------|
| `idx_order_customer_id` on `"order" (customer_id)` | 3 | Lookups of a customer's orders are index scans |
| `idx_customer_name_trgm`, GIN with `gin_trgm_ops` on `lower(name)` | 4 | Serves the `name` search on a large table ("Substring search") |
| `idx_order_description_id`, `idx_product_description_id`, `idx_customer_name_id`: the whitelisted sort columns with `id` trailing | 7 | A shallow sorted offset page is an index scan instead of a top-N sort of the whole table ("Sorted pages") |
| `idx_order_customer_id_id` on `"order" (customer_id, id)` and `idx_order_product_product_id_order_id` on `order_product (product_id, order_id)`, replacing `idx_order_product_product_id` | 8 | Each filtered query is served by an index that leads with the filtered id and ends with the order id; every measured first page and cursor page in `id` order reads at most 172 buffers ("Filtered pages") |

### Sessions, connections and timeouts
- `spring.jpa.open-in-view: false`; services are `@Transactional(readOnly = true)` and return
  DTOs, so no query runs outside a service transaction and the connection is returned before
  serialisation starts. `default_batch_fetch_size: 100` is a safety net only; no endpoint relies
  on it.
- HikariCP: pool size `DB_POOL_SIZE` (default 10), connection timeout 5 s, validation timeout
  2 s. Behaviour under load is predictable, and the service fails fast when the database is
  unreachable.
- On every connection: `DB_STATEMENT_TIMEOUT` 10 s on the server, `DB_SOCKET_TIMEOUT` 15 s,
  `TX_TIMEOUT` 15 s, `cancelSignalTimeout` 3 s. A slow statement or a stalled database holds its
  connection and request thread for a bounded time, then answers a retryable 503. Measured on
  2026-10-19: 10.0 s for a statement waiting at the limit, 15.0 to 18.1 s for a paused database.
  The stack was the Compose build of the branch of #80, whose timeout settings and exception
  handler are those of `82f6119`; `docs/DESIGN.md`, Configuration, gives the method. No
  statement is added, so `QueryCountIT` is unchanged.

### Responses, caching and writes
- gzip for JSON above 1 KB, per response (sizes under "Results"): `/order/1` (178 B) and
  `/products/2` (165 B) are sent as is, and an order with 25 of the seeded products (1,098 B)
  is sent as 290 B (2026-10-19, the jar of `173f958`, the seed).
- Weak `ETag` and `Cache-Control: no-cache` on every API `GET`; a matching `If-None-Match` gets
  `304 Not Modified` without a body, so a re-read of an unchanged resource receives headers only
  (ADR 0007; "Caching").
- Caffeine cache in front of `GET /order/{id}` and `GET /products/{id}`: `@Cacheable`, 60 s TTL,
  10,000 entries, cache advice outside the transaction advice, `products` entries evicted after
  `POST /order` commits. A repeated by-id read within the TTL runs no statement and opens no
  Hibernate session ("Caching").
- Sequence-based ids (`allocationSize = 50`, `pooled-lo`) instead of `GenerationType.IDENTITY`,
  with `jdbc.batch_size: 50`, `order_inserts`, `order_updates` and PgJDBC
  `reWriteBatchedInserts=true`: inserts are deferred to flush and batched per table, and
  `nextval` runs once per 50 ids (ADR 0005; "Write path").
- `QueryCountIT` pins every count above, and `ProductApiIT` walks the cursor to the null
  `nextCursor` and checks that the collected ids cover a snapshot of the product table. A
  reintroduced N+1 (one query for the list, then one per row), a page that loads entities again,
  an empty page that runs its child query, a by-id read that stops being cached or a cursor that
  stops early fails the build, not production.

## Results
A list page went from 11 statements and the whole table to 3 statements and a bounded page:
about 120 ms of waiting on the network at 30 ms per round trip instead of at least 330 ms.

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

3 is the full-page cost and the maximum. Fewer statements run, pinned by `QueryCountIT`, when:

- the first page is short: 2, because Spring Data skips the count when a page is shorter than
  `size` and derives the total from the offset and the rows
  (`QueryCountIT.shortFirstPageSkipsTheCountQuery`, `name=onne&size=5` on the seed);
- the first page is empty: 1, because the services skip the child query of an empty page
  (`emptyOrderPageFilteredByCustomerUsesOneStatement`, `/order?customerId=999999`);
- an empty page lies beyond the end: 2, the page and the count, because Spring Data cannot
  derive that total (`emptyOrderPageBeyondTheEndUsesTwoStatements`,
  `/order?customerId=1&page=50&size=20`);
- the page is a cursor page: 2, or 1 when empty
  (`emptyOrderKeysetPageFilteredByCustomerUsesOneStatement`, `customerId=999999&after=0`).

The "after" payloads were measured on 2026-10-19 against the Compose stack built from `main` at
`c4bfe77` (`docker compose up -d --build` on the seeded database, removed afterwards with
`docker compose down -v`). Each row's exact path was fetched with
`curl -s -o /dev/null -w '%{size_download}'`, once as is and once with `Accept-Encoding: gzip`.

A response body is bounded by `size` (rows per page, at most 100), `NESTED_ORDERS_LIMIT` (orders
per customer and order ids per product, 1 to 100, default 20) and the 100 products an order can
carry. In production each route's latency is in `/actuator/prometheus`:
`http_server_requests_seconds_bucket` has bounds from 5 ms to 10 s (`docs/DESIGN.md`,
Operations), so a p95 or p99 is a `histogram_quantile` over those buckets, not an average.

At 30 ms per round trip a full page waits about 120 ms on the network: its 3 statements plus the
commit of its read-only transaction (PgJDBC sends the `BEGIN` with the first statement). One
round trip more is added when HikariCP validates a connection that was idle for more than
500 ms. That is inside the 250 ms bucket, where the original 11 statements waited at least
330 ms.

## Hydration
The list endpoints no longer load entities. They select exactly the columns the DTOs need with
JPQL constructor expressions (`select new ...Row(...)`) and build the DTOs from those rows, so
nothing is tracked by the session. This saves CPU and allocation on the service, not round
trips. Hydration is Hibernate turning result-set columns into managed entity objects; every
loaded entity costs a persistence-context entry, a collection wrapper per association and the
hydration of columns the response does not use.

| Request | Entities loaded before | Entities loaded after | Statements |
|---------|------------------------|-----------------------|------------|
| `GET /order?size=100` | 263 (100 orders, their 63 customers and the 100 products of those orders) | 0 | 3 → 3 |
| `GET /customer?size=50` | 4,996 (50 customers and their 4,946 orders) | 0 | 3 → 3 |
| `GET /customer?name=a&size=5` | 502 (5 customers and their 497 orders) | 0 | 3 → 3 |
| `GET /products?size=20` | 3,420 (20 products and the 3,400 orders containing them) | 0 | 3 → 3 |
| `GET /products/2` | 201 (the product and its 200 orders, through the entity graph, in 1 statement) | 0 | 1 → 2 |
| `GET /customer/1` | n/a (the endpoint is new) | 0 | 2 |

"After" is pinned by `QueryCountIT` (`getEntityLoadCount()`). "Before" was counted on
2026-10-19 with Hibernate statistics in a fresh Testcontainers database on the seed, on the
parent of each change checked out with `git archive`:

- the four list requests on `8bc8bfc`, the parent of `f2cd542` (#34), which introduced the
  projections;
- `GET /products/2` on `2364f09`, the parent of `ed01d7f` (#43), which moved `GET /products/{id}`
  to the product row and the bounded order ids.

The statement counts of the list requests did not change, and no dirty check is saved either:
the read services were already `@Transactional(readOnly = true)`, which disables flushing.
`GET /order/{id}` keeps its single-statement entity-graph query and MapStruct mapping; one order
with its customer and products is cheap. `GET /products/{id}` no longer loads the product with
every order containing it (201 entities for product 2 on the seed): it reads the product row and
the bounded order ids, 2 statements and no entity, cached by id and evicted on order creation.

### Nested collections
A customer page used to embed every order of every customer on it: 1,941 orders and 97,740 B
for the default page of 20 on the seed. A product embedded every order containing it (200 ids
for product 2). The payload therefore grew with the orders per parent although the statement
count was fixed.

The child query is now a native window query, one that ranks rows within groups without
collapsing them. In one statement, `row_number() over (partition by customer_id order by id desc)`
keeps the newest `NESTED_ORDERS_LIMIT` orders of each customer on the page, and
`count(*) over (partition by customer_id)` supplies `orderCount`. The same shape over
`order_product` serves the products and reads `idx_order_product_product_id_order_id`
(`MigrationIT.productOrderWindowIsServedByTheProductIdOrderIdIndex`).

The bound's effect on the body ("before" measured on `2364f09` as above, "after" under
"Results"):

| Response | Before | After |
|----------|--------|-------|
| Default customer page | 97,740 B, 1,941 nested orders | 21,343 B, 400 nested orders (20 per customer; `orderCount` still totals 1,941) |
| `GET /products/2` | 1,025 B, 200 order ids | 165 B, 20 order ids, `orderCount` 200 |

On the seed a `size=100` page legitimately scans the whole order table, because the 100 seeded
customers own all of it. Unaided, the window statement for all 100 customers is a
`Seq Scan on "order"`; for the 20 customers of the default page it is a
`Bitmap Index Scan on idx_order_customer_id` returning their 1,941 orders. Measured on
2026-10-19 with the jar of `173f958`, and again with the same result on 2026-10-20 with the jar
of `1bd1c00`, which has changeset 8.
`MigrationIT.customerOrderWindowForTheDefaultPageIsServedByTheCustomerIdIndex` pins that bitmap
scan with sequential scans disabled.

Changeset 8's `idx_order_customer_id_id` also leads with `customer_id`, so the planner can pick
either index. For one to five customers it costs the two the same and reads
`idx_order_customer_id_id`; from six customers up to the 100 of a full page it rates
`idx_order_customer_id` (11 pages against 41) as cheaper. Measured on the seed on 2026-10-20 with
the jar of `1bd1c00`, sequential scans disabled, each index dropped in turn in a rolled-back
transaction; the result was the same before and after `ANALYZE` or `VACUUM`.

Known limit: the window's work is bounded in count by `size` but unbounded per parent, because
`count(*) over (partition by customer_id)` reads every order of each parent for `orderCount`. A
customer with a million orders costs a million index entries on every page it appears on,
although the response carries twenty of them. On the production-sized table of "Filtered pages"
(the jar of `1bd1c00`, 2026-10-20), the first page of products holds the ten best-sellers. Its
window reads their 1,010,000 order lines from 3,930 buffers of
`idx_order_product_product_id_order_id` and sorts them, spilling to temporary files under the
default 4 MB `work_mem`, in 457 to 483 ms. Buffers are the database pages a statement reads;
they are the figure to compare, because times vary with the host and its load. If a parent's
orders ever grow without bound, the remedy is a cached or approximate `orderCount`, not a
different window.

## Deep pages
An offset page (`LIMIT/OFFSET`) reads and discards every row before it, so its cost grows with
its depth, and it pays the count query too. A cursor page (`after=<id>`) seeks into the primary
key index and skips the count.

Method: 2026-10-19, the jar of `173f958` (`main` at the time), a throwaway PostgreSQL 16.2
migrated with the seed. The statements are the ones the application sends, taken from the
server's `auto_explain` log, and were run as `EXPLAIN (ANALYZE, BUFFERS)` of prepared copies,
warm; times are the range over 15 runs in two sessions. The database was local, so the absolute
times only show the ratio; the production database adds a network round trip to each statement.

| Request | Page query | Rows the plan walks | Query time | Statements |
|---------|------------|---------------------|------------|------------|
| `GET /order?page=499&size=20` | `order by o1_0.id offset 9980 rows fetch first 20 rows only` | 10,000 index entries of `order_pkey`, 9,980 of them thrown away, each joined to its customer through a `Memoize` node that answers 9,900 of the 10,000 lookups from its cache | 2.5 to 3.1 ms, plus 0.6 to 1.4 ms for the count, a sequential scan of the order table | 3 |
| `GET /order?after=9980&size=20` | `where o1_0.id>9980 order by o1_0.id fetch first 21 rows only` | 20 index entries of `order_pkey`, hash-joined to the 100-row customer table | 0.023 to 0.039 ms, no count | 2 |

The first page costs the same in both modes; the saving is proportional to the depth. The cursor
is the last id of the page (`nextCursor`), so a client walks the whole table in `ceil(n / size)`
requests of constant cost. `QueryCountIT` pins the 2 statements of all six cursor queries:
orders, customers, the name search, products and the two order filters.

## Sorted pages
Sorting by `description` or `name` used to sort the whole table before applying the limit.
Changeset 7's indexes make a shallow sorted page an index scan: 0.024 to 0.067 ms instead of 2.1
to 2.8 ms for the first page of orders. A deep sorted offset page still pays the offset, and an
`id` tie-break makes page boundaries deterministic.

The whitelisted sort properties (`description` on orders and products, `name` on customers) had
no B-tree index, so `GET /order?sort=description,desc` sorted the whole order table before the
limit. Equal descriptions (5,317 distinct among the 10,000 seeded orders) came out in whatever
order the sort produced, so a page boundary could split or repeat a tie. Two changes:

- `SortPolicy` appends `id` in the direction of the last client property (`description desc`
  becomes `description desc, id desc`) unless the client already sorts by `id`; an unsorted
  request keeps the default `id` order. `order by description desc` alone already gets the
  index scan below; a tie-break in the same direction keeps that plan, while a mixed
  `description desc, id asc` produces an `Incremental Sort` (both plans checked on 2026-10-19
  the same way as the table below).
- Changeset 7 adds `idx_order_description_id` on `"order" (description, id)`,
  `idx_product_description_id` on `product (description, id)` and `idx_customer_name_id` on
  `customer (name, id)`: 512 kB, 16 kB and 16 kB on the seed (`pg_relation_size`, 2026-10-19).

Plans measured as under "Deep pages" (2026-10-19, the jar of `173f958`, the seed). The "before"
row drops changeset 7's three indexes inside a rolled-back transaction, which is the schema of
`56d87c7`, the parent of `33314f3` (#53) that added them (the two changelogs differ by changeset 7
alone), and runs that commit's statement, which had no `id` tie-break.

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

The deep page's plan also depends on PostgreSQL's plan cache. PgJDBC turns a statement into a
named server-side prepared statement on its fifth execution on a connection (`prepareThreshold`
5). PostgreSQL plans the first five executions of that prepared statement with their parameter
values and may then keep a generic plan made without them. So from its tenth execution on a
connection a statement can change plan. On one connection (`DB_POOL_SIZE=1`, `auto_explain`
logging every plan) the first nine `GET /order?sort=description,desc&page=499&size=20` sorted
the table in 14 to 26 ms; from the tenth the generic plan, which does not know the offset,
walked the index in 3.5 to 3.6 ms.

Cursor mode remains the constant-cost walk in `id` order only; `after` cannot be combined with
`sort`. For `product` and `customer` the page selects only indexed columns, so the plans are
`Index Only Scan Backward using idx_product_description_id` and
`Index Only Scan using idx_customer_name_id` (answered from the index without reading the
table). `MigrationIT` pins the three plans with `SET LOCAL enable_seqscan = off` on the seed
(`sortedOrderPageIsServedByTheDescriptionIndex`, `sortedProductPageIsServedByTheDescriptionIndex`,
`sortedCustomerPageIsServedByTheNameIndex`). Like the trigram pin, each `EXPLAIN`s a
hand-written statement of the shape the JPQL renders to (`order by description desc, id desc`
and the other two), not the SQL Hibernate renders, which is not captured;
`SortPolicyTest.appendsAnIdTieBreakInTheDirectionOfTheLastProperty` holds the tie-break
direction that keeps that shape index-servable. A filtered page sorted by `description` is
measured under "Filtered pages"; the name search keeps the trigram index.

## Substring search
The trigram index is right for a production-sized table (200,000 customers) and irrelevant on
the 100-row seed. For words of three or more characters it serves the count, rare words and deep
pages of a common word. A one- or two-character search gets no help from it, because `pg_trgm`
extracts no trigram from such a pattern.

`GET /customer?name=<word>` runs `lower(c.name) like lower(concat('%', :word, '%')) escape '!'`
(`CustomerRepository`), which PostgreSQL plans as `lower(name) ~~ '%word%'`. Changeset 4 creates
`idx_customer_name_trgm`, a GIN index with `gin_trgm_ops` on the same expression (a trigram is a
three-character fragment of the text; the index holds every fragment of every name). Plans
measured on 2026-10-19 (the words `zq`, `xw`, `7`, `a`, `q` and `z` on 2026-10-20, on the
200,000-row table rebuilt the same way) with the jar of `173f958` on a throwaway PostgreSQL
16.2, `EXPLAIN (ANALYZE, BUFFERS)`, warm, the range over five runs.

### The seed: 100 customers
Unaided, the predicate for `onne` is a `Seq Scan on customer` that removes 99 rows: 1 buffer,
0.025 to 0.037 ms; the search's own page statement is the same scan under a sort and a limit
(0.023 to 0.046 ms). A sequential scan of the table's one page is cheaper than a GIN lookup, so
the seed never shows the index unaided. With `SET LOCAL enable_seqscan = off` it is a
`Bitmap Heap Scan` fed by a `Bitmap Index Scan on idx_customer_name_trgm`: 6 buffers, 5 of them
in the index, 0.009 to 0.019 ms.
`MigrationIT.customerNameSearchPredicateIsServedByTheTrigramIndex` pins that forced plan by
`EXPLAIN`ing a hand-written statement of the same shape
(`lower(name) LIKE lower(...) ESCAPE '!'`), so it holds the predicate shape, not the SQL
Hibernate renders from the JPQL (which matches today). Since changeset 7 the pin also drops
`idx_customer_name_id` inside its rolled-back transaction: with sequential scans off and that
index present, the planner prefers an `Index Only Scan using idx_customer_name_id` that filters
all 100 entries (cost 14.1) to the GIN lookup (cost 17.0).

### The production-sized table of 200,000 customers
Made by this statement in a database the jar migrated with `LIQUIBASE_CONTEXTS=prod` (no seed),
with the two name indexes dropped before the load and created again after it as changesets 4
and 7 define them, then `ANALYZE customer`:

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
renders as

```sql
select c1_0.id,c1_0.name from customer c1_0 where lower(c1_0.name) like lower(('%'||?||'%')) escape '!' order by c1_0.id fetch first ? rows only
select count(c1_0.id) from customer c1_0 where lower(c1_0.name) like lower(('%'||?||'%')) escape '!'
```

(the page adds `offset ? rows` from page 1 on, and Spring Data skips the count when the first
page is short). They were measured as `EXPLAIN (ANALYZE, BUFFERS) EXECUTE` of prepared copies
under `plan_cache_mode = force_custom_plan`, the plans a connection's first executions get. The
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

A one- or two-character search, which the API accepts, gets no help from the index:

- Its count reads every row: a parallel sequential scan for a common word, or the whole trigram
  index for a word the planner expects to be rare (and on a generic plan for any such word).
- Its page walks the primary key until `size` rows past the offset match for a common word,
  which costs little while the matches are dense. For a rare word it reads the whole trigram
  index and rechecks every row: about 50 ms per statement, more than twice the parallel
  sequential scan of a common word's count.

`xw` and the single character `7`, which match nothing either, take the same plans as `zq`; the
single letters `a`, `q` and `z` (165,971, 6,667 and 9,990 matches) take the common-word ones.
The page limit still bounds what is returned. If such searches turn out to be common, the
remedy is a minimum word length in the API, not another index.

The plan cache (see "Sorted pages") changes the count on a busy connection. With one pooled
connection the count switched to a generic plan from its tenth execution and kept the trigram
index for `onne` (3.8 to 6.1 ms in the application's requests). The page statement with an
offset stayed on custom plans through all sixteen of its executions. A generic count cannot tell
that a two-letter word yields no trigram. For `on` it therefore reads the whole trigram index,
and the recheck removes 186,667 of the 200,000 rows: 1,985 buffers, 76 to 78 ms under
`force_generic_plan` and 82 and 92 ms in the application's requests, about four times the
parallel sequential scan of the custom plan. Forcing generic plans everywhere would hurt rare
words instead: the generic page plan walks `customer_pkey` for `zqxw` and filters out all
200,000 rows (1,883 buffers, 67 to 69 ms instead of under 0.01 ms). `prepareThreshold` and
`plan_cache_mode` therefore stay at their defaults, which let PostgreSQL choose per statement.

## Filtered pages
`GET /order?customerId=` and `GET /order?productId=` page the orders of one customer or one
product, at 3 statements for a full page and 2 for a cursor page (`QueryCountIT`). Before
changeset 8 the planner either read and sorted every order of a large account on every page, or
walked most of the order table for a product. After it, every measured first page and cursor
page in `id` order reads at most 172 buffers.

Method (2026-10-20):

- Jars: `1b05e6b` ("before") and `1bd1c00` ("after"; its changeset 8 and filtered statements are
  unchanged since `c9d56d7` completed the changeset).
- Database: throwaway PostgreSQL 16.2 containers, Docker Desktop on arm64, default settings;
  autovacuum off for the "after" runs, which followed an explicit `VACUUM ANALYZE`.
- Statements: taken from each jar's `org.hibernate.SQL` log and run as
  `EXPLAIN (ANALYZE, BUFFERS) EXECUTE` of prepared copies under
  `plan_cache_mode = force_custom_plan`; times are the range over five runs after one warm-up.
  The "after" statements keep their plans under `force_generic_plan`. The server's `auto_explain`
  log of each jar's own requests showed the same plans.

Where a row gives a first page and the cursor from 0, the time range covers both and a single
buffer or entry count is the page's (the cursor's is at most 10 entries and 8 buffers higher).
Buffers are the figure to compare: they were identical across the five runs of each "after"
statement (a parallel plan's within a few hundred), while times varied by up to 6.8 times under
a millisecond and 1.8 times above it.

### Before changeset 8
The customer page was the order page with `where c1_0.id=?`, which the planner carries over to
`o1_0.customer_id`, and its count `where o1_0.customer_id=?`; the product page joined
`order_product` with `where p1_0.product_id=?`, each cursor added `o1_0.id>?`, and the product
count counted the join. On the seed only the two counts used `idx_order_customer_id` and
`idx_order_product_product_id`. The other statements did not:

- customer 1's first page walked `order_pkey` filtering on `customer_id` (2,332 rows removed;
  2,498 for the cursor from 0);
- product 2's page and cursor merge-joined `order_pkey` (943 and 1,001 entries) with an
  index-only scan of `pk_order_product`;
- customer 1's second page, and a customer added with 21 orders holding the highest ids, got a
  bitmap scan of `idx_order_customer_id` and a sort.

Which plan runs depends on how selective the planner expects the id to be; every plan is cheap
on the seed, so a production-sized table decides.

### The production-sized table of 1,000,000 orders
It holds the 200,000 customers of "Substring search", made the same way in a database the jar
migrated with `LIQUIBASE_CONTEXTS=prod`, plus 1,000,000 orders numbered in time order and
2,001,000 order lines. The statements below loaded them with the order and order-line indexes
dropped before the load and created again after it, followed by `VACUUM ANALYZE`:

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

The walk of the primary key assumes the matches are spread over all ids, so it reads most of
the table for a product launched mid-way or lately, and the same statement changed plan with
the statistics sample (product 10 above).

### What changeset 8 changes
It adds `idx_order_customer_id_id` on `"order" (customer_id, id)` and
`idx_order_product_product_id_order_id` on `order_product (product_id, order_id)`, in the
concurrent form of ADR 0009. The customer statements are unchanged. Their count is an index-only
scan of the new index. A large account's first page and cursor read its entries in id order and
stop after the page; a customer with only a few orders has them read through
`idx_order_customer_id` and sorted.

For products the index was not enough by itself. With the index alone, the statements of
`1b05e6b` still merge-joined `order_pkey` from the first id for product 10 (900,199 entries,
10,263 buffers, 83 to 91 ms), because nothing tells the planner that a frequent product's orders
are recent. The cursor's `o1_0.id>?` also stayed on `"order"`, because PostgreSQL carries an
equality across a join but not a range; so the cursor after product 1's median order id still
read its first 146,471 index entries (614 buffers, 10.2 to 10.5 ms). A product page in id order
and the product cursor therefore take the page's order ids from the index first, in a subquery
with its own order, limit and offset, and then read those orders and their customers:

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
before, but no longer walk `order_pkey`, and take at most 0.061 ms; its count reads 5 buffers
instead of 512.

### What the filters still cost
- An offset page reads at least its offset. Customer 2,000's pages walk
  `idx_order_customer_id_id` in id order up to page 16 (47 buffers for page 1, 228 for page 10).
  From page 17 on the planner reads all 1,000 orders through `idx_order_customer_id` and sorts
  them: 1,007 buffers for pages 25 and 49, 0.42 to 0.50 ms for page 49. A generic plan walks
  `idx_order_customer_id_id` instead (529 and 1,011 buffers). On the seed, customer 1's pages
  after the first read all 96 of its orders through a bitmap scan of `idx_order_customer_id_id`
  and sort them (58 buffers, against 15 for its first page). Product 10's page 50 is in the
  table above.
- A count reads every entry of its customer or product: 38 to 39 ms for product 1.
- A filtered page sorted by `description` takes one of two plans. Either it sorts the filtered
  orders (a large account's page: 1,007 buffers, 0.45 to 0.76 ms), or it walks
  `idx_order_description_id` until the page is full. For a product that walk probes
  `idx_order_product_product_id_order_id` once per order walked: product 11 reads about 163,500
  buffers in 35 to 45 ms (the count varies by a few hundred because parallel workers share the
  walk); product 10 reads 404,374 buffers in 111 to 122 ms.
- With the indexes as they were before changeset 8, restored in a rolled-back transaction, the
  same two pages read as many buffers but probe `pk_order_product` instead. That index's entries
  for the orders walked spread over its whole 90 MB, so about a fifth of those buffers missed
  the default 128 MB of shared buffers and were read from the operating system (115 to 143 ms
  and 376 to 409 ms).
- A page in descending id order read at most 164 buffers for every customer and product
  measured. For a product it keeps the join, which could walk `order_pkey` backwards from the
  newest order for a frequently ordered product whose orders all hold old ids, the mirror image
  of product 10; this table has no such product.

### Which indexes stay
`idx_order_customer_id` stays. Without it the planner walks `idx_order_description_id` for a
large account's page sorted by description, because it rates the heap access of a multi-column
index as less ordered than that of a single-column one. Customer 2,000: 18,542 entries, 18,660
buffers, 8.1 to 13.4 ms; customer 200,000: 15,995 entries, 16,098 buffers, 6.2 to 11.2 ms; with
the index, 1,007 and 127 buffers.

`idx_order_product_product_id` is dropped by changeset 8, because the new index serves every
statement that used it. The window query behind the products' nested order ids now reads the new
index index-only: 3,930 buffers instead of 30,702 for the twenty products of the first page,
which include the ten best-sellers.

Migration cost on this table (ADR 0009): the two index builds took 307 to 447 ms and 584 to
736 ms and the drop 4 to 5 ms, over five runs. They were run through the service's Liquibase as
the `migrate` job runs it, with the jar of `1bd1c00` on 2026-10-20, after the three changesets
had been reverted by hand. Times vary with the host and its load.

### What `MigrationIT` pins
`MigrationIT` pins the "after" plans with sequential scans disabled. Unlike the other pins it
does not `EXPLAIN` a hand-written copy. It sends a filtered request, captures the statements from
the `org.hibernate.SQL` logger, binds the request's values and explains those, so a change to
the JPQL or to the native statements that loses the index fails the build. The six tests:
`customerFilteredPageIsReadInIdOrderFromTheCustomerIdIdIndex`,
`customerFilteredCursorSeeksTheCustomerIdIdIndex`, `customerFilteredCountUsesTheCustomerIdIdIndex`,
`productFilteredPageTakesItsOrderIdsFromALimitedScanOfTheProductIdOrderIdIndex`,
`productFilteredCursorSeeksTheProductIdOrderIdIndex` and
`productFilteredCountReadsTheProductIdOrderIdIndexWithoutTheOrderTable`. The pins hold before
and after the suite's seeded tables are analyzed (the customer cursor is a bitmap scan of the
new index and a sort before, an index scan in id order after).

## Caching
Two layers. HTTP revalidation (a weak `ETag` with `Cache-Control: no-cache`) saves response
bytes, not server work; the service cache (Caffeine, by id, 60 s) saves the round trips of
`GET /order/{id}` and `GET /products/{id}`. Both are measured in `HttpCacheIT`, `CompressionIT`,
`QueryCountIT` and `ProductApiIT`; the design and its staleness bounds are in ADR 0007.

| Scenario | Before | After | Why |
|----------|--------|-------|-----|
| `GET /order/1` repeated with the `ETag` of the first response in `If-None-Match` | 200 with the 178 B body on every call | `304 Not Modified`, no body, the ETag echoed | `ShallowEtagHeaderFilter` on `/order`, `/order/*`, `/customer`, `/customer/*`, `/products`, `/products/*`; the ETag is weak so Tomcat still compresses, and `Cache-Control: no-cache` makes caches revalidate instead of serving stale bodies |
| `GET /order?size=100` with `Accept-Encoding: gzip` | gzipped, under 4 KB | gzipped, under 4 KB, plus the ETag; a revalidation sends 0 bytes of body | Weak ETags are not excluded from compression |
| `GET /order/1` twice, `GET /products/2` twice | 1 (order) or 2 (product) statements and 1 session per call | second call: 0 statements, 0 sessions (`getPrepareStatementCount()`, `getSessionOpenCount()`) | `@Cacheable` on `findById`; `@EnableCaching(order = HIGHEST_PRECEDENCE)` places the cache advice outside the transaction advice, so a hit never enters the transactional method |
| `GET /products/{id}` after `POST /order` containing that product | n/a | the next read lists the new order (2 statements, then cached again) | `OrderCreatedEvent` → `ProductCacheEvictor`, `AFTER_COMMIT` |

The "Before" column is the same request without the layer in question, at today's sizes and
cold-read costs. Rows 1 and 2 are what every call returned before `1a835f9` (#36) added the
ETag filter; row 2's bound is the one `CompressionIT` asserts. Row 3 is the cold read that
`QueryCountIT` pins (`orderByIdUsesOneStatement`,
`productByIdUsesTwoStatementsAndLoadsNoEntities`), which every call costs with
`CACHE_TYPE=none`.

- The ETag filter is shallow: the controller and the service still run on a conditional
  request, so a 304 saves response bytes, not server work. It also buffers the whole response
  body in memory to hash it before the first byte is written, on every `GET` it covers, whether
  or not the client sent `If-None-Match`. That is one extra copy of the body per request, at
  most the 106 KB of `GET /customer?size=100`, accepted because the pages are bounded, so the
  copy is too, and the hash is what makes the 304 possible.
- The cache keeps immutable DTOs (each record copies its lists when it is built, so every hit
  can share one instance), keyed by id, bounded to 10,000 entries and 60 s (`CACHE_TTL`);
  `CACHE_TYPE=none` disables it.
- The hit rate is `cache_gets_total{cache="orders"|"products",result="hit"|"miss"}` in
  `/actuator/prometheus`. The TTL should only be raised on that evidence, because a product can
  be stale for up to the TTL in three cases: it is read on another replica; a write bypasses the
  service; or a read that missed the cache before an order's commit stores the old value after
  the eviction.

## Write path
Ids come from sequences in blocks of 50, so inserts are deferred to flush and batched.
`POST /order` with three products went from 6 prepared statements to 4 (5 on the request that
allocates the next block), and an order with 100 products costs the same.

A `POST` sent with an `Idempotency-Key` (ADR 0010) runs two statements more: the claim of the key
before the create and, in the create's transaction, the completion that stores the response.
`QueryCountIT` pins a keyed `POST /customer` or `POST /products` at 3 prepared statements and a
keyed `POST /order` with three products at 6, each plus the block allocation; a replay or a 422 at
2 (the claim and the read of the stored row) with no entity loaded; an unkeyed `POST` is unchanged.

`getPrepareStatementCount()` counts prepared statements; PgJDBC pipelines a JDBC batch and, with
`reWriteBatchedInserts=true`, sends it as one multi-row `INSERT`. "After" is pinned by
`QueryCountIT` and `InsertBatchingIT`; "before" was counted on 2026-10-19 on `6163c42`, the
parent of `8bc8bfc` (#33) that introduced the sequences and the batching, with the same two
scenarios, with Hibernate statistics, in a fresh Testcontainers database.

| Scenario | Prepared statements before | Prepared statements after | Why |
|----------|----------------------------|---------------------------|-----|
| `POST /order` with three products | 6 (customer + products lookups, 1 order insert, 3 join-table inserts) | 4 (5 on the request that allocates the next block of 50 ids) | `jdbc.batch_size` turns the three `order_product` inserts into one batched statement |
| Three orders with three products each in one flush | 12 (3 order inserts + 9 join-table inserts) | 2 (3 on the flush that allocates the block): 1 batched order insert, 1 batched join-table insert | Ids come from the sequence block, so the inserts are deferred and batched per table |

- `QueryCountIT` asserts 4 to 5 and `InsertBatchingIT` 2 to 3: the suite creates well under 50
  orders per JVM, so exactly one request or flush per run pays the `nextval`, and which one it
  is depends on test order. With `jdbc.batch_size: 1` the two counts rise to 7 and 12.
- The API inserts one order per request today, so the second row matters for future bulk paths
  (an import endpoint, a migration job) rather than for the current endpoints. It is also the
  test that proves the sequence switch, because `IDENTITY` would issue three separate order
  inserts regardless of the batch size.
- An order with the maximum of 100 products has 100 join rows, which exceed
  `jdbc.batch_size: 50`, so Hibernate executes the one prepared join-row insert as two JDBC
  batches of 50 (`org.hibernate.orm.jdbc.batch` at `TRACE` shows `Executing JDBC batch (50 / 50)`
  twice for `Order.products`; 2026-10-19, the jar of `173f958` on the seed). The request stays at
  4 prepared statements, 5 when it allocates the block
  (`QueryCountIT.createOrderWithOneHundredProductsUsesFourStatementsPlusTheBlockAllocation`). The
  pin does not see how many batches there are: it still passes with `jdbc.batch_size: 100`, one
  batch, and fails at 103 with `jdbc.batch_size: 1`, which prepares every join row on its own.

## Deliberately not done
- **Caching the list pages.** The by-id reads are cached (above); the pages are not. A page key
  would have to include page, size, sort, cursor and the `name`, `customerId` and `productId`
  filters. Every `POST /order`, `POST /customer` or `POST /products` changes the totals of at
  least one list, so the entries would be invalidated as fast as they are filled. The lists are
  already at most 2 or 3 statements, and the HTTP revalidation covers the client that polls the
  same page.
