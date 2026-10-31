'use strict';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import test from 'node:test';
const source = fs.readFileSync(
  new URL('../../main/resources/io/github/fengying/alipaydisplay/total-assets.js', import.meta.url),
  'utf8',
);

// 只模拟本脚本使用的DOM和MutationObserver语义；所有金额均为合成数据。
function fixture(profile = 'total-assets') {
  const observers = new Set();
  const weakMaps = [];
  const capturedBindings = new Set();
  class TrackedWeakMap extends WeakMap {
    constructor() {
      super();
      weakMaps.push(this);
    }
    set(key, value) {
      if (value && value.text && value.aria) capturedBindings.add(value);
      return super.set(key, value);
    }
  }
  let mutations = 0;
  class Node {
    constructor(type) {
      this.nodeType = type;
      this.parentElement = null;
      this.ownerDocument = null;
    }
    get isConnected() {
      return this.ownerDocument && this.ownerDocument.body.contains(this);
    }
    contains(node) {
      for (let current = node; current; current = current.parentElement)
        if (current === this) return true;
      return false;
    }
  }
  function notify(record) {
    mutations++;
    for (const observer of observers) {
      if (
        observer.targets.some(
          ([target, options]) =>
            (target === record.target || (options.subtree && target.contains(record.target))) &&
            options[record.type] &&
            (record.type !== 'attributes' ||
              !options.attributeFilter ||
              options.attributeFilter.includes(record.attributeName)),
        )
      ) {
        observer.records.push(record);
      }
    }
  }
  class Text extends Node {
    constructor(value) {
      super(3);
      this.value = value;
    }
    get nodeValue() {
      return this.value;
    }
    set nodeValue(value) {
      const oldValue = this.value;
      this.value = String(value);
      notify({ type: 'characterData', target: this, oldValue });
      if (this.afterWrite) this.afterWrite(this.value);
    }
    get textContent() {
      return this.value;
    }
  }
  class Element extends Node {
    constructor(tag = 'DIV', attrs = {}) {
      super(1);
      this.tagName = tag;
      this.childNodes = [];
      this.attrs = { ...attrs };
    }
    get children() {
      return this.childNodes.filter((node) => node.nodeType === 1);
    }
    get firstChild() {
      return this.childNodes[0] || null;
    }
    get textContent() {
      return this.childNodes.map((node) => node.textContent).join('');
    }
    get hidden() {
      return Object.hasOwn(this.attrs, 'hidden');
    }
    getAttribute(name) {
      return Object.hasOwn(this.attrs, name) ? this.attrs[name] : null;
    }
    setAttribute(name, value) {
      const oldValue = this.getAttribute(name);
      this.attrs[name] = String(value);
      notify({ type: 'attributes', target: this, attributeName: name, oldValue });
    }
    append(...nodes) {
      for (const node of nodes) {
        node.parentElement = this;
        this.childNodes.push(node);
        adopt(node, this.ownerDocument);
      }
      notify({ type: 'childList', target: this, addedNodes: nodes, removedNodes: [] });
      return this;
    }
    replace(old, next) {
      const index = this.childNodes.indexOf(old);
      assert.notEqual(index, -1);
      this.childNodes[index] = next;
      old.parentElement = null;
      next.parentElement = this;
      adopt(next, this.ownerDocument);
      notify({ type: 'childList', target: this, addedNodes: [next], removedNodes: [old] });
    }
    remove(node) {
      const index = this.childNodes.indexOf(node);
      this.childNodes.splice(index, 1);
      node.parentElement = null;
      notify({ type: 'childList', target: this, addedNodes: [], removedNodes: [node] });
    }
  }
  function adopt(node, doc) {
    node.ownerDocument = doc;
    if (node.childNodes) for (const child of node.childNodes) adopt(child, doc);
  }
  class Observer {
    constructor(callback) {
      this.callback = callback;
      this.records = [];
      this.targets = [];
      observers.add(this);
    }
    observe(target, options) {
      this.targets = this.targets.filter(([node]) => node !== target);
      this.targets.push([target, options]);
    }
    takeRecords() {
      const records = this.records;
      this.records = [];
      return records;
    }
    disconnect() {
      this.targets = [];
      this.records = [];
    }
  }
  const body = new Element('BODY');
  const doc = {
    body,
    querySelectorAll(selector) {
      const tokens = selector.split(',').map((token) => token.trim());
      const result = [],
        pending = [body];
      while (pending.length) {
        const node = pending.shift();
        if (
          tokens.some((token) =>
            token[0] === '#'
              ? node.getAttribute('id') === token.slice(1)
              : (node.getAttribute('class') || '').split(/\s+/).includes(token.slice(1)),
          )
        )
          result.push(node);
        pending.push(...node.children);
      }
      return result;
    },
  };
  adopt(body, doc);
  function text(value) {
    return new Text(value);
  }
  function element(tag, attrs, ...children) {
    return new Element(tag, attrs).append(...children);
  }
  const totalText = text('80.00');
  const totalLeaf = element('SPAN', {}, totalText);
  const total = element(
    'DIV',
    { role: 'button', 'aria-label': '总资产80.00元，昨日收益3.21元' },
    element('SPAN', {}, totalLeaf),
  );
  // 实机总额同时有一个继承aria-hidden的无叶按钮及一个可见主金额按钮。
  const totalClone = element('DIV', { role: 'button', 'aria-label': '总资产80.00元' });
  const totalCloneParent = element('DIV', { 'aria-hidden': 'true' }, totalClone);
  const overview = element(
    'DIV',
    { id: 'overview-card' },
    element('DIV', {}, totalCloneParent, total),
  );
  function row(label, amount, gain) {
    const mainText = text(amount),
      gainText = text(gain);
    const name = element('DIV', {}, element('DIV', {}, text(label)));
    const main = element('DIV', {}, element('SPAN', {}, mainText));
    const income = element('DIV', {}, element('SPAN', {}, gainText));
    const right = element('DIV', {}, main, income);
    const scope = element(
      'DIV',
      { 'aria-label': label + amount + '元，收益' + gain + '元' },
      name,
      right,
    );
    return { scope, name, main, right, mainText, income, gainText };
  }
  const balance = row('余额', '10.00', '0.10');
  const yuebao = row('余额宝', '20.00', '0.20');
  const fund = row('基金', '30.00', '-0.30');
  const asset = element(
    'DIV',
    { class: 'asset-card' },
    element('DIV', {}, balance.scope, yuebao.scope, fund.scope),
  );
  const yuebaoDetailsText = text('55.00');
  const yuebaoDetailsSpan = element('SPAN', { 'aria-hidden': 'true' }, yuebaoDetailsText);
  const yuebaoDetailsIcon = element('SVG', { 'aria-hidden': 'true' }, element('PATH', {}));
  const yuebaoDetailsScope = element(
    'DIV',
    { id: 'amountView', role: 'button', 'aria-label': '总金额55.00元' },
    yuebaoDetailsSpan,
    yuebaoDetailsIcon,
  );
  const yuebaoDetailsCaption = element('SPAN', { 'aria-hidden': 'true' }, text('总金额(元)'));
  const yuebaoDetailsTitle = element(
    'DIV',
    {},
    yuebaoDetailsCaption,
    element('DIV', {}),
    element('DIV', {}),
  );
  const yuebaoDetailsRoot = element('DIV', {}, yuebaoDetailsTitle, yuebaoDetailsScope);
  const availableText = text('44.00'),
    profitText = text('0.08');
  const available = element('DIV', { 'aria-label': '可用金额44.00元' }, availableText);
  const profit = element('DIV', { 'aria-label': '昨日收益0.08元' }, profitText);
  body.append(overview, asset, yuebaoDetailsRoot, available, profit);
  const win = {
    location: { href: 'https://example.test/www/index.html?scene=fixture#assets' },
    crypto: webcrypto,
  };
  const context = vm.createContext({
    window: win,
    document: doc,
    URL,
    Uint32Array,
    WeakMap: TrackedWeakMap,
    MutationObserver: Observer,
    getComputedStyle(node) {
      const style = node.getAttribute('style') || '';
      return {
        display: /display:\s*none/.test(style) ? 'none' : 'block',
        visibility: /visibility:\s*hidden/.test(style) ? 'hidden' : 'visible',
      };
    },
  });
  const dispatch = vm.runInContext(source, context);
  const base = {
    nativeSession: 'session_1',
    route: 'https://example.test/www/index.html',
    ...(profile === 'total-assets' ? {} : { profile }),
  };
  function prepare(overrides = {}) {
    return dispatch({ op: 'prepare', ...base, ...overrides });
  }
  const initial = prepare();
  const command = {
    ...base,
    nonce: initial.nonce,
    revision: '9007199254740993',
    amounts: { balance: '111.11', yuebao: '222.22', fund: '333.33', total: '9,999.99' },
  };
  function apply(overrides = {}) {
    return dispatch({ op: 'apply', ...command, ...overrides });
  }
  function stop(overrides = {}) {
    return dispatch({ op: 'stop', ...command, ...overrides });
  }
  function abandon(overrides = {}) {
    return dispatch({ op: 'abandon', ...command, ...overrides });
  }
  function memoryState() {
    const nodes = [
      totalText,
      total,
      balance.mainText,
      balance.scope,
      yuebao.mainText,
      yuebao.scope,
      fund.mainText,
      fund.scope,
      yuebaoDetailsText,
      yuebaoDetailsScope,
    ];
    return {
      owners: weakMaps
        .slice(0, 2)
        .reduce((count, map) => count + nodes.filter((node) => map.has(node)).length, 0),
      cleared: Array.from(capturedBindings).every((binding) =>
        [binding.text, binding.aria].every(
          (field) => field.host === null && field.applied === null && !field.fresh && !field.owned,
        ),
      ),
      observations: Array.from(observers).reduce(
        (count, observer) => count + observer.targets.length,
        0,
      ),
    };
  }
  function flush() {
    for (let round = 0; round < 20; round++) {
      const pending = Array.from(observers).filter((observer) => observer.records.length);
      if (!pending.length) return;
      for (const observer of pending) observer.callback(observer.takeRecords());
    }
    throw new Error('MutationObserver did not settle');
  }
  return {
    dispatch,
    context,
    win,
    doc,
    body,
    element,
    text,
    overview,
    asset,
    total,
    totalText,
    totalLeaf,
    totalClone,
    totalCloneParent,
    details: {
      text: yuebaoDetailsText,
      span: yuebaoDetailsSpan,
      scope: yuebaoDetailsScope,
      icon: yuebaoDetailsIcon,
      title: yuebaoDetailsTitle,
      caption: yuebaoDetailsCaption,
      root: yuebaoDetailsRoot,
      available,
      availableText,
      profit,
      profitText,
    },
    balance,
    yuebao,
    fund,
    command,
    prepare,
    apply,
    stop,
    abandon,
    flush,
    memoryState,
    mutationCount: () => mutations,
  };
}

