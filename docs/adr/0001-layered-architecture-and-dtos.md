# ADR 0001: Layered architecture with request and response DTOs

## Status
Accepted

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
