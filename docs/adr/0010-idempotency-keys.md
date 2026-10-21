# ADR 0010: Idempotency keys on the POST endpoints

## Status
Accepted

## Context
The three `POST`s inserted unconditionally and no `Idempotency-Key` header was supported, so a
client or gateway that retried after a timeout, or after the 503 of a lost database, which does
not say whether the write was applied, created a duplicate. `docs/ASSUMPTIONS.md` ("API contract")
recorded that as a deferral and ADR 0002 placed it after authentication, because a key is unique
only within one client's requests and the service has no client identity. Issue #71 asks for the
header on `POST /customer`, `POST /order` and `POST /products`, a store that replays the original
response for a configurable time, the 422 and 409 of the IETF `Idempotency-Key` header draft for
a key reused with another request and for a repeat that arrives while the first request still
runs, and a record of where the store lives, what a restart or a lost database does to a stored
key, and how expired keys are removed.

## Decision
- **The header and its scope.** `Idempotency-Key` is optional on the three `POST`s and must match
  `^[\x21-\x7E]{1,255}$`, 1 to 255 visible ASCII characters, compared exactly; anything else,
  an empty header included, is a 400 problem detail with the code `invalid_idempotency_key`,
  decided in the controller before the request runs. Without the header a `POST` behaves as it
  always did and runs no extra statement. A key is scoped by a `ClientIdentity`, a one-method
  interface whose single bean, `AnonymousClientIdentity`, answers `anonymous` for every request:
  this is the seam that issue #70 fills with the token subject. Until then every caller shares one
  scope, so clients must use random keys (UUIDs), and a different client's identical key with a
  different body is answered as a reuse (422), never with the other client's response; two clients
  that send the same random key and the same body are the same request, which a random key makes
  negligible.
- **The store is a table.** `idempotency_key` (changeset 9) holds one row per client and key: the
  SHA-256 fingerprint of the request, its path, a status (`IN_PROGRESS` or `COMPLETED`), the
  response status, `Location` and JSON body once completed, `created_at` and `expires_at`, keyed by
  `(client_id, idempotency_key)` and indexed on `expires_at` for the purge. The database rather
  than process memory because a key must survive a restart and be seen by every replica: a client
  that retries after the replica it spoke to was replaced must still get the replay, and two
  replicas must not both create. The fingerprint is `RequestFingerprint.of`, the hash of the route
  and the JSON the application's `ObjectMapper` writes for the bound request record, so a repeat
  sent with other whitespace or another property order is the same request, while any changed
  value, the order of the product ids included, or another path is a reuse.