test('prepare只产生私有nonce，四值独立，金额叶与aria首段之外保持不变', () => {
  const f = fixture(),
    before = f.mutationCount();
  assert.equal(f.prepare().nonce, f.command.nonce);
  assert.equal(f.mutationCount(), before);
  const nodes = [f.totalLeaf, f.balance.income, f.yuebao.income, f.fund.income];
  assert.equal(f.apply().applied, 4);
  assert.equal(f.totalText.nodeValue, '9,999.99');
  assert.equal(f.balance.mainText.nodeValue, '111.11');
  assert.equal(f.yuebao.mainText.nodeValue, '222.22');
  assert.equal(f.fund.mainText.nodeValue, '333.33');
  assert.equal(f.total.getAttribute('aria-label'), '总资产9,999.99元，昨日收益3.21元');
  assert.equal(f.fund.scope.getAttribute('aria-label'), '基金333.33元，收益-0.30元');
  assert.equal(f.balance.gainText.nodeValue, '0.10');
  assert.equal(f.fund.gainText.nodeValue, '-0.30');
  assert.deepEqual(nodes, [f.totalLeaf, f.balance.income, f.yuebao.income, f.fund.income]);
  const after = f.mutationCount();
  f.flush();
  f.apply();
  assert.equal(f.mutationCount(), after, '幂等apply和自写MO不应继续改DOM');
});

