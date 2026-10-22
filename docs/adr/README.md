# Architecture decision records

[Notes for the assessor](../../NOTES.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

Each architecture decision record (ADR) here records one decision that shapes the code, under four
headings: Status, Context, Decision and Consequences. A record is updated whenever the code it
describes changes, so it always matches the code as it is today.

| ADR | Status | Decision |
|-----|--------|----------|
| [0001: Layered architecture with request and response DTOs](0001-layered-architecture-and-dtos.md) | Accepted; amended by [ADR 0006](0006-list-projections-and-sort-whitelist.md) and [ADR 0003, Nested collections](0003-pagination-and-fetch-strategy.md#nested-collections) | Controller, service and repository layers, with validated request records and immutable response records, so entities never cross the controller boundary. |
| [0002: Authentication and authorisation are deferred to a later phase](0002-authentication-deferred.md) | Accepted; amended by [ADR 0010](0010-idempotency-keys.md) | Callers are authenticated by a gateway in front of the service, which ships the controls that need no identity; OAuth2 follows in [issue #70](https://github.com/NeeleshRambally/store/issues/70). |
| [0003: Pagination and fetch strategy for list endpoints](0003-pagination-and-fetch-strategy.md) | Accepted; amended by its Keyset pagination and Nested collections sections; list fetch strategy superseded by [ADR 0006](0006-list-projections-and-sort-whitelist.md) | Every list is paged, 20 by default and at most 100, with cursor paging by id and bounded nested order lists. |
| [0004: Time-boxed exceptions for two Spring Framework 6 advisories](0004-spring-framework-6-cve-exceptions.md) | Accepted; expires 2027-01-31 | Two critical `spring-webmvc` advisories, whose code paths the service never uses, are accepted in `.trivyignore` until the Spring Boot 4 upgrade. |
| [0005: Sequence-based id generation with block allocation](0005-sequence-id-generation.md) | Accepted | Ids come from the existing sequences in blocks of 50, so inserts are deferred to the flush and batched. |
| [0006: Constructor projections for list pages and a sort whitelist](0006-list-projections-and-sort-whitelist.md) | Accepted; supersedes the list fetch strategy of [ADR 0003](0003-pagination-and-fetch-strategy.md); amended by [ADR 0003, Nested collections](0003-pagination-and-fetch-strategy.md#nested-collections) | List pages are built from JPQL constructor projections instead of entities, and `sort` accepts only a per-endpoint list of indexed properties. |
| [0007: HTTP revalidation and a bounded service-level cache for by-id reads](0007-caching.md) | Accepted | Every `GET` carries a weak ETag and answers a matching `If-None-Match` with 304, and single orders and products are cached in the service for 60 s. |
| [0008: Operational posture: migrations, readiness, shutdown and request ids](0008-operational-posture.md) | Accepted | Migrations run as a separate job, the service uses a restricted database role, readiness includes the database, shutdown drains in-flight requests, and every request carries an id. |
| [0009: Index changesets](0009-index-changesets.md) | Accepted | Changesets 3, 4 and 7 keep their plain index builds; every later index on an existing table is built concurrently, one per changeset. |
| [0010: Idempotency keys on the POST endpoints](0010-idempotency-keys.md) | Accepted; amends [ADR 0002](0002-authentication-deferred.md) | The three `POST`s accept an optional `Idempotency-Key`; a database table stores the first response and replays it to a repeat. |
| [0011: Swagger UI from the checked-in contract](0011-swagger-ui-from-the-contract.md) | Accepted | Swagger UI renders the checked-in `OpenAPI.yaml` on the API port; nothing is generated from the code. |
