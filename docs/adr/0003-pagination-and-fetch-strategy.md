# ADR 0003: Pagination and fetch strategy for list endpoints

## Status
Accepted; the fetch strategy for list pages is superseded by ADR 0006 (constructor projections
replace the entity graph and the fetch-join queries; the statement counts are unchanged).

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