- **The flow of a keyed `POST`.** `IdempotentRequests.execute` takes the key, the route, the bound
  request record, the response type and the create as a supplier of the controller's
  `ResponseEntity`, so the controllers keep their return types and build the `Location` as before.
  It claims the key in a transaction of its own (`REQUIRES_NEW`): an `INSERT ... ON CONFLICT
  (client_id, idempotency_key) DO UPDATE` that takes an existing row over, resetting its status,
  fingerprint, path, response columns and timestamps, only `WHERE` the row has expired or has been
  `IN_PROGRESS` for longer than the stale window; the update count says whether the key is ours.
  When it is not, the live row is read in the same transaction: a different fingerprint is a 422
  `idempotency_key_reused`, an `IN_PROGRESS` row a 409 `idempotency_request_in_progress` with
  `Retry-After: 1`, and a `COMPLETED` row a replay of its stored status (201), `Location` and body,
  read back into the response type and sent with `Idempotent-Replayed: true`. A row that vanished
  between the two statements, released or purged by another session, is answered as in progress,
  and the client's retry a second later claims it. A claimed key runs the create inside a second
  transaction (`REQUIRED`, which the service's own `@Transactional` joins) and, after the create
  returns, records the response in that same transaction with an `UPDATE` of the row to
  `COMPLETED`, so the resource and its stored response are committed together or not at all, and
  the after-commit listeners (`CreationEventLogger`, `ProductCacheEvictor`) fire at that commit as
  before. The native `UPDATE` flushes the pending insert first, so the statements of a keyed
  `POST /customer` run as the claim, the insert and the completion, in that order.
- **Error responses are never stored.** When the create throws, the row is released in a new
  transaction and the exception is rethrown, so the usual handling answers (a 404 for an unknown
  customer, a 409 `conflict` for a violated constraint, a 503 for a lost database) and the same
  key may be sent again with a corrected request. The release deletes the row only while it is
  still `IN_PROGRESS`: a commit the server could not confirm, a connection lost at `COMMIT`, has
  either completed the row, which a repeat then replays, or left it in progress, which the release
  removes, so no committed resource is ever created twice. A release that itself fails because the
  database is gone is logged at WARN by the class of its cause, with the stack at DEBUG, and the
  original failure still answers.
- **Restart, lost database and the stale window.** A row claimed by a request that never
  completes, because the process was killed or the database connection was lost before the
  release ran, stays `IN_PROGRESS`; repeats are 409s until the stale window, `stale-after`, has
  passed since `created_at`, after which the next request with that key takes the row over and
  runs afresh. The window is 30 s by default (`IDEMPOTENCY_STALE_AFTER`) and may not be shorter
  than the 15 s transaction timeout, which the binding enforces: a create still inside its
  transaction cannot have started more than 15 s ago, so a row older than the window belongs to no
  running request and taking it over never races a create that is still going to commit. A lost
  database loses nothing stored: rows are ordinary committed data.
- **Expiry and purge.** A row answers repeats for `ttl`, 24 hours by default (`IDEMPOTENCY_TTL`);
  the claim takes an expired row over, so an expired key behaves as a fresh one. `IdempotencyKeyPurge`
  deletes the rows whose `expires_at` has passed every `purge-interval` (10 minutes by default,
  `IDEMPOTENCY_PURGE_INTERVAL`) after the same initial delay, in a transaction of its own, logging
  the count at DEBUG; `@EnableScheduling` lives in `SchedulingConfig`. The test profile sets the
  interval to an hour, so no sweep runs inside a test JVM and none can add a statement to a count a
  test pins. The three durations are bound and validated by `IdempotencyProperties`.
- **The runtime role.** The Compose runtime role `store_app` has `SELECT` and `INSERT` on every
  table through `admin`'s default privileges (ADR 0008), while completing, releasing and purging a
  key needs `UPDATE` and `DELETE` on this one table. Changeset `9-idempotency-key-runtime-grants`
  grants them to the role named by the Liquibase parameter `runtimeRole`
  (`spring.liquibase.parameters.runtimeRole`, `store_app` by default, so another deployment can
  rename it), behind a `sqlCheck` on `pg_roles` with `onFail: MARK_RAN`, so a database without the
  role, as under Testcontainers and the README's `bootRun`, records the changeset as skipped
  instead of failing. The Compose smoke test proves the grant on the started stack: a keyed
  `POST /customer` sent twice is replayed by a service running as `store_app`, and `psql` as
  `store_app` reports `has_table_privilege` for `UPDATE` and `DELETE` on the table while `update
  customer` stays denied.
- **What a keyed `POST` costs.** Two statements beyond the create: the claim and the completion.
  `QueryCountIT` pins a keyed `POST /customer` or `POST /products` at 3 prepared statements and a
  keyed `POST /order` with three products at 6, each plus the block allocation of ADR 0005, a replay
  or a 422 at 2 (the claim and the read of the row) with no entity loaded, and an unkeyed
  `POST /customer` or `POST /products` at 1, unchanged. The stored body is the JSON the
  `ObjectMapper` writes for the DTO, at most a few kilobytes for an order with 100 products.
- **Alternatives considered.** An in-memory cache (Caffeine, as the by-id reads use) would be lost
  on a restart, which is exactly when a client retries, and would let a second replica create the
  duplicate. A unique constraint on the payload, such as a customer's name, would refuse legitimate
  repeats of the same data and could not replay the original response. A single transaction
  holding the key row for the whole create, without the `IN_PROGRESS` state, would make a repeat
  wait on the row lock for up to the statement timeout and then answer a 503, where the 409 with
  `Retry-After: 1` tells the client what is happening and costs nothing to wait for; the two
  transactions are what the 409 state costs.

## Consequences
- `OpenAPI.yaml` 3.1.0 declares the `Idempotency-Key` parameter on the three `POST`s, the
  `Idempotent-Replayed` header on their 201s, the 422 (`UnprocessableEntity`) and the two faces of
  the 409 (`Conflict`, with `Retry-After` for the in-progress case) with named examples, and the
  three codes; `OpenApiContractIT` checks a replayed 201 on each `POST`, the 400, the 422 and the
  409 against the contract and the examples. Adding the header and the codes is a minor version:
  a client of 3.0.0 that sends no key sees no change.
- `IdempotencyIT` covers the behaviour against the database: the replay returns the same id,
  `Location` and body and inserts nothing; a reuse with another body or path is a 422; a 404 and a
  constraint violation release the key; an expired row and a stale `IN_PROGRESS` row are taken
  over; a fresh `IN_PROGRESS` row, a repeat while the first create blocks on a latch and one while
  its insert waits for a table lock are 409s, after which the first request completes and is then
  replayed; the after-commit listeners still run; keys compare exactly; the purge keeps live rows
  and the sweep is registered with the configured interval. The slice tests pin the header parsing
  with the component mocked, `IdempotentRequestsTest` the transaction boundaries and the release
  paths with the store mocked, and the Cucumber features the replay on each `POST`, the 422 and the
  400. `scripts/smoke-test.sh` replays a keyed `POST /customer` in both modes.
- A `POST` answered with a 503 or a 500 can now be repeated safely when it was sent with a key:
  the repeat replays the stored response when the first attempt was committed and runs the request
  afresh when it was not, so `docs/DESIGN.md`'s warning that those answers do not say whether the
  write was applied holds only for unkeyed `POST`s.
- Until issue #70 lands, the `anonymous` scope means a key is shared by every caller: a client
  that derives keys from its own data (an order number) could collide with another client's, and
  would get a 422 for the other's body or, for the same body, the other's response. Random keys
  avoid both; the contract says so. When the token subject arrives, rows written as `anonymous`
  stop matching any caller and expire with their ttl.
- Nothing bounds the number of keys a caller can store other than the ttl; at the default 24
  hours the table holds one row per keyed `POST` of the day. Rate limiting stays at the gateway
  (ADR 0002).
- A deployment that renames the runtime role must pass the name as the Liquibase parameter on
  the migration job, or the grant lands on a role that does not exist and the changeset is skipped,
  after which the first keyed `POST` fails with `permission denied for table idempotency_key`.
