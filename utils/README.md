# Data generation
The script in here generates the sample data in `src/main/resources/db/changelog/data.sql`.
It requires node and npm.

# Installation
```shell
npm install
```

# Execution
```shell
node ./generateData.js > ../src/main/resources/db/changelog/data.sql
```

# Notes
The generator is fully seeded: the customer names, the order descriptions and the customer of
each order all come from Faker's seeded generator, so running it twice produces the same file
(`npm test` checks that). The committed `data.sql` predates that change (the customer of each
order used to come from an unseeded `Math.random()`) and is kept as imported, because the
tests assert its values; regenerating it would change every seeded row.

The script emits customers and orders only. Products and the `order_product` links are derived
in SQL by changeset 5 from whatever orders exist, so a regenerated file keeps the "one or more
products per order" invariant without any change to the changelog. The script writes explicit
ids; changeset 3 moves the database sequences past the highest id after the data is loaded. If
you regenerate against a database that already ran the seed changeset, drop and recreate the
database; the seed changeset is immutable once applied.
