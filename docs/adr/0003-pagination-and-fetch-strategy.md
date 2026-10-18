# ADR 0003: Pagination and fetch strategy for list endpoints

## Status
Accepted; the fetch strategy for list pages is superseded by ADR 0006 (constructor projections
replace the entity graph and the fetch-join queries; the statement counts are unchanged).
Amended with the "Keyset pagination" section below.

## Context
`GET /order` returned every order (10,000 rows, ~950 KB) and `GET /customer` returned every
customer with every nested order (~500 KB). Each request also issued 11 database round trips:
one for the root entities and ten batch fetches of size 10 for the lazy associations. The
production database is not co-located with the service, so round trips dominate latency.

## Decision
- Both list endpoints are paged. The response is a `PageResponse` envelope
  (`content`, `page`, `size`, `totalElements`, `totalPages`). The default page size is 20,
  the maximum is 100 (larger values are capped, not rejected), and the default sort is `id`
  so pages are stable under `LIMIT/OFFSET`.
- `GET /order` joins the customer in the page query. A to-one join does not multiply rows, so
  the database applies the limit; the products of the page are loaded with one further
  `where id in (...)` query: 3 statements per request (page + count + products).
- `GET /customer` pages the customers first and then loads the orders of exactly those
  customers with one `left join fetch ... where id in (...)` query: 3 statements per request.
  Fetch-joining a collection together with `Pageable` is deliberately avoided because Hibernate
  would page in memory after loading every row (`HHH90003004`).
- Open-in-view is disabled. Services are transactional and return DTOs, so no lazy loading can
  happen outside a transaction.
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
depth (pinned by `QueryCountIT`).

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
