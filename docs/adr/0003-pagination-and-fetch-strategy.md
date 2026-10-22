# ADR 0003: Pagination and fetch strategy for list endpoints

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record covers how the list endpoints page their results, what a page costs in statements,
the cursor mode and the bound on nested collections. The rules a client sees are in
[Paging, sorting and filters](../DESIGN.md#paging-sorting-and-filters), and the measurements in
[Performance](../PERFORMANCE.md).

## Status
Accepted; amended by [Keyset pagination](#keyset-pagination) and
[Nested collections](#nested-collections) below, and superseded for the fetch strategy of list
pages by [ADR 0006](0006-list-projections-and-sort-whitelist.md), at the same statement counts.

## Context
`GET /order` returned every order (10,000 rows, ~950 KB), and `GET /customer` every customer with
every nested order (~500 KB). Each request also ran 11 statements, each a database round trip:
one for the root entities and ten batch fetches of size 10 for the lazy associations. The
production database is not co-located with the service, so round trips dominate latency
([Measurements on the original code](../PERFORMANCE.md#measurements-on-the-original-code)).

## Decision
- **Every list is paged.** `GET /order`, `GET /customer` and, since products were added,
  `GET /products` answer with a `PageResponse` envelope (`content`, `page`, `size`,
  `totalElements`, `totalPages`). The default page size is 20 and the maximum 100 (larger values
  are capped, not rejected). The default sort is `id`, so pages are stable under `LIMIT/OFFSET`.
- **The page index is bounded.** `page` is at most 21474836; a larger one is a 400. That is the
  largest page whose offset fits the `int` that JPA's `setFirstResult` takes at size 100.
  Positions further on are reached with the cursor of [Keyset pagination](#keyset-pagination).
- **Three statements per full page:** the page, its count, and one child query for exactly the
  rows on the page.
  - `GET /order` joins the customer in the page query. A to-one join does not multiply rows, so
    the database applies the limit. The products of the page come from one more
    `where id in (...)` query.
  - `GET /customer` pages the customers first, then loads the orders of exactly those customers
    in one query. That query was a `left join fetch ... where id in (...)`; today it is the window
    query of [Nested collections](#nested-collections).
  - `GET /products` loads the order ids of its products with the same window query.
  - Since [ADR 0006](0006-list-projections-and-sort-whitelist.md) these queries select rows, not
    entities.
- **No collection fetch join with paging.** Fetch-joining a collection together with `Pageable`
  is avoided, because Hibernate would then page in memory after loading every row
  (`HHH90003004`).
- **3 is the maximum.** Spring Data skips the count query when a page is shorter than `size`,
  unless it is an empty page after the first, whose total it cannot derive. The services skip the
  child query when the page is empty. So a short first page costs 2, an empty first page 1 and an
  empty page beyond the end 2 (all three pinned by `QueryCountIT`; every case is in
  [Results](../PERFORMANCE.md#results)).
- **Open-in-view is disabled** (`spring.jpa.open-in-view: false`). Services are transactional and
  return DTOs, so nothing can load lazily outside a transaction.
- **Filters.** `GET /order` accepts `customerId` or `productId` (each at least 1; both together
  are a 400), in both paging modes. It then pages the orders of that customer, or the orders
  containing that product, with the same page, count and products queries restricted to that id.
  An id that matches nothing is an empty page, not a 404.
- **Filter indexes.** Each filter is served by an index that leads with the filtered id and ends
  with the order id (changeset 8). A product's page in id order and its cursor take their order ids
  from `idx_order_product_product_id_order_id` before reading those orders. A customer's count reads
  `idx_order_customer_id_id`, and so do the first page and the cursor of a customer with many
  orders, in id order. A cursor page therefore reads only the entries it returns, at any depth. A
  customer with only a few orders has all of them read and sorted, and so does a large customer's
  deep offset page. The plans are in [Filtered pages](../PERFORMANCE.md#filtered-pages).
- **The filters are the complete lists.** They are the uncapped list of a customer's or a
  product's orders. So the nested `orders` of the customer and product responses can be bounded
  without losing any data path.
- **The counts are pinned.** `QueryCountIT` pins the statement counts, so a reintroduced N+1 (one
  query for the list, then one per row) fails the build.

## Consequences
- The paged lists broke clients that expected a bare array. `OpenAPI.yaml` was updated to match
  ([Contract version and client migration](../ASSUMPTIONS.md#contract-version-and-client-migration)).
- `default_batch_fetch_size` is raised to 100 as a safety net, but no endpoint relies on it.

## Keyset pagination
An offset page (`LIMIT/OFFSET`) reads and discards every row before it, so its cost grows with its
depth, and every page also pays the count query. The list endpoints therefore accept a second
mode, keyset pagination: the client pages by the last id it has seen instead of by page number.

- **Request and response.** `after=<id>` (at least 0) returns the elements whose id is greater
  than `after`, in ascending id order, as a `CursorPageResponse` (`content`, `size`,
  `nextCursor`).
- **Query.** The repository query is `where id > :after order by id` with a `Limit` of
  `size + 1`. The service trims the extra row, which tells it whether more rows exist, before it
  loads the children of the page. `nextCursor` is the id of the last returned element, or `null`
  when the walk is complete.
- **Cost.** The count query is skipped, so a cursor page is 2 statements at any depth and 1 when
  it is empty (both pinned by `QueryCountIT`). The plans are in
  [Deep pages](../PERFORMANCE.md#deep-pages).
- **A transparent cursor.** The cursor is the id itself, not an opaque token. Clients can start at
  `after=0`, resume after a known id, and read the cursor in logs and tests. The price is that the
  cursor means something only for the default `id` ascending order. So `after` combined with a
  `page` other than 0, or with a `sort` whose first order is not `id` ascending, is rejected
  rather than answered differently. The answer is a 400 problem detail with the code
  `invalid_cursor` and the detail `after cannot be combined with page or sort`.
- **Migration path.** If other sort keys are ever offered in cursor mode, the cursor becomes an
  opaque token that encodes the sort key and the tie-breaking id (and, if needed, the direction).
  The `after` parameter and the envelope stay; only the cursor's content changes.
- **Offset paging stays** unchanged for clients that need `totalElements` or random access to a
  page. The two modes share the row projections, the child queries and the DTOs.

## Nested collections
Paging bounded the rows per request but not what each row carried. The original `CustomerDTO`
embedded every order of the customer, and the original `ProductDTO` the id of every order
containing the product. So a customer page grew with the orders per customer: the default page of
20 customers carried 1,941 orders on the seed. A product grew with the orders per product, about
200 ids each. There was no bound that a client or an operator could rely on.

- **The bound.** On every list response a customer carries its newest
  `store.nested-orders.limit` orders (environment variable `NESTED_ORDERS_LIMIT`, default 20,
  validated to 1..100 at start-up). Newest means the highest ids. They are returned in ascending id
  order like every other list, and `orderCount` carries the total.
- **Products and single customers too.** A product on the list and by-id responses carries the
  ids of its newest orders the same way, plus `orderCount`. `GET /customer/{id}` applies the same
  bound and the same window query, so a customer looks the same read alone or on a page.
- **One window query per page.** A window query ranks rows within groups without collapsing them.
  The child query is a native one over the ids of the page:
  `row_number() over (partition by customer_id order by id desc)` filtered to `<= :max`, and
  `count(*) over (partition by customer_id)` (the same over `order_product` for products). So the
  newest rows and the total of every parent on the page cost one statement, and the counts are
  unchanged: 3 per page and 2 per cursor page (pinned by `QueryCountIT`).
- **`GET /products/{id}`** reads the product row and that window instead of the entity graph: 2
  statements, no entity, cached and evicted as before ([ADR 0007](0007-caching.md)).
- **The README in bounded form.** The task description in the [README](../../README.md) says a
  customer has "0 or more orders" and asks for "a list of the order IDs" of each product. Both are
  delivered in bounded form: the newest `NESTED_ORDERS_LIMIT` entries (raisable to 100) plus
  `orderCount`, on every response. That deviates from the literal wording for performance.
  Nothing is lost: the complete lists are `GET /order?customerId={id}` and
  `GET /order?productId={id}`, paged, filtered on the indexed foreign keys and added for exactly
  this purpose before the bound was introduced.
- **Newest rather than oldest.** A bounded list that never changes once full tells an operator
  nothing. The newest orders plus the total answer "is this customer active and how much"
  directly, and the ascending order inside the list keeps the shape of every other list.
- **Configuration, not a request parameter,** so a client cannot raise the cost of a page. A limit
  above 100 is refused at start-up (`@Validated`, `@Max(100)` on `NestedOrdersProperties`).
- **Every response is bounded:** rows per page (`size`, at most 100), orders per customer and
  order ids per product (`NESTED_ORDERS_LIMIT`, 1 to 100, default 20) and products per order (at
  most 100 at creation). Nothing in a response grows with the tables; the measured payloads are in
  [Nested collections](../PERFORMANCE.md#nested-collections) and
  [Results](../PERFORMANCE.md#results).
- **In the contract.** `orderCount` is required in `OpenAPI.yaml` for `Customer` and `Product`,
  and the `orders` descriptions state the bound and the full-list endpoint. A client that needs
  every order follows that endpoint rather than the nested list.
- **Known limit.** `orderCount` counts every order of each parent on the page, so a parent with a
  very large number of orders makes each page it appears on read all of them. The measured case
  and the remedy, a cached or approximate `orderCount`, are in
  [Nested collections](../PERFORMANCE.md#nested-collections).
