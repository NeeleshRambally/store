const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');

const generate = () => execFileSync(process.execPath, [path.join(__dirname, 'generateData.js')], { encoding: 'utf8' });

test('two runs produce the same file', () => {
    const first = generate().split('\n');
    const second = generate().split('\n');
    assert.equal(first.length, second.length);
    const differing = first.filter((line, i) => line !== second[i]).length;
    assert.equal(differing, 0, `${differing} of ${first.length} lines differ between two runs`);
});
