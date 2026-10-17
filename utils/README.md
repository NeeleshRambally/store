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
The generator is seeded, so running it twice produces the same file. It writes explicit ids;
changeset 3 moves the database sequences past the highest id after the data is loaded, so
regenerating the data does not require any manual changes to the Liquibase changelog. If you
regenerate against a database that already ran the seed changeset, drop and recreate the
database; the seed changeset is immutable once applied.
