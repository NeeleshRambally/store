# ADR 0010: Idempotency keys on the POST endpoints

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record covers the optional `Idempotency-Key` header on the three `POST`s: a key the client
chooses, so that a repeat of the same request creates nothing new. It records where the keys are
stored, how a keyed request runs, and what a restart or a lost database does to a key.
[Creating resources](../DESIGN.md#creating-resources) gives the rules a client sees, and
[Configuration](../DESIGN.md#configuration) the three settings.

## Status
Accepted; amends [ADR 0002](0002-authentication-deferred.md), which had placed idempotency
keys after authentication.

## Context
The three `POST`s inserted unconditionally, and no `Idempotency-Key` header was supported. A
client or gateway that retried therefore created a duplicate: after a timeout, or after the 503
of a lost database, which does not say whether the write was applied.

The assumptions recorded that as a deferral under [API contract](../ASSUMPTIONS.md#api-contract),
and [ADR 0002](0002-authentication-deferred.md) had placed it after authentication. A key is
unique only within one client's requests, and the service has no client identity.

[Issue #71](https://github.com/NeeleshRambally/store/issues/71) asks for:
- the header on `POST /customer`, `POST /order` and `POST /products`;
- a store that replays the original response for a configurable time;
- the 422 and 409 of the IETF `Idempotency-Key` header draft, for a key reused with another
  request and for a repeat that arrives while the first request still runs;
- a record of where the store lives, what a restart or a lost database does to a stored key, and
  how expired keys are removed.

## Decision
A keyed `POST` claims its key in a database table, runs the create, and stores the response in the
same transaction as the resource. A repeat gets the stored response back; a reused key is a 422,
and a repeat while the first request runs is a 409.

### The header and its scope
- `Idempotency-Key` is optional on the three `POST`s. It must match `^[\x21-\x7E]{1,255}$`: 1 to
  255 visible ASCII characters, compared exactly.
- Anything else, an empty header included, is a 400 problem detail with the code
  `invalid_idempotency_key`, decided in the controller before the request runs.
- Without the header a `POST` behaves as it always did and runs no extra statement.
- A key is scoped by a `ClientIdentity`, a one-method interface. Its single bean,
  `AnonymousClientIdentity`, answers `anonymous` for every request. This is the seam that
  [issue #70](https://github.com/NeeleshRambally/store/issues/70) fills with the token subject.
- Until then every caller shares one scope, so clients must use random keys (UUIDs). Another
  client's identical key with a different body is answered as a reuse (422), never with the other
  client's response. Two clients that send the same random key and the same body are treated as
  the same request, a case that random keys make negligible.

### The store is a table
- `idempotency_key` (changeset 9) holds one row per client and key, keyed by
  `(client_id, idempotency_key)`. A row holds:
  - the token of the claim that owns it;
  - the SHA-256 fingerprint of the request, and its path;
  - a status, `IN_PROGRESS` or `COMPLETED`;
  - once completed, the response status, `Location` and JSON body;
  - `created_at`, and `expires_at`, which is indexed for the purge.
- The store is the database rather than process memory, because a key must survive a restart and
  be seen by every replica. A client that retries after its replica was replaced must still get the
  replay, and two replicas must not both create.
- The fingerprint is `RequestFingerprint.of`: the hash of the route and of the JSON that the
  application's `ObjectMapper` writes for the bound request record. A repeat sent with other
  whitespace or another property order is the same request. Any changed value, the order of the
  product ids included, or another path is a reuse.

### The flow of a keyed POST
`IdempotentRequests.execute` takes the key, the route, the bound request record, the response type,
and the create as a supplier of the controller's `ResponseEntity`. The controllers keep their
return types and build the `Location` as before.
- **Claim.** The key is claimed in a transaction of its own (`REQUIRES_NEW`), with a random token.
  The claim is an `INSERT ... ON CONFLICT (client_id, idempotency_key) DO UPDATE`. It takes an
  existing row over only `WHERE` the row has expired, or has been `IN_PROGRESS` for longer than the
  stale window. A takeover resets the row's token, status, fingerprint, path, response columns and
  timestamps. The update count says whether the key is ours.
- **Answer from the row.** When the key is not ours, the live row is read in the same transaction:
  - a different fingerprint is a 422 `idempotency_key_reused`;
  - an `IN_PROGRESS` row is a 409 `idempotency_request_in_progress` with `Retry-After: 1`;
  - a `COMPLETED` row is replayed: its stored status (201), `Location` and body, read back into the
    response type and sent with `Idempotent-Replayed: true`.

  A row that vanished between the two statements, released or purged by another session, is
  answered as in progress, and the client's retry a second later claims it.
- **Create and record.** A claimed key runs the create inside a second transaction (`REQUIRED`,
  which the service's own `@Transactional` joins). After the create returns, the same transaction
  records the response with an `UPDATE` of the row to `COMPLETED` that matches the claim's token
  and the `IN_PROGRESS` status. The resource and its stored response are committed together or not
  at all, and the after-commit listeners (`CreationEventLogger`, `ProductCacheEvictor`) fire at
  that commit as before.
- **Takeover detected.** An `UPDATE` that matches no row means that a later request took the key
  over while this one was still running (see the stale window below). The create is rolled back,
  the takeover is logged at WARN, and the request is answered as in progress, so its retry replays
  what the later request stored. No resource is ever committed without its stored response, and no
  key ever answers for two.
- **Statement order.** The native `UPDATE` flushes the pending insert first, so the statements of a
  keyed `POST /customer` run as the claim, the insert and the completion, in that order.

### Error responses are never stored
- When the create throws, the row is released in a new transaction and the exception is rethrown.
  The usual handling answers: a 404 for an unknown customer, a 409 `conflict` for a violated
  constraint, a 503 for a lost database. The same key may then be sent again with a corrected
  request.
- The release deletes the row only while it is still `IN_PROGRESS` and still carries the request's
  own token. So:
  - a commit the server could not confirm (a connection lost at `COMMIT`) has either completed the
    row, which a repeat then replays, or left it in progress, which the release removes; no
    committed resource is ever created twice;
  - a row that a later request took over meanwhile is left to that request.
- A release that itself fails because the database is gone is logged at WARN by the class of its
  cause, with the stack at DEBUG. The original failure still answers.

### Restart, lost database and the stale window
- A row claimed by a request that never completes stays `IN_PROGRESS`: the process was killed, or
  the database connection was lost before the release ran. Repeats are 409s until the stale window,
  `stale-after`, has passed since `created_at`. The next request with that key then takes the row
  over and runs afresh.
- The window is 30 s by default (`IDEMPOTENCY_STALE_AFTER`). It is not what keeps a key from
  creating twice; the token is. A takeover of a row whose request is still running makes that
  request fail (above), never create a second resource.
- The window weighs how long a crashed request blocks its key against how long a slow one keeps its
  claim. It must cover the time a keyed create can hold its claim, 30 s at the defaults:
  - up to 5 s waiting for a pool connection before the transaction deadline starts;
  - `TX_TIMEOUT` (15 s);
  - the `DB_STATEMENT_TIMEOUT` (10 s) that a statement started before the deadline may still run.
- `IdempotencyStaleWindowGuard` adds the three up from the bound configuration at start-up and
  refuses a shorter window, naming the value to set. An operator who raises `TX_TIMEOUT` or
  `DB_STATEMENT_TIMEOUT` therefore raises `IDEMPOTENCY_STALE_AFTER` with them.
- With `TX_TIMEOUT=-1` or `DB_STATEMENT_TIMEOUT=0`, nothing bounds a create. The guard then logs at
  WARN that a request running longer than the window is taken over and fails without creating
  anything.
- The guard exists in the serving process only: the `migrate` job runs no keyed `POST` and sets
  `DB_STATEMENT_TIMEOUT` to `0`.
- A lost database loses nothing stored: the rows are ordinary committed data.

### Expiry and purge
- A row answers repeats for `ttl`, 24 hours by default (`IDEMPOTENCY_TTL`). The claim takes an
  expired row over, so an expired key behaves as a fresh one.
- `IdempotencyKeyPurge` deletes the rows whose `expires_at` has passed, in a transaction of its
  own, and logs the count at DEBUG. It runs every `purge-interval` (10 minutes by default,
  `IDEMPOTENCY_PURGE_INTERVAL`), after an initial delay of the same length.
- `@EnableScheduling` lives in `SchedulingConfig`. Both `SchedulingConfig` and
  `IdempotencyKeyPurge` exist in the serving process only (`@ConditionalOnWebApplication`). The
  `migrate` job of
  [ADR 0008](0008-operational-posture.md#migrations-run-as-a-job) runs the same image without a web
  server and exits because nothing keeps the JVM alive. A scheduler's thread would, so the job would
  never complete and `store` would never start.
- The test profile sets the interval to an hour, so no sweep runs inside a test JVM and none can add
  a statement to a count a test pins.
- `IdempotencyProperties` binds and validates the three durations.

### The runtime role
- The Compose runtime role `store_app` has `SELECT` and `INSERT` on every table through `admin`'s
  default privileges ([ADR 0008](0008-operational-posture.md#migrations-run-as-a-job)). Completing,
  releasing and purging a key needs `UPDATE` and `DELETE` on this one table.
- Changeset `9-idempotency-key-runtime-grants` grants them to the role named by the Liquibase
  parameter `runtimeRole` (`spring.liquibase.parameters.runtimeRole`, `store_app` by default), so
  another deployment can rename the role.
- A `sqlCheck` on `pg_roles` with `onFail: MARK_RAN` guards the grant. A database without the role,
  as under Testcontainers and the [README](../../README.md)'s `bootRun`, records the changeset as
  skipped instead of failing.
- The Compose smoke test proves the grant on the started stack. A keyed `POST /customer` sent twice
  is replayed by a service running as `store_app`, and `psql` as `store_app` reports
  `has_table_privilege` for `UPDATE` and `DELETE` on the table, while `update customer` stays
  denied.

### What a keyed POST costs
A keyed `POST` runs two statements beyond the create: the claim and the completion. `QueryCountIT`
pins these counts of prepared statements ([Write path](../PERFORMANCE.md#write-path)):

| Request | Prepared statements |
|---|---|
| Keyed `POST /customer` or `POST /products` | 3, plus the block allocation of [ADR 0005](0005-sequence-id-generation.md) |
| Keyed `POST /order` with three products | 6, plus the block allocation |
| A replay | 2 (the claim and the read of the row), with no entity loaded |
| A 422 for a reused key | 2 (the claim and the read of the row) |
| Unkeyed `POST /customer` or `POST /products` | 1, plus the block allocation, unchanged |

The stored body is the JSON the `ObjectMapper` writes for the DTO: at most a few kilobytes for an
order with 100 products.

### Alternatives considered
- **An in-memory cache** (Caffeine, as the by-id reads use) would be lost on a restart, which is
  exactly when a client retries, and would let a second replica create the duplicate.
- **A unique constraint on the payload**, such as a customer's name, would refuse legitimate
  repeats of the same data and could not replay the original response.
- **One transaction holding the key row for the whole create**, without the `IN_PROGRESS` state,
  would make a repeat wait on the row lock for up to the statement timeout and then answer a 503.
  The 409 with `Retry-After: 1` tells the client what is happening and costs nothing to wait for.
  The two transactions are what the 409 state costs.
- **A takeover that trusts the window alone**, without the token, would let a create that outlived
  the window commit next to the request that took its key over: two resources for one key. The
  token costs one column.
- **No takeover at all**, leaving a crashed request's key to its ttl, would answer 409s for a day
  for a request that never created anything.

## Consequences
- **The contract grows to 3.1.0.** `OpenAPI.yaml`, contract version 3.1.0, declares:
  - the `Idempotency-Key` parameter on the three `POST`s, and the `Idempotent-Replayed` header on
    their 201s;
  - the 422 (`UnprocessableEntity`) and both faces of the 409 (`Conflict`, with `Retry-After` for
    the in-progress case), with named examples;
  - the three codes.

  Adding them is a minor version: a client of 3.0.0 that sends no key sees no change
  ([Contract version and client migration](../ASSUMPTIONS.md#contract-version-and-client-migration)).
- **The contract check covers it.** `OpenApiContractIT` checks a replayed 201 on each `POST`, the
  400, the 422 and the 409 against the contract and the examples.
- **`IdempotencyIT` covers the behaviour against the database:**
  - the replay returns the same id, `Location` and body and inserts nothing;
  - a reuse with another body or path is a 422;
  - a 404 and a constraint violation release the key;
  - an expired row and a stale `IN_PROGRESS` row are taken over;
  - a fresh `IN_PROGRESS` row, a repeat while the first create blocks on a latch, and one while its
    insert waits for a table lock are 409s, after which the first request completes and is then
    replayed;
  - a key taken over while its request still runs rolls that request back, creates once and
    replays the later request, and a release after a takeover leaves the new claim in place;
  - the after-commit listeners still run, and keys compare exactly;
  - the purge keeps live rows, the sweep is registered with the configured interval, and a process
    started without a web server registers neither the sweep nor the stale window guard.
- **The other tests.** The controller slice tests pin the header parsing with `IdempotentRequests`
  mocked.
  `IdempotentRequestsTest` pins the transaction boundaries, the token on the claim, the completion
  and the release, and the release paths, with the store mocked. `IdempotencyStaleWindowGuardTest`
  pins the bound and the WARN. The Cucumber features pin the replay on each `POST`, the 422 and the
  400, and `scripts/smoke-test.sh` replays a keyed `POST /customer` in both modes.
- **A keyed retry is safe.** A `POST` answered with a 503 or a 500 can now be repeated safely when
  it was sent with a key. The repeat replays the stored response when the first attempt was
  committed, and runs the request afresh when it was not. The warning that those answers do not say
  whether the write was applied therefore holds only for unkeyed `POST`s
  ([Database failures](../DESIGN.md#database-failures)).
- **Where the rest is written down:**
  - the header rules: [Creating resources](../DESIGN.md#creating-resources);
  - the three settings: [Configuration](../DESIGN.md#configuration);
  - the role grant: [Start-up and migrations](../DESIGN.md#start-up-and-migrations);
  - the three codes: [Errors](../DESIGN.md#errors);
  - the behaviour as an assumption: [Request bodies](../ASSUMPTIONS.md#request-bodies);
  - the 3.1.0 entry:
    [Contract version and client migration](../ASSUMPTIONS.md#contract-version-and-client-migration).
- **One shared scope for now.** Until
  [issue #70](https://github.com/NeeleshRambally/store/issues/70) lands, the `anonymous` scope
  means that every caller shares one key space. A client that derives keys from its own data (an
  order number) could collide with another client's key. It would get a 422 for the other's body
  or, for the same body, the other's response. Random keys avoid both, and the contract's
  description suggests a UUID. When the token subject arrives, rows written as `anonymous` stop
  matching any caller and expire with their ttl.
- **Only the ttl bounds the table.** Nothing bounds the number of keys a caller can store other
  than the ttl. At the default 24 hours, the table holds one row per keyed `POST` of the day. Rate
  limiting stays at the gateway ([ADR 0002](0002-authentication-deferred.md)).
- **A renamed role needs the parameter.** A deployment that renames the runtime role must pass the
  name as the Liquibase parameter on the migration job. Otherwise the grant targets a role that
  does not exist, the changeset is skipped, and the first keyed `POST` fails with
  `permission denied for table idempotency_key`. The changeset runs again when the name changes
  (`runOnChange`), so a rename after a skipped or an earlier run grants the renamed role instead of
  failing the checksum validation. Its rollback revokes only where the role exists.