test('aria必须固定名称前缀且首金额同值，拒绝只在收益出现主金额的情况', () => {
  for (const aria of [
    '收益80.00元，总资产90.00元',
    '总资产81.00元，收益80.00元',
    '总资产合计80.00元',
    '基金80.00元',
    '总资产****元',
  ]) {
    const f = fixture();
    f.total.setAttribute('aria-label', aria);
    f.apply();
    assert.equal(f.totalText.nodeValue, '80.00', aria);
    assert.equal(f.total.getAttribute('aria-label'), aria);
  }
});

test('格式规范化同值允许分组差异，后续aria数字段完整保留', () => {
  const f = fixture();
  f.totalText.nodeValue = '1,234.5';
  f.total.setAttribute('aria-label', '总资产（元）：1234.50，收益999.00元');
  assert.equal(f.apply().applied, 4);
  assert.equal(f.total.getAttribute('aria-label'), '总资产（元）：9,999.99，收益999.00元');
});

test('隐藏信号使原文失效；仅恢复描述不得回放旧金额，fresh文本和描述齐备后重识别', () => {
  const f = fixture();
  f.apply();
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  f.flush();
  assert.equal(f.totalText.nodeValue, '****');
  f.total.setAttribute('aria-label', '总资产90.00元，昨日收益4.00元');
  f.flush();
  assert.equal(f.totalText.nodeValue, '****');
  f.totalText.nodeValue = '90.00';
  f.total.setAttribute('aria-label', '总资产90.00元，昨日收益4.00元');
  f.flush();
  assert.equal(f.totalText.nodeValue, '9,999.99');
  assert.equal(f.total.getAttribute('aria-label'), '总资产9,999.99元，昨日收益4.00元');
});

test('宿主更新同一字段后只保存最新host值，停用恢复新值且收益不变', () => {
  const f = fixture();
  f.apply();
  f.balance.mainText.nodeValue = '15.25';
  f.balance.scope.setAttribute('aria-label', '余额15.25元，收益8.50元');
  f.flush();
  assert.equal(f.balance.mainText.nodeValue, '111.11');
  assert.equal(f.stop().status, 'stopped');
  assert.equal(f.balance.mainText.nodeValue, '15.25');
  assert.equal(f.balance.scope.getAttribute('aria-label'), '余额15.25元，收益8.50元');
});

test('重复根、重复语义、未知金额子结构均不猜测目标', () => {
  const f = fixture();
  f.body.append(f.element('DIV', { id: 'overview-card' }));
  assert.equal(f.apply().applied, 0);
  assert.equal(f.totalText.nodeValue, '80.00');
  const g = fixture();
  g.asset.append(
    g.element(
      'DIV',
      { 'aria-label': '基金44.00元' },
      g.element('DIV', {}, g.text('基金')),
      g.element(
        'DIV',
        {},
        g.element('DIV', {}, g.element('SPAN', {}, g.text('44.00'))),
        g.element('DIV', {}),
      ),
    ),
  );
  g.apply();
  assert.equal(g.fund.mainText.nodeValue, '30.00');
  const h = fixture();
  h.totalLeaf.append(h.element('SPAN', {}, h.text('1')));
  h.apply();
  assert.equal(h.totalText.nodeValue, '80.00');
});

test('继承aria-hidden或CSS隐藏的克隆不参与总额唯一性，两个可见按钮则拒绝', () => {
  const f = fixture();
  assert.equal(f.apply().applied, 4, '隐藏克隆不影响唯一可见总额');
  const g = fixture();
  g.totalCloneParent.setAttribute('aria-hidden', 'false');
  g.apply();
  assert.equal(g.totalText.nodeValue, '80.00', '两个可见总额按钮不能猜测');
  const h = fixture();
  h.totalCloneParent.setAttribute('aria-hidden', 'false');
  h.totalCloneParent.setAttribute('style', 'display:none');
  assert.equal(h.apply().applied, 4);
});

test('真实隐藏模板outer span直接掩码与恢复后的内数字span重新识别', () => {
  const f = fixture();
  f.apply();
  const outer = f.total.children[0];
  outer.remove(f.totalLeaf);
  const mask = f.text('****');
  outer.append(mask);
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  f.flush();
  const hidden = f.apply();
  assert.equal(hidden.targets, 4);
  assert.equal(hidden.hidden, 1);
  assert.equal(hidden.applied, 3);
  assert.equal(mask.nodeValue, '****');
  outer.remove(mask);
  const restoredText = f.text('93.00'),
    restoredLeaf = f.element('SPAN', {}, restoredText);
  outer.append(restoredLeaf);
  f.total.setAttribute('aria-label', '总资产93.00元，昨日收益7.00元');
  f.flush();
  assert.equal(restoredText.nodeValue, '9,999.99');
  assert.equal(f.apply().applied, 4);
  f.stop();
  assert.equal(restoredText.nodeValue, '93.00');
});

test('DOM叶被替换时不把旧原值用于新节点，待新描述后才能应用', () => {
  const f = fixture();
  f.apply();
  const replacement = f.element('SPAN', {}, f.text('91.00'));
  f.totalLeaf.parentElement.replace(f.totalLeaf, replacement);
  f.flush();
  assert.equal(replacement.textContent, '91.00');
  f.total.setAttribute('aria-label', '总资产91.00元，昨日收益5.00元');
  f.flush();
  assert.equal(replacement.textContent, '9,999.99');
  f.stop();
  assert.equal(replacement.textContent, '91.00');
});

