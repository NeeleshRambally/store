# Data generation
The script in here can be used to generate a lot of sample data. It requires node and npm to run

# Installation
```shell
npm install
```

# Execution
```shell
node ./generateData.js > ../src/main/resources/db/changelog/data.sql
```

# Notes
If you change the liquibase migration, you'll need to hack the liquibase changelog. Or drop and recreate your database