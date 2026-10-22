# ADR 0009: Index changesets

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record covers how the Liquibase changesets that index existing tables lock those tables, and
the form every new index changeset takes. The queries the indexes serve are in
[Indexes](../PERFORMANCE.md#indexes), and how the migration job runs is in
[Start-up and migrations](../DESIGN.md#start-up-and-migrations).

## Status
Accepted

## Context
Three changesets index tables that may already hold data, and their plain builds stop every write
to the table until the changeset commits. They cannot be changed, because Liquibase refuses to run
when an applied changeset no longer matches its checksum.
- **Plain builds block writes.** Changesets 3 (`3-index-order-customer-id`), 4
  (`4-customer-name-trigram-index`) and 7 (`7-sort-indexes-with-id-tie-break`) build indexes on
  existing tables with a plain `CREATE INDEX`. A plain build takes a `SHARE` lock on its table:
  reads go on, but every `INSERT`, `UPDATE` and `DELETE` on the table waits.
- **The lock lasts until the commit.** Liquibase runs each changeset in one transaction, so the
  lock is held until the changeset commits, not only while its index is built. Changeset 7 builds
  three indexes in one transaction, so `order` stays closed to writes until the `product` and
  `customer` builds have finished as well.
- **A waiting build queues writes.** A build that is still waiting for its lock also holds back
  every write that arrives after it.
- **Changeset 5 blocks nothing.** It builds `idx_order_product_product_id` in the changeset that
  creates `order_product`, on a table no other session can see before that changeset commits. Its
  foreign key does take a `SHARE ROW EXCLUSIVE` lock on `order`, which holds writes to `order` for
  the milliseconds that its DDL on two new, empty tables takes.
- **Applied changesets cannot be rewritten.** Liquibase stores the checksum of every applied
  changeset in `databasechangelog`. It refuses to migrate when an applied changeset no longer
  matches (`Validation Failed: 1 changesets check sum`, and the process exits 1).
- **Some databases still have to apply them.** A database that has applied changesets 3, 4 and 7
  keeps them as they are. One that has not applies them as written on its next migration. A
  database created by the original code, whose changelog ended at changeset 2, runs all three
  against its populated tables the first time this service migrates it.

### Measured builds
The builds were timed by hand on 2026-10-20 on PostgreSQL 16.2 (Docker Desktop on arm64). The
database was migrated by the service's jar with `LIQUIBASE_CONTEXTS=prod` and loaded with the
200,000 customers of [Substring search](../PERFORMANCE.md#substring-search) and 1,000,000 orders
spread over them with 1,000 distinct descriptions. The indexes were dropped before the load, and
each was then built five times inside a transaction, warm:

| Changeset | Index | Table | Plain build |
|-----------|-------|-------|-------------|
| 3 | `idx_order_customer_id` | 1,000,000 orders | 121 to 149 ms |
| 4 | `idx_customer_name_trgm` (GIN, trigrams: three-character fragments of the name) | 200,000 customers | 260 to 277 ms |
| 7 | `idx_order_description_id` | 1,000,000 orders | 836 to 901 ms |
| 7 | `idx_customer_name_id` | 200,000 customers | 109 to 132 ms |

Changeset 7 as written, with the empty `product` table of a database migrated without the seed,
took 0.97 to 1.02 s in three runs, all of it with `order` closed to writes.

## Decision
Changesets 3, 4 and 7 stay as written. Every later changeset that indexes an existing table builds
one index concurrently, so reads and writes go on while it builds.
- **The old changesets stay.** Applying changesets 3, 4 and 7 to a populated database is planned
  as a write window sized from its tables. The best estimate comes from timing the changesets on a
  restored copy of that database; the figures above give the order of magnitude.
- **One concurrent build per changeset.** Every future changeset that indexes an existing table
  builds one index, with `CREATE INDEX CONCURRENTLY IF NOT EXISTS`, and declares
  `runInTransaction: false`. PostgreSQL refuses a concurrent build inside a transaction block
  (`CREATE INDEX CONCURRENTLY cannot run inside a transaction block`). One index per changeset
  keeps a failure to that index.
- **What a concurrent build costs.** It takes a `SHARE UPDATE EXCLUSIVE` lock, under which reads
  and writes go on. An `INSERT` into `customer` committed in 1.3 ms while a concurrent build of
  `idx_customer_name_id` was in progress. It costs time instead. It reads the table twice and then
  waits for every transaction older than itself; one open transaction held that build for 4.5 s.
  Built concurrently, `idx_customer_name_trgm` took 489 to 552 ms and `idx_customer_name_id` 132
  to 167 ms.
- **Drop first, so that a rerun rebuilds.** A concurrent build that fails part-way, for example
  when a statement timeout cancels it, leaves an `INVALID` index behind. No query uses it, but
  `IF NOT EXISTS` takes it for an existing index. Rerunning the build prints
  `relation "..." already exists, skipping`, Liquibase records the changeset as executed, and the
  index stays invalid. The changeset therefore first drops an index of the same name,
  concurrently, so that a rerun after a failure rebuilds it.

The shape of such a changeset, with the rollback the other changesets declare:
```yaml
  - changeSet:
      id: 8-<name>
      author: developer@securitease.com
      runInTransaction: false
      changes:
        - sql:
            dbms: postgresql
            sql: |
              DROP INDEX CONCURRENTLY IF EXISTS idx_<name>;
              CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_<name> ON <table> (<columns>);
      rollback:
        - sql:
            sql: DROP INDEX CONCURRENTLY IF EXISTS idx_<name>;
```
It was run through the service's Liquibase on 2026-10-20 against the tables above:
- with a 60 ms statement timeout the build failed, left its index invalid and was not recorded,
  and the rerun with the timeouts off dropped and rebuilt the index and recorded the changeset;
- the same changeset without the `DROP` was recorded over the invalid index;
- the rollback, which Liquibase also runs outside a transaction, removed the index.

## Consequences
- **The migration job runs without the request limits.** The `migrate` job runs with
  `DB_STATEMENT_TIMEOUT` and `DB_SOCKET_TIMEOUT` set to `0` (`compose.yaml`,
  [ADR 0008](0008-operational-posture.md#database-calls-are-bounded-in-time)), and a Kubernetes
  `Job` needs the same. The service's 10 s statement timeout would cancel a longer build, and its
  15 s socket timeout would drop the connection, because a backend sends nothing while it builds.
  With `DB_SOCKET_TIMEOUT=1`, a concurrent build of about a second failed with `Read timed out`
  and its changeset was not recorded.
- **Nothing else bounds a migration statement.** A build that waits for its lock waits as long as
  the transaction holding the table, and behind a plain build every write to the table waits too.
  A rollout therefore first checks `pg_stat_activity` for long transactions.
- **In-process migrations keep the limits.** A migration that runs in-process at start-up instead
  (`./gradlew bootRun`, or the image run on its own) keeps the service's two limits unless it is
  given the same two variables.
- **The locks matter on a live deployment.** On the Compose stack nothing writes during a
  migration, because `store` starts only after `migrate` has exited. The locks matter where the
  previous version keeps serving while the job runs, as in a rolling Kubernetes deployment.
- **Only review enforces the rule.** The suite migrates empty or seeded tables only, where a plain
  build takes milliseconds, so it would not notice a new changeset that breaks the rule. The rule
  rests on the review of each new changeset.
- **Changeset 8 is the first to follow it.** `8-index-order-customer-id-id` and
  `8-index-order-product-product-id-order-id` build one index each in the form above.
  `8-drop-index-order-product-product-id` drops the index that the second replaces with
  `DROP INDEX CONCURRENTLY IF EXISTS`, which does not lock out reads or writes either. Its rollback
  builds that index again in the same concurrent form.
- **Changeset 8 measured.** It was run as the `migrate` job runs it, through the service's
  Liquibase with the jar of `1bd1c00` on 2026-10-20, against the 1,000,000 orders and 2,001,000
  order lines of [Filtered pages](../PERFORMANCE.md#filtered-pages). In five runs the two builds
  took 307 to 447 ms and 584 to 736 ms, and the drop 4 to 5 ms. Five concurrent builds of each in
  psql took 277 to 323 ms and 550 to 636 ms. Such times vary with the host and its load.