test('同scope同值TextNode反复换代保留仍有效的aria字段，持续完整应用', () => {
  const f = fixture();
  f.apply();
  const originalAria = f.total.getAttribute('aria-label');
  let node = f.totalText;
  for (let iteration = 0; iteration < 5; iteration++) {
    const next = f.text('80.00');
    f.totalLeaf.replace(node, next);
    node = next;
    f.flush();
    assert.equal(f.apply().applied, 4, '第' + iteration + '次换代');
    assert.equal(node.nodeValue, '9,999.99');
    assert.equal(f.total.getAttribute('aria-label'), originalAria);
  }
  f.stop();
  assert.equal(node.nodeValue, '80.00');
  assert.equal(f.total.getAttribute('aria-label'), '总资产80.00元，昨日收益3.21元');
});

test('同scope同值数字SPAN重建也保留aria，基金主金额与收益节点各自管理', () => {
  const f = fixture();
  f.apply();
  const total = f.element('SPAN', {}, f.text('80.00'));
  f.totalLeaf.parentElement.replace(f.totalLeaf, total);
  const fund = f.element('SPAN', {}, f.text('30.00'));
  f.fund.main.replace(f.fund.main.children[0], fund);
  f.flush();
  assert.equal(f.apply().applied, 4);
  assert.equal(total.textContent, '9,999.99');
  assert.equal(fund.textContent, '333.33');
  assert.equal(f.fund.gainText.nodeValue, '-0.30');
});

test('同值TextNode换代遇到中间隐藏不得移交隐藏前aria', () => {
  const f = fixture();
  f.apply();
  f.body.setAttribute('aria-hidden', 'true');
  const node = f.text('80.00');
  f.totalLeaf.replace(f.totalText, node);
  f.body.setAttribute('aria-hidden', 'false');
  f.flush();
  assert.notEqual(f.apply().applied, 4);
  f.stop();
  assert.notEqual(node.nodeValue, '9,999.99');
});

test('换代批次曾加入掩码节点，即使最终为同值数字也不继承旧aria', () => {
  const f = fixture();
  f.apply();
  const mask = f.text('****');
  f.totalLeaf.replace(f.totalText, mask);
  const node = f.text('80.00');
  f.totalLeaf.replace(mask, node);
  f.flush();
  assert.equal(f.apply().applied, 3);
  assert.equal(node.nodeValue, '80.00');
});

test('同值节点换代但scope或root对象变化不得移交aria缓存', () => {
  for (const moveScope of [false, true]) {
    const f = fixture();
    f.apply();
    const clone = f.element(
      'DIV',
      { role: 'button', 'aria-label': f.total.getAttribute('aria-label') },
      f.element('SPAN', {}, f.element('SPAN', {}, f.text('80.00'))),
    );
    if (moveScope) {
      const overview = f.element('DIV', { id: 'overview-card' });
      f.total.parentElement.remove(f.total);
      overview.append(f.total);
      f.body.replace(f.overview, overview);
      f.totalLeaf.replace(f.totalText, f.text('80.00'));
    } else f.total.parentElement.replace(f.total, clone);
    f.flush();
    assert.equal(f.apply().applied, 3);
  }
});

test('旧nonce、旧session、精确字符串revision与同revision冲突都不能写入', () => {
  const f = fixture();
  f.apply();
  const before = f.mutationCount();
  assert.equal(f.apply({ nonce: 'old' }).status, 'stale-session');
  assert.equal(f.apply({ nativeSession: 'old' }).status, 'stale-session');
  assert.equal(f.apply({ revision: '9007199254740992' }).status, 'stale-revision');
  assert.equal(f.apply({ revision: '09' }).status, 'stale-revision');
  assert.equal(
    f.apply({ amounts: { ...f.command.amounts, total: '1.00' } }).status,
    'revision-conflict',
  );
  assert.equal(f.mutationCount(), before);
  const second = f.prepare({ nativeSession: 'session_2' });
  assert.notEqual(second.nonce, f.command.nonce);
  assert.equal(f.prepare().status, 'stale-session');
  assert.equal(f.stop().status, 'stale-session');
  assert.equal(f.mutationCount(), before, 'prepare和旧stop都不改DOM');
});

test('query/hash-only变化使旧nonce立即失效，新prepare只改私有状态', () => {
  const f = fixture();
  f.apply();
  const before = f.mutationCount();
  f.win.location.href = 'https://example.test/www/index.html?scene=next#other';
  assert.equal(f.apply().status, 'stale-session');
  assert.equal(f.stop().status, 'stale-session');
  assert.deepEqual(f.memoryState(), { owners: 0, cleared: true, observations: 0 });
  const prepared = f.prepare({ nativeSession: 'session_2' });
  assert.notEqual(prepared.nonce, f.command.nonce);
  assert.equal(f.mutationCount(), before);
  assert.equal(
    f.apply({ nativeSession: 'session_2', nonce: prepared.nonce, revision: '9007199254740994' })
      .applied,
    0,
  );
  assert.equal(
    f.mutationCount(),
    before,
    '失效后已释放字段，未知原产物等待host重绘，不能重新取得写入归属',
  );
});

test('停用仅恢复仍自有且可信的字段，隐藏后不回放缓存数字', () => {
  const f = fixture();
  f.apply();
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  f.flush();
  const result = f.stop();
  assert.equal(result.status, 'stopped');
  assert.equal(f.totalText.nodeValue, '****');
  assert.equal(f.total.getAttribute('aria-label'), '总资产金额隐藏');
  assert.equal(f.balance.mainText.nodeValue, '10.00');
  assert.equal(f.totalText.nodeValue.includes('80.00'), false);
});

