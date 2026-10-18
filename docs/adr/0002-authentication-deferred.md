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
  validation, rejection of unknown JSON properties, problem-detail responses that never leak
  exception messages or stack traces, actuator exposure limited to `health`, `info` and
  `prometheus` with health details hidden, and a container image that runs as a non-root user.
- A later phase adds `spring-boot-starter-oauth2-resource-server` with JWT validation, scope
  based authorisation (for example `orders:write` on `POST /order`).
- The management port was separated ahead of that phase: the actuator is served on
  `management.server.port` (8081 by default) and is absent from the API port, so it is not
  exposed through the public ingress. Management traffic is absent from `http.server.requests`.

## Consequences
- Until that phase the service must not be reachable from untrusted networks.
- Tests remain free of security fixtures, which keeps the slice and acceptance tests focused on
  behaviour; they will gain a JWT fixture when authentication lands.
