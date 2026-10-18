const { faker } = require('@faker-js/faker');

const positiveInteger = (name, fallback) => {
    const raw = process.env[name] ?? fallback;
    const value = Number(raw);
    if (!/^\d+$/.test(raw) || value < 1) {
        process.stderr.write('usage: CUSTOMERS=<positive integer> ORDERS=<positive integer> node generateData.js\n');
        process.exit(1);
    }
    return value;
};

const N = positiveInteger('CUSTOMERS', '100');
const M = positiveInteger('ORDERS', '10000');

faker.seed(42);

const escape = (value) => value.replace(/'/g, "''");

for (let i = 1; i <= N; i++) {
    console.log(`INSERT INTO customer (id, name) VALUES (${i}, '${escape(faker.name.fullName())}');`);
}

for (let i = 1; i <= M; i++) {
    const customerId = faker.datatype.number({ min: 1, max: N });
    console.log(`INSERT INTO "order" (id, description, customer_id) VALUES (${i}, '${escape(faker.commerce.productName())}', ${customerId});`);
}