test('外部写回相同模块金额字符串仍是host mutation，不能当成本次自写', () => {
  const f = fixture();
  f.apply();
  f.totalText.nodeValue = f.totalText.nodeValue;
  f.flush();
  assert.equal(f.totalText.nodeValue, '9,999.99', '外部拥有的字段不擅自恢复');
  assert.equal(f.apply().applied, 3);
  f.stop();
  assert.notEqual(f.totalText.nodeValue, '80.00');
});

test('host先覆盖而MO尚未执行时apply先消费记录，不覆盖新值或恢复旧值', () => {
  const f = fixture();
  f.apply();
  f.totalText.nodeValue = '123.00';
  f.apply();
  assert.equal(f.totalText.nodeValue, '123.00');
  f.stop();
  assert.equal(f.totalText.nodeValue, '123.00');
});

test('宿主aria主金额改变而文本尚未重绑时遮蔽旧模块文本，停用不回放原值', () => {
  const f = fixture();
  f.apply();
  f.total.setAttribute('aria-label', '总资产92.00元，昨日收益6.00元');
  f.flush();
  assert.equal(f.totalText.nodeValue, '****');
  assert.equal(f.total.getAttribute('aria-label'), '总资产92.00元，昨日收益6.00元');
  f.stop();
  assert.equal(f.totalText.nodeValue, '****');
});

test('隐藏祖先之后不因重新显示而回放旧原文', () => {
  const f = fixture();
  f.apply();
  f.overview.setAttribute('style', 'display:none');
  f.flush();
  assert.equal(f.totalText.nodeValue, '****');
  f.overview.setAttribute('style', 'display:block');
  f.flush();
  assert.equal(f.totalText.nodeValue, '****');
});

test('输出只包含固定状态、计数和nonce；表达式重求值复用同document私有会话', () => {
  const f = fixture();
  const next = vm.runInContext(source, f.context);
  const result = next({ op: 'apply', ...f.command });
  assert.equal(result.applied, 4);
  assert.deepEqual(Object.keys(result).sort(), [
    'applied',
    'hidden',
    'nonce',
    'status',
    'targets',
    'unknown',
    'waiting',
  ]);
  assert.equal(JSON.stringify(result).includes('80.00'), false);
});

test('prepare在有待处理隐藏mutation时也不写DOM，旧会话不能再次接管', () => {
  const f = fixture();
  f.apply();
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  const before = f.mutationCount();
  const prepared = f.prepare({ nativeSession: 'session_2' });
  assert.equal(prepared.status, 'prepared');
  assert.equal(f.mutationCount(), before);
  assert.equal(f.prepare().status, 'stale-session');
});

test('返回计数区分完整应用、部分应用、隐藏、等待宿主及未知结构', () => {
  const f = fixture();
  assert.equal(f.apply().status, 'applied');
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  f.flush();
  const partial = f.apply();
  assert.equal(partial.status, 'partial');
  assert.equal(partial.applied, 3);
  assert.equal(partial.hidden, 1);
  for (const item of [f.balance, f.yuebao, f.fund])
    item.scope.setAttribute('aria-label', item.name.textContent + '金额隐藏');
  f.flush();
  const hidden = f.apply();
  assert.equal(hidden.status, 'hidden');
  assert.equal(hidden.hidden, 4);
  const g = fixture();
  for (const element of [g.total, g.balance.scope, g.yuebao.scope, g.fund.scope])
    element.setAttribute(
      'aria-label',
      element.getAttribute('aria-label').replace(/[0-9]+\.[0-9]{2}/, '987.00'),
    );
  const waiting = g.apply();
  assert.equal(waiting.status, 'waiting-host');
  assert.equal(waiting.waiting, 4);
  const h = fixture();
  h.body.remove(h.overview);
  const unknown = h.apply();
  assert.equal(unknown.status, 'unknown-shape');
  assert.equal(unknown.unknown, 4);
});

test('同批aria先隐藏再恢复旧数字，无fresh文本时不得复用隐藏前缓存', () => {
  const f = fixture();
  f.apply();
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  f.total.setAttribute('aria-label', '总资产80.00元，昨日收益3.21元');
  f.flush();
  assert.equal(f.apply().applied, 3);
  assert.equal(f.totalText.nodeValue, '****');
  f.stop();
  assert.equal(f.totalText.nodeValue, '****');
});

test('同批aria隐藏后确有fresh文本及aria时允许重新应用，两种先后顺序均支持', () => {
  for (const ariaFirst of [false, true]) {
    const f = fixture();
    f.apply();
    f.total.setAttribute('aria-label', '总资产金额隐藏');
    const aria = () => f.total.setAttribute('aria-label', '总资产95.00元，昨日收益1.00元');
    if (ariaFirst) aria();
    f.totalText.nodeValue = '95.00';
    if (!ariaFirst) aria();
    f.flush();
    assert.equal(f.apply().applied, 4);
    f.stop();
    assert.equal(f.totalText.nodeValue, '95.00');
  }
});

test('根外ancestor可见性同批隐藏再恢复也使旧缓存失效', () => {
  for (const [name, hidden, shown] of [
    ['aria-hidden', 'true', 'false'],
    ['style', 'display:none', 'display:block'],
    ['style', 'visibility:hidden', 'visibility:visible'],
  ]) {
    const f = fixture();
    f.apply();
    f.body.setAttribute(name, hidden);
    f.body.setAttribute(name, shown);
    f.flush();
    assert.equal(f.totalText.nodeValue, '****', name);
    assert.equal(f.balance.mainText.nodeValue, '****', name);
    f.stop();
    assert.equal(f.totalText.nodeValue, '****', name);
  }
});

