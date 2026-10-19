# ADR 0001: Layered architecture with request and response DTOs

## Status
Accepted. Since ADR 0006 every list and by-id read except `GET /order/{id}` builds its DTOs
from projection rows rather than through MapStruct; the entity mappers serve the create
responses and that one entity-graph read only. Entities are built by their constructors, which
take the required fields (`Customer(name)`, `Product(description)` and
`Order(description, customer)`); the no-argument constructor is protected and exists for JPA,
no entity has a setter, and Hibernate assigns the id. MapStruct maps entity → DTO only.

The `orders` mapping of `CustomerMapper` and `ProductMapper` must never be applied to a
persistent entity, whose lazy collection is unbounded (ADR 0003, "Nested collections"). The
call sites hold that rule, not the type system: a new entity and a loaded one have the same
type, and a separate type for unsaved entities would model the same table twice for two call
sites. Each of the two mappings has one caller, the create method of its service, which maps
the entity it has just saved, whose collection is still the empty one its constructor made.
`OrderMapper` runs on `POST /order` and on `GET /order/{id}`, whose entity graph fetches the
customer and the products; it maps the customer's id and name and each product's id and
description, and reads no collection of orders. Every other read builds its DTOs from rows.
`QueryCountIT` pins zero entity loads on every list and cursor page and on the by-id reads of
a customer and a product, so a read that started mapping a loaded entity fails the build, and
one statement on `GET /order/{id}`, so its mapping cannot reach a lazy collection unnoticed.

## Context
The original service wired controllers directly to Spring Data repositories and bound JPA
entities from the request body. That design let a client set or overwrite any primary key
(`POST /customer` with `{"id": 5}` updated row 5), let a nested `orders` array cascade into new
rows, surfaced database constraint failures as HTTP 500s, and relied on open-in-view for lazy
loading during JSON serialisation.

## Decision
- A layered structure: `controller` (HTTP concerns only) → `service` (use cases, transactions)
  → `repository` (Spring Data JPA). Services are defined by interfaces so controllers depend on
  abstractions and unit tests can mock a single collaborator.
- Request bodies are validated records (`CreateCustomerRequest`, `CreateOrderRequest`) with no
  id field. Unknown JSON properties are rejected (`fail-on-unknown-properties`), so a client
  supplying an id receives 400 rather than silently mutating data.
- Responses are immutable records mapped by MapStruct. Entities never cross the controller
  boundary, and the entity graph is truncated one level deep to avoid serialisation cycles.
- A single `@RestControllerAdvice` maps errors to RFC 7807 problem details: validation → 400
  with per-field errors, unknown resource → 404, data integrity → 409, anything else → 500
  without internal detail.
- Entities use id-based equality, a constant hash code and an id-only `toString`; the cascade
  from customer to orders is removed. Only the order service writes orders.

## Consequences
- Request and response shapes are now explicit contracts documented in `OpenAPI.yaml`.
- Adding an endpoint means a request record, a service method, a controller method and tests at
  each layer, which keeps each class small and single-purpose.
- A hexagonal (ports and adapters) layout was considered and rejected for now: with two
  aggregates and one persistence technology the extra indirection would not pay for itself.
