# ADR 0005: Sequence-based id generation with block allocation

## Status
Accepted

## Context
The three entities used `GenerationType.IDENTITY`. Hibernate must then execute every insert
immediately to learn the id, so inserts can never be batched and the join-table rows of an order
were written one prepared statement each. The seed data inserts explicit ids (customers 1..100,
orders 1..10,000, products 1..100), after which changesets 3 and 5 move each `BIGSERIAL`
sequence to `max(id) + 1` with `setval(..., false)`.

## Decision
- `Customer`, `Order` and `Product` use `GenerationType.SEQUENCE` with a `@SequenceGenerator`
  on the existing `customer_id_seq`, `order_id_seq` and `product_id_seq` and
  `allocationSize = 50`. Changeset 6 alters the three sequences to `INCREMENT BY 50`.
- The optimiser is `pooled-lo` (`hibernate.id.optimizer.pooled.preferred`). The default
  `pooled` optimiser treats a fetched value as the top of its block: after `setval(order_id_seq,
  10001, false)` the first `nextval` returns 10001 and `pooled` would hand out 9952..10001, which
  collide with the seeded rows. `pooled-lo` treats the fetched value as the low end of the block
  (10001..10050), and a writer that bypasses Hibernate (`INSERT ... RETURNING id`, a migration)
  simply takes the next block of 50.
- Batching is switched on: `hibernate.jdbc.batch_size: 50`, `order_inserts: true`,
  `order_updates: true`, and PgJDBC `reWriteBatchedInserts=true` through the HikariCP
  data-source properties, so a batch becomes one multi-row `INSERT`.
- Two guards fail the application at start-up rather than at the first insert:
  `hibernate.id.sequence.increment_size_mismatch_strategy` stays at its default `EXCEPTION`, so
  a database whose sequence increment is not 50 (changeset 6 missing or rolled back) refuses to
  start; and `spring.jpa.hibernate.ddl-auto: validate` verifies that the named sequences exist.
- `MigrationIT` checks the increment of all three sequences and that a direct
  `INSERT ... RETURNING id` still lands above the seeded ids; `InsertBatchingIT` checks that
  three orders flushed together need at most three prepared statements, which `IDENTITY` cannot
  satisfy.

## Rollout
- Changeset 6 is applied before the new application version starts, by the migration job of
  ADR 0008: the Compose `migrate` service, or a Kubernetes `Job` or init container ahead of the
  deployment, runs the image with the schema owner's credentials, and the Compose `store`
  service runs no Liquibase (`SPRING_LIQUIBASE_ENABLED=false`). Liquibase stays enabled by
  default, so `./gradlew bootRun` and the image run on its own (`docs/DESIGN.md`, Continuous
  integration) still migrate in-process at start-up, and a rollout that runs the image that way
  applies changeset 6 on its first new instance. The old version, which keeps serving after
  changeset 6 is applied, tolerates the larger increment: an `IDENTITY` insert uses the default
  of the column, which just skips 49 ids per row.
- Rolling back changeset 6 requires rolling back the application first; the new version refuses
  to start against a sequence with increment 1.
- Anything that writes explicit ids (seed data, imports) must keep them below the current
  sequence value, as the existing changesets already do; the sequence is never moved backwards.
- Each instance holds its unallocated block in memory, so a restart or a second instance leaves
  gaps of up to 49 ids. Ids were never promised to be gapless and are not exposed as a count
  anywhere.

## Consequences
- `POST /order` with three products drops from 6 to 4 prepared statements (plus one `nextval`
  per 50 orders); several entities flushed together become one batched insert per table.
- Generated ids are visible as soon as an entity is persisted, before the flush, which simplifies
  any future code that links entities before commit.
- The id-assignment contract changes from "the next serial value" to "a value from the current
  block"; nothing in the API or the tests depended on consecutive ids.