test('观察祖先不将无关兄弟节点或收益子树变化当成主金额替换', () => {
  const f = fixture();
  f.apply();
  f.body.append(f.element('DIV', {}, f.text('普通提示')));
  f.balance.income.append(f.element('SPAN', {}, f.text('收益附属标签')));
  f.flush();
  assert.equal(f.apply().applied, 4);
  assert.equal(f.totalText.nodeValue, '9,999.99');
  f.stop();
  assert.equal(f.totalText.nodeValue, '80.00');
});

test('隐藏后新aria先到、fresh文本后到且不重复aria，仍按同代同值验证恢复', () => {
  const f = fixture();
  f.apply();
  f.total.setAttribute('aria-label', '总资产金额隐藏');
  f.flush();
  f.total.setAttribute('aria-label', '总资产96.00元，昨日收益2.00元');
  f.flush();
  assert.equal(f.totalText.nodeValue, '****');
  f.totalText.nodeValue = '96.00';
  f.flush();
  assert.equal(f.apply().applied, 4);
  assert.equal(f.total.getAttribute('aria-label'), '总资产9,999.99元，昨日收益2.00元');
  f.stop();
  assert.equal(f.totalText.nodeValue, '96.00');
});

test('文本setter同步触发宿主隐藏aria时不再写入旧模块朗读', () => {
  const f = fixture();
  f.totalText.afterWrite = (value) => {
    if (value === f.command.amounts.total) f.total.setAttribute('aria-label', '总资产金额隐藏');
  };
  assert.equal(f.apply().applied, 3);
  assert.equal(f.totalText.nodeValue, '****');
  assert.equal(f.total.getAttribute('aria-label'), '总资产金额隐藏');
  f.stop();
  assert.equal(f.totalText.nodeValue, '****');
});

test('stop恢复后释放活DOM的WeakMap归属并清空host/applied原文', () => {
  const f = fixture();
  f.apply();
  assert.equal(f.memoryState().owners, 8);
  assert.equal(f.stop().restored, 4);
  assert.equal(f.totalText.nodeValue, '80.00');
  assert.deepEqual(f.memoryState(), { owners: 0, cleared: true, observations: 0 });
});

test('abandon仅凭匹配session/nonce清私有状态，不检查旧route也不产生DOM写入', () => {
  const f = fixture();
  f.apply();
  f.win.location.href = 'https://example.test/other?scene=next#route';
  const before = f.mutationCount();
  const reply = f.abandon();
  assert.equal(reply.status, 'abandoned');
  assert.equal(reply.applied, 0);
  assert.equal(reply.targets, 0);
  assert.equal(f.mutationCount(), before);
  assert.deepEqual(f.memoryState(), { owners: 0, cleared: true, observations: 0 });
  assert.equal(f.prepare({ route: 'https://example.test/other' }).status, 'stale-session');
  f.totalText.nodeValue = '97.00';
  f.total.setAttribute('aria-label', '总资产97.00元');
  const hostChanges = f.mutationCount();
  f.flush();
  assert.equal(f.totalText.nodeValue, '97.00');
  assert.equal(f.mutationCount(), hostChanges);
});

test('旧abandon命令不得清除新会话或中断新observer', () => {
  const f = fixture();
  f.apply();
  f.abandon();
  f.totalText.nodeValue = '98.00';
  f.total.setAttribute('aria-label', '总资产98.00元');
  f.balance.mainText.nodeValue = '11.00';
  f.balance.scope.setAttribute('aria-label', '余额11.00元');
  f.yuebao.mainText.nodeValue = '21.00';
  f.yuebao.scope.setAttribute('aria-label', '余额宝21.00元');
  f.fund.mainText.nodeValue = '31.00';
  f.fund.scope.setAttribute('aria-label', '基金31.00元');
  const next = f.prepare({ nativeSession: 'session_2' });
  assert.equal(f.apply({ nativeSession: 'session_2', nonce: next.nonce }).applied, 4);
  const before = f.mutationCount();
  assert.equal(f.abandon().status, 'stale-session');
  assert.equal(f.mutationCount(), before);
  f.totalText.nodeValue = '99.00';
  f.total.setAttribute('aria-label', '总资产99.00元');
  f.flush();
  assert.equal(f.totalText.nodeValue, '9,999.99');
});

test('余额宝profile只改经固定标题确认的总金额一处，代理叶aria-hidden不阻止可见显示', () => {
  const f = fixture('yuebao'),
    detail = f.details;
  const availableAria = detail.available.getAttribute('aria-label'),
    profitAria = detail.profit.getAttribute('aria-label');
  const children = [...detail.scope.childNodes],
    titleChildren = [...detail.title.childNodes];
  const result = f.apply();
  assert.equal(result.status, 'applied');
  assert.equal(result.targets, 1);
  assert.equal(result.applied, 1);
  assert.equal(result.unknown, 0);
  assert.equal(result.waiting, 0);
  assert.equal(detail.text.nodeValue, '222.22');
  assert.equal(detail.scope.getAttribute('aria-label'), '总金额222.22元');
  assert.equal(detail.span.getAttribute('aria-hidden'), 'true');
  assert.deepEqual(detail.scope.childNodes, children);
  assert.deepEqual(detail.title.childNodes, titleChildren);
  assert.equal(detail.availableText.nodeValue, '44.00');
  assert.equal(detail.profitText.nodeValue, '0.08');
  assert.equal(detail.available.getAttribute('aria-label'), availableAria);
  assert.equal(detail.profit.getAttribute('aria-label'), profitAria);
  assert.equal(f.totalText.nodeValue, '80.00');
  assert.equal(f.yuebao.mainText.nodeValue, '20.00');
  assert.equal(f.stop().restored, 1);
  assert.equal(detail.text.nodeValue, '55.00');
  assert.equal(detail.scope.getAttribute('aria-label'), '总金额55.00元');
  assert.deepEqual(f.memoryState(), { owners: 0, cleared: true, observations: 0 });
});

