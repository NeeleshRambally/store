# ADR 0005: Sequence-based id generation with block allocation

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record explains why ids come from database sequences in blocks of 50 instead of identity
columns, and how the change rolls out without colliding with existing rows. The statement counts
before and after are in [Write path](../PERFORMANCE.md#write-path).

## Status
Accepted

## Context
The three entities used `GenerationType.IDENTITY`. Hibernate must then run every insert at once
to learn its id, so inserts can never be batched. The join-table rows of an order were written
with one prepared statement each. The seed data inserts explicit ids (customers 1..100,
orders 1..10,000, products 1..100). Changesets 3 and 5 then move each `BIGSERIAL` sequence to
`max(id) + 1` with `setval(..., false)`.

## Decision
- **Sequences with blocks of 50.** `Customer`, `Order` and `Product` use
  `GenerationType.SEQUENCE` with a `@SequenceGenerator` on the existing `customer_id_seq`,
  `order_id_seq` and `product_id_seq`, and `allocationSize = 50`. Changeset 6 alters the three
  sequences to `INCREMENT BY 50`.
- **The `pooled-lo` optimiser** (`hibernate.id.optimizer.pooled.preferred`). An optimiser decides
  how Hibernate turns one `nextval` into a block of ids; `pooled-lo` treats the fetched value as
  the low end of the block. After `setval(order_id_seq, 10001, false)` the first `nextval`
  returns 10001, and `pooled-lo` hands out 10001..10050. The default `pooled` optimiser treats the
  value as the top of its block and would hand out 9952..10001, which collide with the seeded
  rows. A writer that bypasses Hibernate (`INSERT ... RETURNING id`, a migration) simply takes the
  next block of 50.
- **Batching is on:** `hibernate.jdbc.batch_size: 50`, `order_inserts: true`,
  `order_updates: true`, and PgJDBC `reWriteBatchedInserts=true` through the HikariCP
  data-source properties, so a batch becomes one multi-row `INSERT`.
- **Two guards fail the application at start-up** rather than at the first insert:
  - `hibernate.id.sequence.increment_size_mismatch_strategy` stays at its default, `EXCEPTION`,
    so a database whose sequence increment is not 50 (changeset 6 missing or rolled back) refuses
    to start;
  - `spring.jpa.hibernate.ddl-auto: validate` checks that the named sequences exist.
- **Tests.** `MigrationIT` checks the increment of all three sequences, and that a direct
  `INSERT ... RETURNING id` still lands above the seeded ids. `InsertBatchingIT` checks that three
  orders flushed together need at most three prepared statements, which `IDENTITY` cannot
  satisfy.

## Rollout
- **Migrate first.** Changeset 6 is applied before the new application version starts, by the
  migration job of [ADR 0008](0008-operational-posture.md). The Compose `migrate` service, or a
  Kubernetes `Job` or init container ahead of the deployment, runs the image with the schema
  owner's credentials. The Compose `store` service runs no Liquibase
  (`SPRING_LIQUIBASE_ENABLED=false`).
- **In-process where Liquibase is on.** Liquibase stays enabled by default, so `./gradlew bootRun`
  and the image run on its own
  ([Pulling the delivered image](../DESIGN.md#pulling-the-delivered-image)) still migrate
  in-process at start-up. A rollout that runs the image that way applies changeset 6 on its first
  new instance.
- **The old version keeps working.** It keeps serving after changeset 6 is applied and tolerates
  the larger increment: an `IDENTITY` insert uses the default of the column, which just skips 49
  ids per row.
- **Rollback order.** Rolling back changeset 6 requires rolling back the application first; the
  new version refuses to start against a sequence with increment 1.
- **Explicit ids.** Anything that writes explicit ids (seed data, imports) must keep them below the
  current sequence value, as the existing changesets already do. The sequence is never moved
  backwards.
- **Gaps.** Each instance holds its unallocated block in memory, so a restart or a second instance
  leaves gaps of up to 49 ids. Ids were never promised to be gapless and are not exposed as a
  count anywhere.

## Consequences
- `POST /order` with three products drops from 6 to 4 prepared statements, plus one `nextval` per
  50 orders (pinned by `QueryCountIT`). Several entities flushed together become one batched
  insert per table. An `Idempotency-Key` adds two statements
  ([Write path](../PERFORMANCE.md#write-path)).
- Generated ids are known as soon as an entity is persisted, before the flush, which simplifies
  any future code that links entities before commit.
- The inserts now run at commit, after the service method has returned. A creation is therefore
  logged only after its commit ([Logs](../DESIGN.md#logs)), and an insert that fails there fails
  the commit itself ([Database failures](../DESIGN.md#database-failures)).
- The id-assignment contract changes from "the next serial value" to "a value from the current
  block". Nothing in the API or the tests depended on consecutive ids.
