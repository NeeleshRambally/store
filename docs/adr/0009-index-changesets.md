# ADR 0009: Index changesets

## Status
Accepted

## Context
Changesets 3 (`3-index-order-customer-id`), 4 (`4-customer-name-trigram-index`) and 7
(`7-sort-indexes-with-id-tie-break`) build indexes on tables that already exist, with a plain
`CREATE INDEX`. A plain build takes a `SHARE` lock on its table: reads go on, but every `INSERT`,
`UPDATE` and `DELETE` on the table waits. Liquibase runs each changeset in one transaction, so
the lock is held until the changeset commits, not only while its index is built: changeset 7
builds three indexes in one transaction, and `order` stays closed to writes until the `product`
and `customer` builds have finished as well. A build that is still waiting for its lock also
holds back every write that arrives after it. Changeset 5's `idx_order_product_product_id` is
different: it is built in the changeset that creates `order_product`, on a table no other
session can see before that changeset commits, so the build blocks nothing. The changeset's
foreign key does take a `SHARE ROW EXCLUSIVE` lock on `order`, which holds writes to `order` for
the milliseconds that its DDL on two new, empty tables takes.

Liquibase stores the checksum of every applied changeset in `databasechangelog` and refuses to
migrate when an applied changeset no longer matches it (`Validation Failed: 1 changesets check
sum`, and the process exits 1), so changesets 3, 4 and 7 cannot be rewritten. A database that has
applied them keeps them as they are, and one that has not applies them as written on its next
migration: a database created by the original code, whose changelog ended at changeset 2, runs
all three against its populated tables the first time this service migrates it.

The builds were timed by hand on 2026-10-20 on PostgreSQL 16.2 (Docker Desktop on arm64): a
database migrated by the service's jar with `LIQUIBASE_CONTEXTS=prod`, loaded with the 200,000
customers of `docs/PERFORMANCE.md` ("Substring search") and 1,000,000 orders spread over them
with 1,000 distinct descriptions, the indexes dropped before the load and each one then built
five times inside a transaction, warm:

| Changeset | Index | Table | Plain build |
|-----------|-------|-------|-------------|
| 3 | `idx_order_customer_id` | 1,000,000 orders | 121 to 149 ms |
| 4 | `idx_customer_name_trgm` (GIN, trigrams) | 200,000 customers | 260 to 277 ms |
| 7 | `idx_order_description_id` | 1,000,000 orders | 836 to 901 ms |
| 7 | `idx_customer_name_id` | 200,000 customers | 109 to 132 ms |

Changeset 7 as written, with the empty `product` table of a database migrated without the seed,
took 0.97 to 1.02 s in three runs, all of it with `order` closed to writes.

## Decision
- Changesets 3, 4 and 7 stay as they are. Applying them to a populated database is planned as a
  write window sized from its tables, best by timing the changesets on a restored copy of that
  database; the figures above give the order of magnitude.
- Every future changeset that indexes an existing table builds one index, with
  `CREATE INDEX CONCURRENTLY IF NOT EXISTS`, and declares `runInTransaction: false`, because
  PostgreSQL refuses a concurrent build inside a transaction block (`CREATE INDEX CONCURRENTLY
  cannot run inside a transaction block`). A concurrent build takes a `SHARE UPDATE EXCLUSIVE`
  lock, under which reads and writes go on: an `INSERT` into `customer` committed in 1.3 ms
  while a concurrent build of `idx_customer_name_id` was in progress. It costs time instead: it
  reads the table twice and then waits for every transaction older than itself (one open
  transaction held that build for 4.5 s), and the two `customer` builds above took 489 to 552 ms
  and 132 to 167 ms concurrently. One index per changeset keeps a failure to that index.
- A concurrent build that fails part-way, for example when a statement timeout cancels it, leaves
  an `INVALID` index behind, which no query uses and which `IF NOT EXISTS` takes for an existing
  index: rerunning the build prints `relation "..." already exists, skipping`, Liquibase records
  the changeset as executed and the index stays invalid. The changeset therefore drops an index
  of the same name concurrently first, so that a rerun after a failure rebuilds it.

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
It was run through the service's Liquibase on 2026-10-20 against the tables above: with a 60 ms
statement timeout the build failed, left its index invalid and was not recorded, and the rerun
with the timeouts off dropped and rebuilt the index and recorded the changeset; the same
changeset without the `DROP` was recorded over the invalid index; and the rollback, which
Liquibase also runs outside a transaction, removed the index.

## Consequences
- The `migrate` job runs with `DB_STATEMENT_TIMEOUT` and `DB_SOCKET_TIMEOUT` set to `0`
  (`compose.yaml`, ADR 0008), and a Kubernetes `Job` needs the same: the service's 10 s
  statement timeout would cancel a longer build, and its 15 s socket timeout would drop the
  connection, because a backend sends nothing while it builds (with `DB_SOCKET_TIMEOUT=1` a
  concurrent build of about a second failed with `Read timed out` and its changeset was not
  recorded). Nothing else then bounds a migration statement: a build that waits for its lock
  waits as long as the transaction holding the table, and behind a plain build every write to
  the table waits too, so a rollout first checks `pg_stat_activity` for long transactions. A
  migration that runs in-process at start-up instead (`./gradlew bootRun`, or the image run on
  its own) keeps the service's two limits unless it is given the same two variables.
- On the Compose stack nothing writes during a migration, because `store` starts only after
  `migrate` has exited; the locks matter where the previous version keeps serving while the job
  runs, as in a rolling Kubernetes deployment.
- The suite migrates empty or seeded tables only, where a plain build takes milliseconds, so it
  would not notice a new changeset that breaks the rule; the rule rests on the review of each
  new changeset.
- Changeset 8 is the first to follow the rule. `8-index-order-customer-id-id` and
  `8-index-order-product-product-id-order-id` build one index each in the form above, and
  `8-drop-index-order-product-product-id` drops the index the second replaces with
  `DROP INDEX CONCURRENTLY IF EXISTS`, which does not lock out reads or writes either, with a
  rollback that builds it again in the same concurrent form. Through the service's Liquibase,
  run as the `migrate` job runs it with the jar of `062b418` on 2026-10-20 against the 1,000,000
  orders and 2,001,000 order lines of `docs/PERFORMANCE.md` ("Filtered pages"), the two builds
  took 307 to 447 ms and 584 to 736 ms and the drop 4 to 5 ms in five runs, and five concurrent
  builds of each in psql took 277 to 323 ms and 550 to 636 ms; such times vary with the host and
  its load.
