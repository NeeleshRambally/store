# Data generation
The script in here generates the sample data in `src/main/resources/db/changelog/data.sql`.
It requires node and npm.

# Installation
```shell
npm ci
```
installs the locked `@faker-js/faker` 7.6.0 from the public npm registry.

# Execution
```shell
node ./generateData.js > ../src/main/resources/db/changelog/data.sql
```

# Creating more data
The row counts come from two environment variables, `CUSTOMERS` (default 100) and `ORDERS`
(default 10000); both must be positive integers, otherwise the script prints a usage line and
exits 1. For example:
```shell
CUSTOMERS=1000 ORDERS=200000 node ./generateData.js > ../src/main/resources/db/changelog/data.sql
```
Products and the `order_product` links are derived in SQL from the orders by changeset 5, so
nothing else needs regenerating. The seed changesets are immutable once applied: after
regenerating against a database that already ran them, drop and recreate the database.

# Checking the generator
```shell
npm test
```
is the generator's own check (determinism, the two size variables and their rejection); the
`build` job of `.github/workflows/ci.yml` and the `generator-test` job of `.gitlab-ci.yml` run
`npm ci && npm test` in this directory.

# Known advisory
`npm audit` reports GHSA-qxc2-j82w-r537 (high, CWE-95) against `@faker-js/faker` 7.6.0: code
execution through `helpers.fake`, affecting every release up to 10.4.0. The generator never
calls `helpers.fake` (`grep -n helpers generateData.js` prints nothing; it uses
`faker.name.fullName`, `faker.commerce.productName` and `faker.datatype.number` on a fixed
seed) and runs only on a developer machine against this checked-in script, so the advisory is
accepted. The fix is on the Faker 10 line (`npm audit` offers 10.6.0, a semver-major), which
renames `faker.name` to `faker.person` along the way and changes every seeded value, so taking
it would regenerate `data.sql` and break every test that asserts the seed; that is the reason
for staying on 7.6.0 (see "Notes"). `npm ci` does not fail on audit findings, so the
pipelines' `npm ci && npm test` step is unaffected. Reviewed 2026-10-19; re-check when the
seed is next regenerated, which is when the major can be taken.

# Notes
The generator is fully seeded: the customer names, the order descriptions and the customer of
each order all come from Faker's seeded generator, so running it twice produces the same file
(`npm test` checks that). The committed `data.sql` predates that change (the customer of each
order used to come from an unseeded `Math.random()`) and is kept as imported, because the
tests assert its values; regenerating it would change every seeded row.

The script emits customers and orders only. Products and the `order_product` links are derived
in SQL by changeset 5 from whatever orders exist, so a regenerated file keeps the "one or more
products per order" invariant without any change to the changelog. The script writes explicit
ids; changeset 3 moves the database sequences past the highest id after the data is loaded.
