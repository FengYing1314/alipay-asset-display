import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

for (const name of ['total-assets', 'fund-summary']) {
  test(`${name} 可作为表达式嵌入 Android 调用且未超出读取上限`, () => {
    const bytes = readFileSync(
      new URL(`../../main/resources/io/github/fengying/alipaydisplay/${name}.js`, import.meta.url),
    );
    assert.ok(bytes.length > 0 && bytes.length <= 96000);
    const expression = bytes.toString('utf8').trim().replace(/;\s*$/, '');
    assert.doesNotThrow(() => new vm.Script(`(${expression})({})`));
  });
}
