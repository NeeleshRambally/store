# ADR 0001: Layered architecture with request and response DTOs

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record explains why the service is split into controllers, services and repositories, and why
requests and responses are records rather than JPA entities. The defects it fixed are listed in
[Defects found in the original code](../ASSUMPTIONS.md#defects-found-in-the-original-code-and-what-was-done),
and the error contract it set up is in [Errors](../DESIGN.md#errors).

## Status
Accepted; amended by [ADR 0006](0006-list-projections-and-sort-whitelist.md) and
[ADR 0003, Nested collections](0003-pagination-and-fetch-strategy.md#nested-collections), which
move every read except `GET /order/{id}` from MapStruct to query rows.

## Context
The original service wired its controllers straight to Spring Data repositories and bound JPA
entities from the request body. That design had four effects:
- A client could set or overwrite any primary key: `POST /customer` with `{"id": 5}` updated
  row 5.
- A nested `orders` array cascaded into new rows.
- A database constraint failure surfaced as an HTTP 500.
- The controllers mapped entities to DTOs after the repository call had returned, so the mapping
  loaded lazy associations outside any transaction. That worked only because of open-in-view
  (Spring keeping the JPA session open for the whole request).

## Decision
- **Three layers.** `controller` (HTTP concerns only) → `service` (use cases, transactions) →
  `repository` (Spring Data JPA). Each service is an interface (`CustomerService`,
  `OrderService`, `ProductService`), so a controller depends on an abstraction and its slice test
  mocks it. Since [ADR 0010](0010-idempotency-keys.md) the slices also mock `IdempotentRequests`.
- **Request records.** `CreateCustomerRequest`, `CreateOrderRequest` and `CreateProductRequest`
  are validated records with no id field. Unknown JSON properties are rejected
  (`spring.jackson.deserialization.fail-on-unknown-properties`), so a client that sends an id gets
  a 400 instead of changing data. The other body rules are in
  [Strict input](../DESIGN.md#strict-input).
- **Response records.** Responses are immutable records (`CustomerDTO`, `OrderDTO`, `ProductDTO`
  and their nested records). Entities never cross the controller boundary. Nested objects stop one
  level deep, so serialisation has no cycles.
- **Entities.** Each entity is built by a constructor that takes its required fields:
  `Customer(name)`, `Product(description)` and `Order(description, customer)`. The no-argument
  constructor is protected and exists for JPA. No entity has a setter, and Hibernate assigns the
  id. Equality is by id, the hash code is constant and `toString` prints the id only.
- **No cascade.** The cascade from customer to orders is removed. Only the order service writes
  orders.
- **Mapping.** MapStruct maps entity → DTO only. Today it has four call sites: the create method
  of each service, and `GET /order/{id}`. Every other read builds its DTOs from query rows.
  `OrderMapper` runs on `POST /order` and on `GET /order/{id}`, whose entity graph fetches the
  customer and the products. It maps the customer's id and name and each product's id and
  description, and reads no collection of orders.
- **One error handler.** A single `@RestControllerAdvice`, `GlobalExceptionHandler`, turns errors
  into RFC 7807 problem details (the standard JSON error body), each with a stable `code`.
  Validation is a 400 with per-field `errors`, an unknown resource a 404, a data integrity failure
  a 409, and anything unexpected a 500 without internal detail. The full list, with the 503s of a
  lost database, is in [Errors](../DESIGN.md#errors).

## Consequences
- Request and response shapes are explicit contracts, documented in `OpenAPI.yaml` and enforced by
  [the contract check](../DESIGN.md#the-contract-check).
- Adding an endpoint means a request record, a service method, a controller method and tests at
  each layer. Each class stays small and has one purpose.
- `CustomerMapper` and `ProductMapper` must never map an entity loaded from the database, because
  their `orders` mapping would read its lazy collection, which is unbounded
  ([ADR 0003, Nested collections](0003-pagination-and-fetch-strategy.md#nested-collections)). Each
  has one caller, the create method of its service. That method maps the entity it has just created
  and saved, whose collection is still the empty one its constructor made.
- The call sites hold that rule, not the type system: a new entity and a loaded one have the same
  type. A separate type for unsaved entities would model the same table twice for two call sites.
- `QueryCountIT` guards the rule. It pins zero entity loads on every list and cursor page and on
  the by-id reads of a customer and a product. A read that starts mapping a loaded entity
  therefore fails the build. It also pins one statement on `GET /order/{id}`, so that mapping
  cannot reach a lazy collection unnoticed.
- A hexagonal (ports and adapters) layout was considered and rejected for now. With three
  aggregates and one persistence technology, the extra indirection would not pay for itself.
