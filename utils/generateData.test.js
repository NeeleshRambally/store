const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const path = require('node:path');

const script = path.join(__dirname, 'generateData.js');
const usage = 'usage: CUSTOMERS=<positive integer> ORDERS=<positive integer> node generateData.js';

const generate = (env = {}) => execFileSync(process.execPath, [script], { encoding: 'utf8', env: { ...process.env, ...env } });

const run = (env) => spawnSync(process.execPath, [script], { encoding: 'utf8', env: { ...process.env, ...env } });

test('two runs produce the same file', () => {
    const first = generate().split('\n');
    const second = generate().split('\n');
    assert.equal(first.length, second.length);
    const differing = first.filter((line, i) => line !== second[i]).length;
    assert.equal(differing, 0, `${differing} of ${first.length} lines differ between two runs`);
});

test('CUSTOMERS and ORDERS set the number of rows', () => {
    const lines = generate({ CUSTOMERS: '3', ORDERS: '5' }).trim().split('\n');
    const customers = lines.filter((line) => line.startsWith('INSERT INTO customer '));
    const orders = lines.filter((line) => line.startsWith('INSERT INTO "order" '));
    assert.equal(customers.length, 3);
    assert.equal(orders.length, 5);
    assert.equal(lines.length, 8);
    const customerIds = orders.map((line) => Number(line.match(/, (\d+)\);$/)[1]));
    assert.ok(customerIds.every((id) => id >= 1 && id <= 3), `customer ids ${customerIds} outside 1..3`);
});

test('a non-numeric CUSTOMERS is rejected with the usage line', () => {
    const result = run({ CUSTOMERS: 'abc' });
    assert.notEqual(result.status, 0);
    assert.equal(result.stderr.trim(), usage);
    assert.equal(result.stdout, '');
});

test('a zero ORDERS is rejected with the usage line', () => {
    const result = run({ ORDERS: '0' });
    assert.notEqual(result.status, 0);
    assert.equal(result.stderr.trim(), usage);
    assert.equal(result.stdout, '');
});