test('余额宝标题、父结构、唯一id及aria金额证明任一失败均保持原样', () => {
  const cases = [
    (f) => f.details.title.setAttribute('aria-label', '可用金额(元)'),
    (f) => f.details.title.children[1].append(f.text('可用金额')),
    (f) => {
      f.details.caption.firstChild.nodeValue = '可用金额(元)';
    },
    (f) => f.details.root.append(f.element('DIV', {})),
    (f) => f.details.scope.setAttribute('aria-label', '可用金额55.00元'),
    (f) => f.details.scope.setAttribute('aria-label', '总金额56.00元'),
    (f) => f.details.scope.setAttribute('aria-label', '总金额55.00元，收益0.08元'),
    (f) => f.body.append(f.element('DIV', { id: 'amountView', role: 'button' })),
  ];
  for (const mutate of cases) {
    const f = fixture('yuebao');
    mutate(f);
    assert.equal(f.apply().applied, 0);
    assert.equal(f.details.text.nodeValue, '55.00');
    assert.equal(f.details.availableText.nodeValue, '44.00');
  }
});

test('余额宝千分组金额是一个完整数字段，任意配置分组数均可独立显示', () => {
  for (const [host, desired] of [
    ['1,234.56', '0.00'],
    ['1,234,567.89', '999,999,999.99'],
    ['999.99', '1,000.00'],
  ]) {
    const f = fixture('yuebao');
    f.details.text.nodeValue = host;
    f.details.scope.setAttribute('aria-label', '总金额' + host + '元');
    const result = f.apply({ amounts: { ...f.command.amounts, yuebao: desired } });
    assert.equal(result.applied, 1);
    assert.equal(f.details.text.nodeValue, '' + desired);
    assert.equal(f.details.scope.getAttribute('aria-label'), '总金额' + desired + '元');
    assert.equal(f.stop().restored, 1);
    assert.equal(f.details.text.nodeValue, host);
  }
});

test('余额宝scope或ancestor隐藏仍优先，叶自身代理属性不能扩展到祖先', () => {
  for (const node of ['scope', 'root']) {
    const f = fixture('yuebao');
    f.apply();
    f.details[node].setAttribute('aria-hidden', 'true');
    f.flush();
    assert.equal(f.details.text.nodeValue, '****');
    f.details[node].setAttribute('aria-hidden', 'false');
    f.flush();
    assert.equal(f.apply().applied, 0);
    assert.equal(f.details.text.nodeValue, '****');
    f.stop();
    assert.equal(f.details.text.nodeValue, '****');
  }
});

test('余额宝隐藏掩码后只能用fresh数字与同代aria恢复，不回放隐藏前缓存', () => {
  const f = fixture('yuebao');
  f.apply();
  f.details.text.nodeValue = '****';
  f.details.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  assert.equal(f.apply().hidden, 1);
  f.details.scope.setAttribute('aria-label', '总金额65.00元');
  f.flush();
  assert.equal(f.details.text.nodeValue, '****');
  f.details.text.nodeValue = '65.00';
  f.flush();
  assert.equal(f.apply().applied, 1);
  assert.equal(f.details.text.nodeValue, '222.22');
  f.stop();
  assert.equal(f.details.text.nodeValue, '65.00');
});

test('余额宝初始已隐藏时不缓存仍留在DOM中的数字', () => {
  const f = fixture('yuebao');
  f.details.scope.setAttribute('aria-label', '总金额已隐藏');
  assert.equal(f.apply().applied, 0);
  f.details.scope.setAttribute('aria-label', '总金额55.00元');
  f.flush();
  assert.equal(f.apply().applied, 0);
  f.details.text.nodeValue = '55.00';
  f.flush();
  assert.equal(f.apply().applied, 1);
});

test('余额宝同值TextNode和代理SPAN重建保留同字段aria，不同值则等待fresh aria', () => {
  const f = fixture('yuebao');
  f.apply();
  let node = f.details.text,
    span = f.details.span;
  for (let iteration = 0; iteration < 3; iteration++) {
    const next = f.text('55.00');
    span.replace(node, next);
    node = next;
    f.flush();
    assert.equal(f.apply().applied, 1);
  }
  const replacement = f.element('SPAN', { 'aria-hidden': 'true' }, f.text('55.00'));
  f.details.scope.replace(span, replacement);
  span = replacement;
  node = span.firstChild;
  f.flush();
  assert.equal(f.apply().applied, 1);
  assert.equal(node.nodeValue, '222.22');
  const changed = f.text('66.00');
  span.replace(node, changed);
  f.flush();
  assert.equal(f.apply().applied, 0);
  assert.equal(changed.nodeValue, '66.00');
  f.details.scope.setAttribute('aria-label', '总金额66.00元');
  f.flush();
  assert.equal(f.apply().applied, 1);
  f.stop();
  assert.equal(changed.nodeValue, '66.00');
});

test('profile在prepare固定，旧缺省命令兼容总资产而不同profile不得切换当前会话', () => {
  const f = fixture();
  assert.equal(f.apply({ profile: 'total-assets' }).applied, 4);
  const before = f.mutationCount();
  assert.equal(f.apply({ profile: 'yuebao' }).status, 'stale-session');
  assert.equal(f.prepare({ profile: 'yuebao' }).status, 'stale-session');
  assert.equal(f.apply({ profile: 'fund' }).status, 'invalid-config');
  assert.equal(f.mutationCount(), before);
  const g = fixture('yuebao');
  g.apply();
  const current = g.mutationCount();
  assert.equal(g.apply({ profile: 'total-assets' }).status, 'stale-session');
  assert.equal(g.apply({ profile: undefined }).status, 'stale-session');
  assert.equal(g.mutationCount(), current);
});

