# ADR 0006: Constructor projections for list pages and a sort whitelist

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record explains why list pages select plain rows instead of loading entities, and why `sort`
accepts only a fixed list of properties. The entity counts are in
[Hydration](../PERFORMANCE.md#hydration) and the plans of sorted pages in
[Sorted pages](../PERFORMANCE.md#sorted-pages).

## Status
Accepted. Supersedes the list-page fetch strategy of
[ADR 0003](0003-pagination-and-fetch-strategy.md), at the same statement counts. Amended by
[ADR 0003, Nested collections](0003-pagination-and-fetch-strategy.md#nested-collections), which
moved the customer and product child queries to a window query and `GET /products/{id}` to rows.

## Context
The list endpoints paged entities. `GET /order` loaded the orders with their customers through an
entity graph, then fetch-joined the products of the page. `GET /customer` and `GET /products`
paged the root entities and fetch-joined their children. The statement count was already fixed
at 3. But every row became a managed entity, with a collection wrapper, columns the response
never used and an entry in the persistence context. On a fresh seed a 100-row order page loaded
263 entities and a 50-row customer page 4,996 ([Hydration](../PERFORMANCE.md#hydration)).

The paged queries were also where a client-supplied `sort` reached Hibernate. As derived queries,
they had Spring Data check each sort property against the entity, so `sort=nope` was a 400. But
the check accepted any mapped path, so `sort=customer.name` ordered through an implicit,
unindexed join. The JPQL queries of this decision get no such check: an unknown property would
become `order by o.nope`, a Hibernate error and a 500.

## Decision
- **Rows, not entities.** A list page selects columns into plain records, which live in
  `repository.view`, instead of loading entities. The page query is a JPQL constructor expression
  (`select new ...Row(...)`, which builds one record per row) with an explicit `countQuery`. A
  cursor page runs the same kind of query without a count.
- **One child query per page,** for exactly the ids on the page, in the shape
  `where id in (...) order by parent, child`. For orders it is a constructor expression too. For
  customers and products it is the native window query of
  [ADR 0003, Nested collections](0003-pagination-and-fetch-strategy.md#nested-collections),
  mapped to the same kind of record. The services group the child rows by parent id in a
  `LinkedHashMap` and build the response DTOs directly.
- **Native where JPQL cannot reach.** The `productId` filter's page in id order and its cursor are
  native queries that return the same `OrderRow`, because JPQL reaches `order_product.order_id`
  only through a join to `"order"`. That page's count is a separate JPQL query
  ([What changeset 8 changes](../PERFORMANCE.md#what-changeset-8-changes)).
- **MapStruct** remains the entity-to-DTO mapper for `GET /order/{id}` and the create paths only.
- **Sort whitelist.** Before any query runs, the service checks `sort` against a per-endpoint
  `SortPolicy`: orders and products accept `id` and `description`, customers `id` and `name`.
  Only `Sort.Order.getProperty()` is compared, so both directions are allowed. Any other property
  raises `InvalidSortPropertyException`, which `GlobalExceptionHandler` maps to a 400 problem
  detail (`invalid_sort`, `Unknown sort property: <property>`). The handler for Spring Data's
  `PropertyReferenceException` is gone, because nothing throws it any more.
- **Tie-break.** After validation the policy appends `id`, in the direction of the last client
  property, unless the request is unsorted or already names `id`. Equal values then never leave
  the boundary between two offset pages to the planner.
- **Tests.** `QueryCountIT` pins `getEntityLoadCount() == 0` and 3 statements for each list
  endpoint, on the URLs of the [Hydration](../PERFORMANCE.md#hydration) table. `OrderApiIT` and a
  scenario in `order.feature` pin the 400 for `customer.name`; `CustomerApiIT` and a scenario in
  `customer.feature` pin it for `nope`.

## Consequences
- **A narrower contract.** Sorting by a nested path was never documented and never indexed, and
  is now rejected rather than silently joined. `OpenAPI.yaml` lists the accepted properties per
  endpoint and the example 400 body (`unknownSortProperty`); that list is the documented contract.
- **Every whitelisted property is indexed.** Changeset 7 adds `(description, id)` on `order` and
  `product` and `(name, id)` on `customer`, pinned by `MigrationIT`, and ties are broken by `id`
  in the same direction. Adding a property therefore means adding it to the policy, the
  specification and an index whose trailing column is `id`
  ([Sorted pages](../PERFORMANCE.md#sorted-pages)).
- **CPU, not round trips.** Round trips are unchanged; the saving is service-side CPU and
  allocation. There is no dirty-check saving to claim, because the read transactions were already
  read-only.
- **Lists change on their own.** A new column in a list response is a change to the row record and
  its query, not to the entity mapping. That keeps the entity graph of `GET /order/{id}`
  independent of the lists.
