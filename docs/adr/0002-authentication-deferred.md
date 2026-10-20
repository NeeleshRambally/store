# ADR 0002: Authentication and authorisation are deferred to a later phase

## Status
Accepted

## Context
The API currently has no caller identity. For a production fintech service the real
requirement is authenticated callers with per-endpoint authorisation, not merely a security
filter chain. Adding Spring Security with `permitAll` would add surface area, complicate every
web test and provide no actual protection.

## Decision
- This phase does not add Spring Security. The service is assumed to run behind an
  authenticating gateway or service mesh that terminates TLS and authenticates callers.
- The controls that are shipped now and do not depend on identity: strict request DTOs with
  validation (JSON types are bound exactly, so a string or a fraction is never coerced into an
  id, nor a number or a boolean into text, and request text carries no control characters),
  rejection of unknown JSON properties and of a property repeated in a body (the gateway and
  the service could otherwise read different values from it), validated query parameters that
  are rejected when repeated or when the query string cannot be parsed in full, numeric path and
  query parameters accepted only in canonical decimal form (ASCII digits with no plus sign or
  leading zeros, so each id has one decimal spelling, and never read in hexadecimal or with
  their whitespace removed), problem-detail
  responses that never leak exception messages or stack traces, actuator exposure limited to
  `health`, `info` and `prometheus` with health details hidden, and a container image that runs
  as a non-root user.
- A later phase adds `spring-boot-starter-oauth2-resource-server` with JWT validation, scope
  based authorisation (for example `orders:write` on `POST /order`). It is tracked as
  [issue #70](https://github.com/neeleshambally/store/issues/70), due before 2027-03-31, after
  the Spring Boot 4.1 upgrade of [issue #57](https://github.com/neeleshambally/store/issues/57),
  so that the filter chain is written once, on the Spring Security line that Boot 4.1 manages.
  The `Idempotency-Key` support that the `POST`s lack (`docs/ASSUMPTIONS.md`, API contract)
  follows it as [issue #71](https://github.com/neeleshambally/store/issues/71), due before
  2027-04-30, because a key is unique only within one client's requests and the service has no
  client identity until that phase.
- Request bodies are bounded by the service itself, not only by the gateway: a body over
  `REQUEST_MAX_BODY_BYTES` (64 KiB by default) is a 413 problem detail before it is parsed
  (ADR 0008, "Request size"). The gateway should bound bodies as well, but the service does not
  rely on it.
- Rate limiting and per-client quotas, CORS and the security response headers that Spring
  Security would add (`Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`)
  are gateway responsibilities in this phase; the service implements none of them. With the
  resource-server phase the security headers and CORS move into the service's filter chain,
  while rate limiting stays at the gateway, which sees every replica.
- The management port was separated ahead of that phase: the actuator is served on
  `management.server.port` (8081 by default) and is absent from the API port, so it is not
  exposed through the public ingress. Management traffic is absent from `http.server.requests`.

## Consequences
- Until that phase the service must not be reachable from untrusted networks. The Compose stack
  publishes the API, the actuator and the database on 127.0.0.1 unless `STORE_BIND_ADDRESS`
  names another address, and its smoke test fails when a published port is bound to any
  address other than that one.
- A gateway rule written for a path or an id holds only if the gateway reads the request as the
  service does. The service percent-decodes the path and the query string and drops the `;`
  parameters of each path segment before it matches the route and binds an id, so `/order/%31`,
  `/%6Frder/1` and `/order/1;x=1` return order 1 and `customerId=%31` filters by customer 1:
  the canonical decimal form applies to the decoded value only. A gateway that applies such a
  rule must decode the path and the query in the same way before it compares them, or reject a
  path that carries a percent-encoded octet or a `;` parameter. Tomcat drops a query parameter
  it cannot decode, so a gateway that read `customerId=1%` as customer 1 would have scoped a
  request that the service answered with every order; the list endpoints therefore reject a
  query string that Tomcat cannot parse in full with a 400 (`invalid_parameter`), whichever
  parameter it affects, rather than serve the request without it (`docs/DESIGN.md`, input
  rules).
- Tests remain free of security fixtures, which keeps the slice and acceptance tests focused on
  behaviour; they will gain a JWT fixture when authentication lands.
