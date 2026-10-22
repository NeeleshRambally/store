# ADR 0002: Authentication and authorisation are deferred to a later phase

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record explains why the service ships without authentication, what protects it until
authentication lands, and what a gateway in front of it must do. The input rules it relies on are
in [API contract](../ASSUMPTIONS.md#api-contract) and [Strict input](../DESIGN.md#strict-input).

## Status
Accepted; amended by [ADR 0010](0010-idempotency-keys.md), which shipped idempotency keys ahead
of authentication.

## Context
The API has no caller identity. A production fintech service needs authenticated callers with
per-endpoint authorisation, not merely a security filter chain. Adding Spring Security with
`permitAll` would add surface area, complicate every web test and protect nothing.

## Decision
- **No Spring Security yet.** The service is assumed to run behind an authenticating gateway or
  service mesh that terminates TLS and authenticates callers.
- **Controls that need no identity ship now:**
  - Strict request records with validation. JSON types are bound exactly: a string or a fraction
    is never coerced into an id, nor a number or a boolean into text. Request text carries no
    control characters.
  - Unknown JSON properties are rejected. So is a property repeated in a body, from which the
    gateway and the service could otherwise read different values.
  - Query parameters are validated. On the list endpoints, which take them, a repeated
    single-valued parameter is rejected, and so is a query string that cannot be parsed in full.
  - Numeric path and query parameters are accepted only in canonical decimal form: ASCII digits,
    no plus sign, no leading zeros. Each id has one spelling and is never read as hexadecimal or
    with its whitespace removed.
  - Problem details never leak exception messages or stack traces.
  - The actuator exposes only `health`, `info` and `prometheus`, with health details hidden.
  - The container image runs as a non-root user.
- **Bodies are bounded by the service**, not only by the gateway. A `POST`'s JSON body over
  `REQUEST_MAX_BODY_BYTES` (64 KiB by default) is a 413 problem detail before it is parsed. A
  body of any other media type is a 415, and a body sent with a method no endpoint takes is a
  405; neither body is read. The gateway should bound bodies as well, but the service does not
  rely on it. The rules are in [Creating resources](../DESIGN.md#creating-resources) and
  [ADR 0008](0008-operational-posture.md), under "Request size".
- **The management port is already separate.** The actuator is served on
  `management.server.port` (8081 by default) and is absent from the API port, so the public
  ingress does not expose it. Management traffic is absent from `http.server.requests`
  ([Ports, probes and metrics](../DESIGN.md#ports-probes-and-metrics)).
- **Left to the gateway for now:** rate limiting and per-client quotas, CORS, and the security
  response headers that Spring Security would add (`Strict-Transport-Security`,
  `X-Content-Type-Options`, `X-Frame-Options`). The service implements none of them. When the
  resource server lands, the security headers and CORS move into the service's filter chain.
  Rate limiting stays at the gateway, which sees every replica.
- **The resource server comes next:** `spring-boot-starter-oauth2-resource-server` with JWT
  validation and scope-based authorisation (for example `orders:write` on `POST /order`). It is
  [issue #70](https://github.com/NeeleshRambally/store/issues/70), due before 2027-03-31. It
  follows the Spring Boot 4.1 upgrade of
  [issue #57](https://github.com/NeeleshRambally/store/issues/57), so that the filter chain is
  written once, on the Spring Security line that Boot 4.1 manages.
- **Idempotency keys came first.** The `Idempotency-Key` support of
  [issue #71](https://github.com/NeeleshRambally/store/issues/71) landed ahead of authentication
  ([ADR 0010](0010-idempotency-keys.md)). A key is unique only within one client's requests, so
  the key store is scoped by a `ClientIdentity`. It answers `anonymous` for every caller until
  authentication supplies the token subject, so until then clients must use random keys.

## Consequences
- Until authentication lands, the service must not be reachable from untrusted networks. The
  Compose stack publishes the API, the actuator and the database on 127.0.0.1 unless
  `STORE_BIND_ADDRESS` names another address. Its smoke test fails when a published port is bound
  to any other address ([The smoke test](../DESIGN.md#the-smoke-test)).
- A gateway rule written for a path or an id holds only if the gateway reads the request as the
  service does. Before it matches the route and binds an id, the service percent-decodes the path
  and the query string and drops the `;` parameters of each path segment. So `/order/%31`,
  `/%6Frder/1` and `/order/1;x=1` return order 1, and `customerId=%31` filters by customer 1: the
  canonical decimal form applies to the decoded value only. A gateway that applies such a rule
  must decode the path and the query the same way before it compares them. Otherwise it must
  reject a path that carries a percent-encoded octet or a `;` parameter.
- Tomcat drops a query parameter it cannot decode. A gateway that read `customerId=1%` as
  customer 1 would have scoped a request that the service answered with every order. So the list
  endpoints reject a query string that Tomcat cannot parse in full with a 400
  (`invalid_parameter`), whichever parameter it affects, rather than serve the request without it
  ([Strict input](../DESIGN.md#strict-input)).
- Tests stay free of security fixtures, which keeps the slice and acceptance tests focused on
  behaviour. They gain a JWT fixture when authentication lands.
