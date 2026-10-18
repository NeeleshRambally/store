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
