# ADR 0003: Pagination and fetch strategy for list endpoints

## Status
Accepted; the fetch strategy for list pages is superseded by ADR 0006 (constructor projections
replace the entity graph and the fetch-join queries; the statement counts are unchanged).
Amended with the "Keyset pagination" and "Nested collections" sections below; the `customerId`
and `productId` filters of `GET /order` (below, under Decision) apply in both paging modes.

## Context
`GET /order` returned every order (10,000 rows, ~950 KB) and `GET /customer` returned every
customer with every nested order (~500 KB). Each request also issued 11 statements, each a
database round trip: one for the root entities and ten batch fetches of size 10 for the lazy
associations. The production database is not co-located with the service, so round trips
dominate latency.

## Decision
- Both list endpoints are paged. The response is a `PageResponse` envelope
  (`content`, `page`, `size`, `totalElements`, `totalPages`). The default page size is 20,
  the maximum is 100 (larger values are capped, not rejected), and the default sort is `id`
  so pages are stable under `LIMIT/OFFSET`.
- `GET /order` joins the customer in the page query. A to-one join does not multiply rows, so
  the database applies the limit; the products of the page are loaded with one further
  `where id in (...)` query: 3 statements for a full page (page + count + products).
- `GET /customer` pages the customers first and then loads the orders of exactly those
  customers with one `left join fetch ... where id in (...)` query: 3 statements for a full
  page.
- The two child queries above are the original shape; today's are the native window query of
  "Nested collections" below over the row projections of ADR 0006, at the same counts.
- 3 is the full-page cost and the maximum: Spring Data skips the count query when the first
  page is shorter than `size`, and the services skip the child query when the page is empty, so
  a short first page costs 2 and an empty one 1 (both pinned by `QueryCountIT`).
  Fetch-joining a collection together with `Pageable` is deliberately avoided because Hibernate
  would page in memory after loading every row (`HHH90003004`).
- Open-in-view is disabled. Services are transactional and return DTOs, so no lazy loading can
  happen outside a transaction.
- `GET /order` accepts `customerId` or `productId` (not both: 400) and then pages the orders of
  that customer, or the orders containing that product, with the same page, count and products
  queries restricted by the indexed foreign key (`idx_order_customer_id`,
  `idx_order_product_product_id`). An id that matches nothing is an empty page, not a 404. This
  is the uncapped list of a customer's or a product's orders; the nested `orders` of the
  customer and product responses can therefore be bounded without losing any data path.
- An integration test pins the statement counts, so a reintroduced N+1 fails the build.

## Consequences
- The list endpoints are a breaking change for clients that expected a bare array; the
  OpenAPI contract is updated accordingly.
- `default_batch_fetch_size` is raised to 100 as a safety net but no endpoint relies on it.

## Keyset pagination
`LIMIT/OFFSET` reads and discards every row before the requested page, so the cost of a page
grows with its depth, and every page also pays the count query. The list endpoints therefore
accept a second addressing mode: `after=<id>` returns the elements whose id is greater than
`after`, in ascending id order, as a `CursorPageResponse` (`content`, `size`, `nextCursor`).
The repository query is `where id > :after order by id` with a `Limit` of `size + 1`; the
service trims the extra row, which tells it whether more rows exist, before loading the
children of the page, and sets `nextCursor` to the id of the last returned element or `null`
when the walk is complete. The count query is skipped, so a cursor page is 2 statements at any
depth (pinned by `QueryCountIT`), 1 when it is empty (also pinned).

- The cursor is deliberately transparent: it is the id itself, not an opaque token. Clients can
  start at `after=0`, resume after a known id, and read the cursor in logs and tests. The price
  is that the cursor is only meaningful for the default `id` ascending order, so `after`
  combined with `page` other than 0, or with a `sort` whose first order is not `id` ascending,
  is rejected with a 400 problem detail (`after cannot be combined with page or sort`) rather
  than silently returning something else.
- If other sort keys are ever offered in cursor mode, the migration path is an opaque cursor
  that encodes the sort key and the tie-breaking id (and, if needed, the direction); the `after`
  parameter and the envelope stay, only the cursor's content changes.
- Offset pagination remains available unchanged for clients that need `totalElements` or random
  access to a page; the two modes share the row projections, the child queries and the DTOs.

## Nested collections
Paging bounded the rows per request but not what each row carried. The original `CustomerDTO`
embedded every order of the customer and the original `ProductDTO` every order id containing
the product, so a customer page grew with the orders per customer (the default page of 20
customers carried 1,941 orders on the seed) and a product with the orders per product (about
200 ids each), without any bound a client or an operator could rely on.

From this release the nested `orders` of a customer, on every list response, are its newest
`store.nested-orders.limit` orders (environment variable `NESTED_ORDERS_LIMIT`, default 20,
validated to 1..100 at start-up), where newest means the highest ids, returned in ascending id
order like every other list; `orderCount` carries the total. A product on the list and by-id
responses carries the ids of its newest `limit` orders the same way, plus `orderCount`; the
by-id customer read (`GET /customer/{id}`) applies the same bound and the same window query,
so a customer looks the same whether read alone or on a page. The
child query is a native window query: `row_number() over (partition by customer_id order by id
desc)` filtered to `<= :max` and `count(*) over (partition by customer_id)`, over the ids of the
page (the same over `order_product` for products), so the newest rows and the total of every
parent on the page still cost one statement and the per-request counts are unchanged (3 per
page, 2 per cursor page, pinned by `QueryCountIT`). `GET /products/{id}` reads the product row
and that window instead of the entity graph: 2 statements, no entity, cached and evicted as
before.

- The README's "a customer has 0 or more orders" and the products task's "list of the order
  IDs" are delivered in bounded form: the newest `NESTED_ORDERS_LIMIT` ids (raisable to 100)
  plus `orderCount` on every response. That deviates from the literal wording for performance
  reasons, and nothing is lost because the complete lists are `GET /order?customerId={id}` and
  `GET /order?productId={id}`, paged, filtered on the indexed foreign keys and added for
  exactly this purpose before the bound was introduced.
- Newest rather than oldest because a bounded list that never changes once full tells an
  operator nothing; the newest orders plus the total answer "is this customer active and how
  much" directly, and the ascending order inside the list keeps the response shape of every
  other list.
- The limit is configuration, not a request parameter, so a client cannot raise the cost of a
  page; raising it above 100 is refused at start-up (`@Validated`, `@Max(100)`).
- What a response is bounded by is now exact: rows per page (`size`, at most 100), orders per
  customer and order ids per product (`NESTED_ORDERS_LIMIT`, 1 to 100, default 20) and products
  per order (at most 100 at creation). Nothing in a response grows with the tables; the measured
  payloads are in `docs/PERFORMANCE.md`.
- `orderCount` is required in `OpenAPI.yaml` for `Customer` and `Product` and the `orders`
  descriptions state the bound and the full-list endpoint; a client that needs every order
  follows the link rather than the nested list.