test('纯数字text与aria同值回声不得成为host源，后续配置和stop都不回放该回声', () => {
  for (const separate of [false, true]) {
    const f = fixture();
    f.apply();
    f.totalText.nodeValue = '9,999.99';
    if (separate) f.flush();
    f.total.setAttribute('aria-label', '总资产9,999.99元，昨日收益3.21元');
    f.flush();
    const next = {
      revision: '9007199254740994',
      amounts: { ...f.command.amounts, total: '888.88' },
    };
    assert.equal(f.apply(next).applied, 3);
    assert.equal(f.totalText.nodeValue, '9,999.99', '已归host的回声不擅自覆写为新配置');
    assert.equal(f.stop({ revision: next.revision }).restored, 3);
    assert.equal(f.totalText.nodeValue, '9,999.99');
  }
});

test('同scope回声改用不带千分逗号格式也仍来源不明，可靠不同host值可恢复', () => {
  const f = fixture();
  f.apply();
  f.totalText.nodeValue = '9999.99';
  f.total.setAttribute('aria-label', '总资产9999.99元');
  f.flush();
  const next = { revision: '9007199254740994', amounts: { ...f.command.amounts, total: '777.77' } };
  assert.equal(f.apply(next).applied, 3);
  assert.equal(f.totalText.nodeValue, '9999.99');
  f.totalText.nodeValue = '91.00';
  f.total.setAttribute('aria-label', '总资产91.00元，昨日收益7.00元');
  f.flush();
  assert.equal(f.apply(next).applied, 4);
  assert.equal(f.totalText.nodeValue, '777.77');
  f.stop({ revision: next.revision });
  assert.equal(f.totalText.nodeValue, '91.00');
  assert.equal(f.total.getAttribute('aria-label'), '总资产91.00元，昨日收益7.00元');
});

test('同scope数字TextNode或SPAN重建不能洗掉最后模块金额的来源不明凭据', () => {
  for (const replaceSpan of [false, true]) {
    const f = fixture();
    f.apply();
    const node = f.text('9,999.99');
    if (replaceSpan) f.totalLeaf.parentElement.replace(f.totalLeaf, f.element('SPAN', {}, node));
    else f.totalLeaf.replace(f.totalText, node);
    f.total.setAttribute('aria-label', '总资产9,999.99元，昨日收益3.21元');
    f.flush();
    const next = {
      revision: '9007199254740994',
      amounts: { ...f.command.amounts, total: '888.88' },
    };
    assert.equal(f.apply(next).applied, 3);
    assert.equal(node.nodeValue, '9,999.99');
    f.stop({ revision: next.revision });
    assert.equal(node.nodeValue, '9,999.99');
  }
});

test('回声已使绑定清源后，停用再启用的重扫仍不能自动采纳同scope回声', () => {
  const f = fixture();
  f.apply();
  f.totalText.nodeValue = '9,999.99';
  f.total.setAttribute('aria-label', '总资产9,999.99元');
  f.flush();
  f.stop();
  const next = { revision: '9007199254740994', amounts: { ...f.command.amounts, total: '666.66' } };
  assert.equal(f.apply(next).applied, 3);
  assert.equal(f.totalText.nodeValue, '9,999.99');
  f.stop({ revision: next.revision });
  assert.equal(f.totalText.nodeValue, '9,999.99');
});

test('仍可信host原值恰等于模块输出时可继续作为原源，不能因纯数字一概丢弃', () => {
  const f = fixture();
  f.apply();
  const same = { revision: '9007199254740994', amounts: { ...f.command.amounts, total: '80.00' } };
  f.apply(same);
  f.totalText.nodeValue = '80.00';
  f.total.setAttribute('aria-label', '总资产80.00元，昨日收益3.21元');
  f.flush();
  const changed = {
    revision: '9007199254740995',
    amounts: { ...f.command.amounts, total: '99.00' },
  };
  assert.equal(f.apply(changed).applied, 4);
  assert.equal(f.totalText.nodeValue, '99.00');
  f.stop({ revision: changed.revision });
  assert.equal(f.totalText.nodeValue, '80.00');
});

test('可信原值等于模块输出时同scope叶重建仍可复用同代原值证明', () => {
  const f = fixture();
  f.apply();
  const same = { revision: '9007199254740994', amounts: { ...f.command.amounts, total: '80.00' } };
  f.apply(same);
  const node = f.text('80.00');
  f.totalLeaf.replace(f.totalText, node);
  f.flush();
  const changed = {
    revision: '9007199254740995',
    amounts: { ...f.command.amounts, total: '99.00' },
  };
  assert.equal(f.apply(changed).applied, 4);
  assert.equal(node.nodeValue, '99.00');
  f.stop({ revision: changed.revision });
  assert.equal(node.nodeValue, '80.00');
});

test('新的scope对象按严格结构读取，旧scope回声凭据不串到新scope', () => {
  const f = fixture();
  f.apply();
  const node = f.text('9,999.99');
  const replacement = f.element(
    'DIV',
    { role: 'button', 'aria-label': '总资产9,999.99元' },
    f.element('SPAN', {}, f.element('SPAN', {}, node)),
  );
  f.total.parentElement.replace(f.total, replacement);
  f.flush();
  const next = { revision: '9007199254740994', amounts: { ...f.command.amounts, total: '888.88' } };
  assert.equal(f.apply(next).applied, 4);
  assert.equal(node.nodeValue, '888.88');
  f.stop({ revision: next.revision });
  assert.equal(node.nodeValue, '9,999.99');
});

test('余额宝纯数字同值回声同样不能被下一配置及停用当作原值', () => {
  const f = fixture('yuebao');
  f.apply();
  f.details.text.nodeValue = '222.22';
  f.details.scope.setAttribute('aria-label', '总金额222.22元');
  f.flush();
  const next = {
    revision: '9007199254740994',
    amounts: { ...f.command.amounts, yuebao: '333.33' },
  };
  assert.equal(f.apply(next).applied, 0);
  assert.equal(f.details.text.nodeValue, '222.22');
  assert.equal(f.stop({ revision: next.revision }).restored, 0);
  assert.equal(f.details.text.nodeValue, '222.22');
});
