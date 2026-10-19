# ADR 0006: Constructor projections for list pages and a sort whitelist

## Status
Accepted; amended by ADR 0003, "Nested collections": `GET /products/{id}` now builds its response from
the product row and the bounded order-id window, so MapStruct maps entities to DTOs for
`GET /order/{id}` and the create paths only.

## Context
The list endpoints paged entities: `GET /order` loaded the orders with their customers through
an entity graph and then fetch-joined the products of the page, `GET /customer` and
`GET /products` paged the root entities and fetch-joined their children. The statement count was
already fixed at 3, but every row became a managed entity with a collection wrapper, hydrated
columns the response never used and an entry in the persistence context (on a fresh seed a
100-row order page loaded 263 entities and a 50-row customer page 4,996; `docs/PERFORMANCE.md`,
"Hydration").

The paged queries were also the only place where a client-supplied `sort` reached Hibernate
unchecked. Spring Data validates sort properties against the entity for derived queries, which
turned `sort=nope` into a 400, but it also accepted any mapped path, so `sort=customer.name`
ordered through an implicit, unindexed join. With the projection queries written in JPQL,
Spring Data no longer validates the property at all: an unknown one becomes `order by o.nope`,
a Hibernate error and a 500.

## Decision
- Each list endpoint runs a JPQL constructor expression (`select new ...Row(...)`) for the page
  with an explicit `countQuery`, and one more constructor-expression query for the children of
  exactly the ids on the page (`where id in (...) order by parent, child`). The records live in
  `repository.view`; the services group the child rows by parent id in a `LinkedHashMap` and
  build the response DTOs directly. MapStruct remains the entity-to-DTO mapper for the by-id and
  create paths only.
- The service layer checks `sort` against a per-endpoint `SortPolicy` before any query runs:
  orders and products accept `id` and `description`, customers `id` and `name`. Only
  `Sort.Order.getProperty()` is compared, so both directions are allowed. Any other property
  raises `InvalidSortPropertyException`, which `GlobalExceptionHandler` maps to a 400 problem
  detail with `Unknown sort property: <property>`. The handler for Spring Data's
  `PropertyReferenceException` is gone because nothing throws it any more.
- After validation the policy appends `id` as a tie-break in the direction of the last client
  property unless the request is unsorted or already names `id`, so equal values never leave
  the boundary between two offset pages to the planner.
- `QueryCountIT` pins `getEntityLoadCount() == 0` and the statement count of 3 for each list
  endpoint on the same URLs as before; `OrderApiIT`, `CustomerApiIT` and the Cucumber scenarios
  pin the 400 for `customer.name` and `nope`.

## Consequences
- This narrows the contract: sorting by a nested path was never documented, never indexed and
  is now rejected rather than silently joined. `OpenAPI.yaml` lists the accepted properties per
  endpoint and the example 400 body; that list is the documented contract. A whitelisted sort
  property is an indexed one (changeset 7: `(description, id)` on `order` and `product`,
  `(name, id)` on `customer`, pinned by `MigrationIT`), and ties are broken by `id` in the same
  direction, so adding a property means adding it to the policy, the specification and an index
  whose trailing column is `id` (`docs/PERFORMANCE.md`, "Sorted pages").
- Round trips are unchanged; the saving is service-side CPU and allocation. There is no
  dirty-check saving to claim because the read transactions were already read-only.
- A new column in a list response is a change to the row record and the JPQL, not to the
  entity mapping, which keeps the entity graph of the by-id endpoints independent of the lists.
