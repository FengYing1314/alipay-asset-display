'use strict';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import { webcrypto } from 'node:crypto';
const source = fs.readFileSync(
  new URL('../../main/resources/io/github/fengying/alipaydisplay/fund-summary.js', import.meta.url),
  'utf8',
);

// 仅合成数据；记录原DOM和所有自有字段，校验退出后不保留短期原文。
function fixture() {
  const observers = new Set(),
    maps = [],
    captured = new Set();
  let mutations = 0,
    recordReads = 0;
  class TrackedWeakMap extends WeakMap {
    constructor() {
      super();
      maps.push(this);
    }
    set(key, value) {
      if (value instanceof Map) captured.add(value);
      return super.set(key, value);
    }
  }
  function notify(record) {
    mutations++;
    for (const observer of observers)
      if (
        observer.targets.some(
          ([node, options]) =>
            (node === record.target || (options.subtree && node.contains(record.target))) &&
            options[record.type] &&
            (record.type !== 'attributes' ||
              !options.attributeFilter ||
              options.attributeFilter.includes(record.attributeName)),
        )
      ) {
        observer.records.push(record);
      }
  }
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
      for (let e = node; e; e = e.parentElement) if (e === this) return true;
      return false;
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
  function parseStyle(raw) {
    const values = new Map();
    for (const piece of (raw || '').split(';')) {
      const match = /^\s*([\w-]+)\s*:\s*(.*?)\s*(!important)?\s*$/.exec(piece);
      if (match) values.set(match[1], { value: match[2], priority: match[3] ? 'important' : '' });
    }
    return values;
  }
  class Element extends Node {
    constructor(tag = 'DIV', attrs = {}) {
      super(1);
      this.tagName = tag.toUpperCase();
      this.attrs = { ...attrs };
      this.childNodes = [];
      this.style = {
        getPropertyValue: (name) => parseStyle(this.getAttribute('style')).get(name)?.value || '',
        getPropertyPriority: (name) =>
          parseStyle(this.getAttribute('style')).get(name)?.priority || '',
        setProperty: (name, value, priority = '') => {
          const styles = parseStyle(this.getAttribute('style'));
          styles.set(name, { value, priority });
          this.setAttribute(
            'style',
            Array.from(
              styles,
              ([key, item]) => key + ': ' + item.value + (item.priority ? ' !important' : '') + ';',
            ).join(' '),
          );
        },
        removeProperty: (name) => {
          const styles = parseStyle(this.getAttribute('style'));
          styles.delete(name);
          this.setAttribute(
            'style',
            Array.from(
              styles,
              ([key, item]) => key + ': ' + item.value + (item.priority ? ' !important' : '') + ';',
            ).join(' '),
          );
        },
      };
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
      if (this.afterAttribute) this.afterAttribute(name, String(value));
    }
    appendChild(node) {
      this.childNodes.push(node);
      node.parentElement = this;
      adopt(node, this.ownerDocument);
      notify({ type: 'childList', target: this, addedNodes: [node], removedNodes: [] });
      return node;
    }
    removeChild(node) {
      assert(this.childNodes.includes(node));
      this.childNodes.splice(this.childNodes.indexOf(node), 1);
      node.parentElement = null;
      notify({ type: 'childList', target: this, removedNodes: [node], addedNodes: [] });
      return node;
    }
    replace(old, next) {
      const index = this.childNodes.indexOf(old);
      assert(index >= 0);
      this.childNodes[index] = next;
      old.parentElement = null;
      next.parentElement = this;
      adopt(next, this.ownerDocument);
      notify({ type: 'childList', target: this, removedNodes: [old], addedNodes: [next] });
    }
  }
  function adopt(node, document) {
    node.ownerDocument = document;
    if (node.childNodes) for (const child of node.childNodes) adopt(child, document);
  }
  class Observer {
    constructor(callback) {
      this.callback = callback;
      this.targets = [];
      this.records = [];
      observers.add(this);
    }
    observe(target, options) {
      this.targets = this.targets.filter(([node]) => node !== target);
      this.targets.push([target, options]);
    }
    takeRecords() {
      recordReads++;
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
    createElement: (tag) => {
      const node = new Element(tag);
      adopt(node, doc);
      return node;
    },
    createTextNode: (value) => {
      const node = new Text(value);
      adopt(node, doc);
      return node;
    },
    querySelectorAll(selector) {
      const result = [],
        pending = [body];
      while (pending.length) {
        const node = pending.shift();
        if ((node.getAttribute('class') || '').split(/\s+/).includes(selector.slice(1)))
          result.push(node);
        pending.push(...node.children);
      }
      return result;
    },
  };
  adopt(body, doc);
  const text = (value) => doc.createTextNode(value);
  function element(tag, attrs = {}, ...children) {
    const node = new Element(tag, attrs);
    adopt(node, doc);
    for (const child of children) node.appendChild(child);
    return node;
  }
  const values = ['1', ',', '234', '.', '56'];
  const leaves = values.map((value) => text(value));
  const number = element(
    'SPAN',
    {
      class: 'wu-number wu-alisans-number',
      role: 'text',
      'data-mark': 'totalAsset',
      'aria-label': '总金额1,234.56元',
      style: 'color: red; display: inline-block;',
    },
    ...leaves.map((node) => element('SPAN', { 'aria-hidden': 'true' }, node)),
  );
  const scope = element('DIV', { class: 'total-asset', 'aria-label': '总金额1234.56元' }, number);
  const eye = element('DIV', { role: 'button', 'aria-label': '隐藏金额' });
  const title = element(
    'DIV',
    {},
    element('SPAN', { 'aria-hidden': 'true' }, text('总金额(元)')),
    eye,
  );
  const profitText = text('0.25'),
    profit = element('DIV', {}, profitText, element('SPAN'), element('SPAN'));
  const block = element('DIV', {}, title, scope, profit);
  const root = element('DIV', { class: 'assets-all' }, block, element('DIV'), element('DIV'));
  const holdingText = text('987.65'),
    holding = element('DIV', { class: 'fund-holding-item' }, holdingText);
  body.appendChild(root);
  body.appendChild(holding);
  const win = {
    location: { href: 'https://example.test/assets?fixture=1#top' },
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
      return {
        display: node.style.getPropertyValue('display') || 'inline',
        visibility: node.style.getPropertyValue('visibility') || 'visible',
      };
    },
  });
  const dispatch = vm.runInContext(source, context);
  const base = { nativeSession: 'fund_1', profile: 'fund', route: 'https://example.test/assets' };
  const prepare = (overrides = {}) => dispatch({ op: 'prepare', ...base, ...overrides });
  const prepared = prepare();
  const command = {
    ...base,
    nonce: prepared.nonce,
    revision: '9007199254740993',
    amounts: { balance: '11.00', yuebao: '22.00', fund: '999,999,999.99', total: '44.00' },
  };
  const apply = (overrides = {}) => dispatch({ op: 'apply', ...command, ...overrides });
  const stop = (overrides = {}) => dispatch({ op: 'stop', ...command, ...overrides });
  const abandon = (overrides = {}) => dispatch({ op: 'abandon', ...command, ...overrides });
  const status = (overrides = {}) => dispatch({ op: 'status', ...command, ...overrides });
  function flush() {
    for (let round = 0; round < 32; round++) {
      const pending = Array.from(observers).filter((item) => item.records.length);
      if (!pending.length) return;
      for (const item of pending) item.callback(item.takeRecords());
    }
    throw new Error('observer did not settle');
  }
  function hostAmount(integer = '235', fraction = '67') {
    leaves[2].nodeValue = integer;
    leaves[4].nodeValue = fraction;
    scope.setAttribute('aria-label', '总金额1' + integer + '.' + fraction + '元');
    number.setAttribute('aria-label', '总金额1,' + integer + '.' + fraction + '元');
  }
  function memory() {
    return {
      ownershipMaps: [number, scope].filter((node) => maps[0].has(node)).length,
      ownedNodes: scope.children.filter((node) => maps[1].has(node)).length,
      observers: Array.from(observers).reduce((count, item) => count + item.targets.length, 0),
      fieldsCleared: Array.from(captured).every((map) => map.size === 0),
    };
  }
  return {
    dispatch,
    context,
    win,
    body,
    doc,
    element,
    text,
    root,
    block,
    title,
    eye,
    scope,
    number,
    leaves,
    profitText,
    holdingText,
    command,
    prepare,
    apply,
    stop,
    abandon,
    status,
    flush,
    hostAmount,
    memory,
    count: () => mutations,
    recordReads: () => recordReads,
    pendingRecords: () =>
      Array.from(observers).reduce((count, item) => count + item.records.length, 0),
    presentation: () => scope.children.find((node) => node !== number) || null,
  };
}

test('prepare零DOM写；任意配置分组在自有自然流SPAN展示，原5叶/收益/持仓不改', () => {
  const f = fixture(),
    before = f.count(),
    original = f.number.childNodes.slice(),
    values = f.leaves.map((node) => node.nodeValue);
  assert.equal(f.prepare().nonce, f.command.nonce);
  assert.equal(f.count(), before);
  const reply = f.apply();
  assert.equal(reply.status, 'applied');
  assert.equal(reply.applied, 1);
  assert.equal(reply.unknown, 0);
  assert.equal(f.presentation().textContent, '999,999,999.99');
  assert.equal(f.presentation().getAttribute('aria-hidden'), 'true');
  assert.equal(f.number.style.getPropertyValue('display'), 'none');
  assert.equal(f.number.style.getPropertyPriority('display'), 'important');
  assert.deepEqual(f.number.childNodes, original);
  assert.deepEqual(
    f.leaves.map((node) => node.nodeValue),
    values,
  );
  assert.equal(f.scope.getAttribute('aria-label'), '总金额999,999,999.99元');
  assert.equal(f.number.getAttribute('aria-label'), '总金额999,999,999.99元');
  assert.equal(f.profitText.nodeValue, '0.25');
  assert.equal(f.holdingText.nodeValue, '987.65');
  const after = f.count();
  f.flush();
  f.apply();
  assert.equal(f.count(), after);
});

test('双aria与拼接原文同值、标题及唯一结构均严格验证', () => {
  const cases = [
    (f) => f.scope.setAttribute('aria-label', '总金额1235.56元'),
    (f) => f.number.setAttribute('aria-label', '总金额1234.56元，收益1.00元'),
    (f) => {
      f.title.children[0].firstChild.nodeValue = '可用金额(元)';
    },
    (f) => {
      f.leaves[1].nodeValue = '.';
    },
    (f) => f.body.appendChild(f.element('DIV', { class: 'total-asset' })),
  ];
  for (const mutate of cases) {
    const f = fixture();
    mutate(f);
    const before = f.number.style.getPropertyValue('display');
    assert.equal(f.apply().applied, 0);
    assert.equal(f.presentation(), null);
    assert.equal(f.number.style.getPropertyValue('display'), before);
  }
});

test('合法stop逐字段恢复display value/priority及双aria，释放全部归属', () => {
  const f = fixture();
  f.number.style.setProperty('display', 'inline-flex', 'important');
  f.apply();
  const reply = f.stop();
  assert.equal(reply.restored, 1);
  assert.equal(reply.masked, 0);
  assert.equal(f.presentation(), null);
  assert.equal(f.number.style.getPropertyValue('display'), 'inline-flex');
  assert.equal(f.number.style.getPropertyPriority('display'), 'important');
  assert.equal(f.number.style.getPropertyValue('color'), 'red');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1234.56元');
  assert.equal(f.number.getAttribute('aria-label'), '总金额1,234.56元');
  assert.deepEqual(f.memory(), {
    ownershipMaps: 0,
    ownedNodes: 0,
    observers: 0,
    fieldsCleared: true,
  });
});

test('host异步更新原叶与双aria后只恢复最新原文', () => {
  const f = fixture();
  f.apply();
  f.hostAmount();
  f.flush();
  assert.equal(f.apply().applied, 1);
  assert.equal(f.presentation().textContent, '999,999,999.99');
  f.stop();
  assert.equal(f.number.textContent, '1,235.67');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1235.67元');
  assert.equal(f.number.getAttribute('aria-label'), '总金额1,235.67元');
});

test('stop先消费尚未回调的host更新，原5叶不改且不回放上一笔aria', () => {
  const f = fixture();
  f.apply();
  f.hostAmount('236', '78');
  const result = f.stop();
  assert.equal(result.restored, 1);
  assert.equal(f.number.textContent, '1,236.78');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1236.78元');
  assert.equal(f.number.getAttribute('aria-label'), '总金额1,236.78元');
});

test('同批隐藏→恢复双aria但无fresh数字不得复用旧源，stop不露出残留数字', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.number.setAttribute('aria-label', '总金额已隐藏');
  f.number.setAttribute('aria-label', '总金额1,234.56元');
  f.flush();
  assert.equal(f.presentation(), null);
  assert.equal(f.apply().applied, 0);
  const stopped = f.stop();
  assert.equal(stopped.restored, 0);
  assert.equal(stopped.masked, 1);
  assert.equal(f.number.style.getPropertyValue('display'), 'none');
  assert.equal(f.number.textContent, '1,234.56');
});

test('隐藏后fresh数字与双aria在同代齐备才恢复，aria先到也允许', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  assert.equal(f.presentation(), null);
  f.scope.setAttribute('aria-label', '总金额1235.67元');
  f.number.setAttribute('aria-label', '总金额1,235.67元');
  f.flush();
  assert.equal(f.apply().applied, 0);
  f.leaves[2].nodeValue = '235';
  f.leaves[4].nodeValue = '67';
  f.flush();
  assert.equal(f.apply().applied, 1);
  f.stop();
  assert.equal(f.number.textContent, '1,235.67');
});

test('根外祖先同批隐藏恢复也移除自有数字并清源', () => {
  const f = fixture();
  f.apply();
  f.body.setAttribute('aria-hidden', 'true');
  f.body.setAttribute('aria-hidden', 'false');
  f.flush();
  assert.equal(f.presentation(), null);
  assert.equal(f.apply().applied, 0);
  assert.equal(f.stop().masked, 1);
});

test('原组件重建时不把缓存交给新叶，等待完整fresh数字与双aria', () => {
  const f = fixture();
  f.apply();
  const replacement = f.text('234');
  f.number.children[2].replace(f.leaves[2], replacement);
  f.flush();
  assert.equal(f.presentation(), null);
  assert.equal(f.apply().applied, 0);
  f.stop();
  assert.equal(f.number.style.getPropertyValue('display'), 'none');
});

test('同一number仅原TextNode重建后，保留display归属但须fresh数字和双aria才恢复', () => {
  const f = fixture();
  f.apply();
  let live = f.leaves.slice();
  for (let round = 0; round < 2; round++) {
    const replacement = f.text(round === 0 ? '234' : '235');
    f.number.children[2].replace(live[2], replacement);
    live[2] = replacement;
    f.flush();
    assert.equal(f.apply().applied, 0);
    assert.equal(f.presentation(), null);
    const fraction = round === 0 ? '67' : '78',
      integer = round === 0 ? '235' : '236';
    const values = ['1', ',', integer, '.', fraction];
    live.forEach((node, index) => {
      node.nodeValue = values[index];
    });
    f.scope.setAttribute('aria-label', '总金额1' + integer + '.' + fraction + '元');
    f.number.setAttribute('aria-label', '总金额1,' + integer + '.' + fraction + '元');
    f.flush();
    assert.equal(f.apply().applied, 1);
    assert.equal(f.presentation().textContent, '999,999,999.99');
  }
  assert.equal(f.stop().restored, 1);
  assert.equal(f.number.style.getPropertyValue('display'), 'inline-block');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1236.78元');
});

function rebuiltNumber(f, values = ['1', ',', '235', '.', '67']) {
  return f.element(
    'SPAN',
    {
      class: 'wu-number wu-alisans-number',
      role: 'text',
      'data-mark': 'totalAsset',
      'aria-label': '总金额' + values.join('') + '元',
      style: 'display: inline-flex;',
    },
    ...values.map((value) => f.element('SPAN', { 'aria-hidden': 'true' }, f.text(value))),
  );
}

function hostEyeHide(f, { releaseInner = true, withMask = true } = {}) {
  const spans = f.number.children.slice();
  f.eye.setAttribute('aria-label', '显示金额');
  if (withMask) {
    f.number.setAttribute('role', '');
    for (const span of spans) f.number.removeChild(span);
    for (let index = 0; index < 4; index++)
      f.number.appendChild(f.element('SPAN', { 'aria-hidden': 'true' }, f.text('*')));
  }
  if (releaseInner) f.number.setAttribute('aria-label', '总金额1,234.56元');
  f.flush();
  return spans;
}

function hostEyeShow(f, spans, { outer = '总金额1234.56元', showAction = true } = {}) {
  for (const span of f.number.children.slice()) f.number.removeChild(span);
  for (const span of spans) f.number.appendChild(span);
  f.number.setAttribute('role', 'text');
  if (outer !== null) f.scope.setAttribute('aria-label', outer);
  if (showAction) f.eye.setAttribute('aria-label', '隐藏金额');
  f.flush();
}

test('真实四单星模板后复用原五叶和inner aria，仅outer setter及眼睛show事件即可恢复', () => {
  const f = fixture();
  f.apply();
  const spans = hostEyeHide(f),
    leafValues = f.leaves.map((node) => node.nodeValue);
  const hidden = f.status();
  assert.equal(hidden.debug.eyeActionShow, true);
  assert.equal(hidden.debug.scopeMaskSeen, true);
  assert.equal(hidden.debug.shapeKnown, false);
  assert.equal(f.presentation(), null);
  hostEyeShow(f, spans);
  assert.equal(f.status().applied, 1);
  assert.equal(f.status().debug.eyeActionHide, true);
  assert.equal(f.status().debug.scopeMaskSeen, true);
  assert.equal(f.status().debug.showReceipt, false);
  assert.deepEqual(
    f.leaves.map((node) => node.nodeValue),
    leafValues,
  );
  assert.deepEqual(f.number.children, spans);
  assert.equal(f.presentation()?.textContent, '999,999,999.99');
  assert.equal(f.stop().restored, 1);
  assert.equal(f.number.getAttribute('aria-label'), '总金额1,234.56元');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1234.56元');
  assert.equal(f.profitText.nodeValue, '0.25');
  assert.equal(f.holdingText.nodeValue, '987.65');
});

test('show事件可先于模板移除；status不消费待用receipt且结构事件不是下一次hidden', () => {
  const f = fixture();
  f.apply();
  const spans = hostEyeHide(f);
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.eye.setAttribute('aria-label', '隐藏金额');
  f.flush();
  f.number.setAttribute('class', f.number.getAttribute('class'));
  f.flush();
  const before = f.count(),
    reads = f.recordReads();
  assert.equal(f.status().debug.showReceipt, true);
  assert.equal(f.status().debug.showReceipt, true);
  assert.equal(f.count(), before);
  assert.equal(f.recordReads(), reads);
  hostEyeShow(f, spans, { showAction: false });
  assert.equal(f.status().applied, 1);
});

test('初始按钮快照、无明确show事件或无真实scope掩码都不能恢复旧数字', () => {
  for (const mode of ['snapshot', 'no-show-event', 'no-mask']) {
    const f = fixture();
    f.apply();
    if (mode === 'snapshot') {
      f.scope.setAttribute('aria-label', '总金额已隐藏');
      f.flush();
      f.scope.setAttribute('aria-label', '总金额1234.56元');
      f.number.setAttribute('aria-label', '总金额1,234.56元');
      f.flush();
    } else {
      const spans = hostEyeHide(f, { withMask: mode !== 'no-mask' });
      hostEyeShow(f, spans, { showAction: mode !== 'no-show-event' });
    }
    assert.equal(f.status().applied, 0);
    assert.equal(f.presentation(), null);
  }
});

test('未知aria/role或非空按钮不能形成眼睛授权', () => {
  for (const mode of ['aria', 'role', 'children']) {
    const f = fixture();
    f.apply();
    const spans = hostEyeHide(f);
    if (mode === 'aria') f.eye.setAttribute('aria-label', '其他状态');
    if (mode === 'role') f.eye.setAttribute('role', 'link');
    if (mode === 'children') f.eye.appendChild(f.element('SPAN'));
    f.flush();
    hostEyeShow(f, spans);
    assert.equal(f.status().applied, 0);
    assert.equal(f.presentation(), null);
  }
});

test('明确show也不能使用仍own或不匹配的aria，不能接受lastOutput回声', () => {
  for (const mode of ['owned-inner', 'wrong-outer', 'echo']) {
    const f = fixture(),
      amounts = { ...f.command.amounts, fund: '9,876.54' };
    f.apply({ amounts });
    const spans = hostEyeHide(f, { releaseInner: mode !== 'owned-inner' });
    if (mode === 'echo') {
      ['9', ',', '876', '.', '54'].forEach((value, index) => {
        f.leaves[index].nodeValue = value;
      });
      f.number.setAttribute('aria-label', '总金额9,876.54元');
    }
    hostEyeShow(f, spans, {
      outer:
        mode === 'wrong-outer'
          ? '总金额1235.56元'
          : mode === 'echo'
            ? '总金额9876.54元'
            : '总金额1234.56元',
    });
    assert.equal(f.status().applied, 0);
    assert.equal(f.presentation(), null);
    assert.equal(f.stop().restored, 0);
  }
});

test('同批show之后再出现真实mask会撤销receipt，即使最终又是严格数字结构', () => {
  const f = fixture();
  f.apply();
  const spans = hostEyeHide(f);
  f.eye.setAttribute('aria-label', '隐藏金额');
  const transient = f.element('SPAN', {}, f.text('*'));
  f.scope.appendChild(transient);
  f.scope.removeChild(transient);
  hostEyeShow(f, spans, { showAction: false });
  assert.equal(f.status().applied, 0);
  assert.equal(f.status().debug.showReceipt, false);
});

test('同批完整hide/show恢复无需中间flush，mask先于眼睛hide动作也有效', () => {
  const f = fixture();
  f.apply();
  const spans = f.number.children.slice();
  f.number.setAttribute('role', '');
  for (const span of spans) f.number.removeChild(span);
  for (let index = 0; index < 4; index++)
    f.number.appendChild(f.element('SPAN', { 'aria-hidden': 'true' }, f.text('*')));
  f.eye.setAttribute('aria-label', '显示金额');
  f.number.setAttribute('aria-label', '总金额1,234.56元');
  hostEyeShow(f, spans);
  assert.equal(f.status().applied, 1);
  assert.equal(f.status().debug.scopeMaskSeen, true);
});

test('show后临时片段在被移除前变成mask，不能因最终已脱树而漏掉更晚隐藏', () => {
  const f = fixture();
  f.apply();
  const spans = hostEyeHide(f);
  const transient = f.element('SPAN', {}, f.text(''));
  f.scope.appendChild(transient);
  f.eye.setAttribute('aria-label', '隐藏金额');
  transient.firstChild.nodeValue = '*';
  f.scope.removeChild(transient);
  hostEyeShow(f, spans, { showAction: false });
  assert.equal(f.status().applied, 0);
  assert.equal(f.status().debug.showReceipt, false);
});

test('show后原数字TextNode先变mask再被新数字叶替换，已脱树记录仍撤销receipt', () => {
  const f = fixture();
  f.apply();
  const spans = hostEyeHide(f);
  for (const span of f.number.children.slice()) f.number.removeChild(span);
  for (const span of spans) f.number.appendChild(span);
  f.number.setAttribute('role', 'text');
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.eye.setAttribute('aria-label', '隐藏金额');
  f.leaves[2].nodeValue = '**';
  f.number.children[2].replace(f.leaves[2], f.text('234'));
  f.flush();
  assert.equal(f.status().applied, 0);
  assert.equal(f.status().debug.showReceipt, false);
});

test('receipt绑定root/scope/control对象，替换后不能转交', () => {
  for (const mode of ['root', 'scope', 'control']) {
    const f = fixture();
    f.apply();
    const spans = hostEyeHide(f);
    f.eye.setAttribute('aria-label', '隐藏金额');
    f.flush();
    assert.equal(f.status().debug.showReceipt, true);
    if (mode === 'root') {
      f.root.removeChild(f.block);
      const next = f.element(
        'DIV',
        { class: 'assets-all' },
        f.block,
        f.element('DIV'),
        f.element('DIV'),
      );
      f.body.replace(f.root, next);
    } else if (mode === 'scope') {
      f.scope.removeChild(f.number);
      f.block.replace(
        f.scope,
        f.element('DIV', { class: 'total-asset', 'aria-label': '总金额1234.56元' }, f.number),
      );
    } else f.title.replace(f.eye, f.element('DIV', { role: 'button', 'aria-label': '隐藏金额' }));
    hostEyeShow(f, spans, { showAction: false });
    assert.equal(f.status().debug.showReceipt, false);
    assert.equal(f.status().applied, 0);
  }
});

test('同一个show receipt成功消费后不能为第二次旧节点搬家授权', () => {
  const f = fixture();
  f.apply();
  const spans = hostEyeHide(f);
  hostEyeShow(f, spans);
  assert.equal(f.status().applied, 1);
  const leaf = f.number.children[2].firstChild;
  f.number.children[2].removeChild(leaf);
  f.number.children[2].appendChild(leaf);
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.number.setAttribute('aria-label', '总金额1,234.56元');
  f.flush();
  assert.equal(f.status().applied, 0);
  assert.equal(f.status().debug.showReceipt, false);
  assert.equal(f.presentation(), null);
});

test('自有展示或收益兄弟中的掩码不能证明scope真实隐藏模板', () => {
  for (const mode of ['own', 'profit']) {
    const f = fixture();
    f.apply();
    if (mode === 'own') f.presentation().firstChild.nodeValue = '*';
    else f.profitText.nodeValue = '****';
    f.eye.setAttribute('aria-label', '显示金额');
    f.number.setAttribute('aria-label', '总金额1,234.56元');
    f.flush();
    f.scope.setAttribute('aria-label', '总金额1234.56元');
    f.eye.setAttribute('aria-label', '隐藏金额');
    f.flush();
    assert.equal(f.status().debug.scopeMaskSeen, false);
    assert.equal(f.status().applied, 0);
  }
});

test('宿主同值回写模块自有aria掩码不算独立scope mask证明', () => {
  const f = fixture();
  f.apply();
  f.eye.setAttribute('aria-label', '显示金额');
  f.flush();
  const moduleMask = f.scope.getAttribute('aria-label');
  f.scope.setAttribute('aria-label', moduleMask);
  f.number.setAttribute('aria-label', '总金额1,234.56元');
  f.flush();
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.eye.setAttribute('aria-label', '隐藏金额');
  f.flush();
  assert.equal(f.status().debug.scopeMaskSeen, false);
  assert.equal(f.status().applied, 0);
});

test('隐藏后同批重建五个live文本及双aria即可恢复，不等待第二次宿主写入', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  const values = ['1', ',', '235', '.', '67'];
  f.number.children.forEach((span, index) => span.replace(span.firstChild, f.text(values[index])));
  f.scope.setAttribute('aria-label', '总金额1235.67元');
  f.number.setAttribute('aria-label', '总金额1,235.67元');
  f.flush();
  assert.equal(f.presentation()?.textContent, '999,999,999.99');
  assert.equal(f.apply().applied, 1);
  assert.equal(f.stop().restored, 1);
  assert.equal(f.number.style.getPropertyValue('display'), 'inline-block');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1235.67元');
});

test('隐藏中间事件和其后的完整新叶双aria同一回调批次可安全恢复', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.number.setAttribute('aria-label', '总金额已隐藏');
  const values = ['1', ',', '235', '.', '67'];
  f.number.children.forEach((span, index) => span.replace(span.firstChild, f.text(values[index])));
  f.scope.setAttribute('aria-label', '总金额1235.67元');
  f.number.setAttribute('aria-label', '总金额1,235.67元');
  f.flush();
  assert.equal(f.presentation()?.textContent, '999,999,999.99');
  assert.equal(f.stop().restored, 1);
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1235.67元');
});

test('同批双aria先于新叶与live字符事件也能恢复，不把旧叶事件当新叶证明', () => {
  for (const mode of ['new-before-text', 'old-only']) {
    const f = fixture();
    f.apply();
    f.scope.setAttribute('aria-label', '总金额已隐藏');
    f.flush();
    f.scope.setAttribute('aria-label', '总金额1235.67元');
    f.number.setAttribute('aria-label', '总金额1,235.67元');
    if (mode === 'old-only') f.leaves[2].nodeValue = '235';
    const replacement = f.text(mode === 'old-only' ? '234' : '200');
    f.number.children[2].replace(f.leaves[2], replacement);
    if (mode === 'new-before-text') {
      replacement.nodeValue = '235';
      f.leaves[4].nodeValue = '67';
    }
    f.flush();
    assert.equal(f.apply().applied, mode === 'new-before-text' ? 1 : 0);
  }
});

test('隐藏后同批新增完整number证明自带inner aria，旧scope仍须fresh aria', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  const number = rebuiltNumber(f);
  f.scope.replace(f.number, number);
  f.scope.setAttribute('aria-label', '总金额1235.67元');
  f.flush();
  assert.equal(f.apply().applied, 1);
  assert.equal(f.scope.children.find((node) => node !== number)?.textContent, '999,999,999.99');
  assert.equal(f.stop().restored, 1);
  assert.equal(number.style.getPropertyValue('display'), 'inline-flex');
  assert.equal(number.getAttribute('aria-label'), '总金额1,235.67元');
});

test('隐藏后同批新增scope及完整number可证明其两个新aria字段', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  const number = rebuiltNumber(f),
    scope = f.element('DIV', { class: 'total-asset', 'aria-label': '总金额1235.67元' }, number);
  f.block.replace(f.scope, scope);
  f.flush();
  assert.equal(f.apply().applied, 1);
  assert.equal(scope.children.find((node) => node !== number)?.textContent, '999,999,999.99');
  assert.equal(f.stop().restored, 1);
  assert.equal(scope.getAttribute('aria-label'), '总金额1235.67元');
});

test('新增number不能替旧scope证明aria，隐藏前事件也不能穿过隐私屏障', () => {
  for (const mode of ['missing', 'before-hidden']) {
    const f = fixture();
    f.apply();
    if (mode === 'before-hidden') f.scope.setAttribute('aria-label', '总金额1235.67元');
    f.scope.setAttribute('aria-label', '总金额已隐藏');
    const number = rebuiltNumber(f);
    f.scope.replace(f.number, number);
    f.flush();
    assert.equal(f.apply().applied, 0);
    assert.equal(f.scope.children.length, 1);
  }
});

test('把旧五叶搬到新增number不等于当前隐私代次的文本写入', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  const number = rebuiltNumber(f, ['1', ',', '234', '.', '56']);
  f.number.children.slice().forEach((span, index) => {
    f.number.removeChild(span);
    number.replace(number.children[index], span);
  });
  f.scope.replace(f.number, number);
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.flush();
  assert.equal(f.apply().applied, 0);
  assert.equal(f.scope.children.length, 1);
});

test('同批fresh重建后再隐藏恢复aria，前序数字证据不能越过中间隐藏', () => {
  const f = fixture();
  f.apply();
  const number = rebuiltNumber(f);
  f.scope.replace(f.number, number);
  f.scope.setAttribute('aria-label', '总金额1235.67元');
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.scope.setAttribute('aria-label', '总金额1235.67元');
  f.flush();
  assert.equal(f.apply().applied, 0);
  assert.equal(f.scope.children.length, 1);
});

test('新number与双aria仅复制模块纯数字输出不能作为新宿主金额', () => {
  const f = fixture();
  f.apply({ amounts: { ...f.command.amounts, fund: '9,876.54' } });
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  const number = rebuiltNumber(f, ['9', ',', '876', '.', '54']);
  f.scope.replace(f.number, number);
  f.scope.setAttribute('aria-label', '总金额9,876.54元');
  f.flush();
  assert.equal(f.apply({ amounts: { ...f.command.amounts, fund: '9,876.54' } }).applied, 0);
  assert.equal(f.scope.children.length, 1);
  assert.equal(f.stop().restored, 0);
});

test('同对象5叶与双aria回声也不能在隐私失效后成为宿主源', () => {
  const f = fixture(),
    amounts = { ...f.command.amounts, fund: '9,876.54' };
  f.apply({ amounts });
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  ['9', ',', '876', '.', '54'].forEach((value, index) => {
    f.leaves[index].nodeValue = value;
  });
  f.scope.setAttribute('aria-label', '总金额9876.54元');
  f.number.setAttribute('aria-label', '总金额9,876.54元');
  f.flush();
  assert.equal(f.presentation(), null);
  assert.equal(f.apply({ amounts }).applied, 0);
  assert.equal(f.stop().restored, 0);
  assert.equal(f.number.style.getPropertyValue('display'), 'none');
});

test('已可靠验证的初始宿主值本来等于配置时，持续绑定与安全stop仍可使用', () => {
  const f = fixture(),
    amounts = { ...f.command.amounts, fund: '1,234.56' };
  assert.equal(f.apply({ amounts }).applied, 1);
  f.flush();
  assert.equal(f.apply({ amounts }).applied, 1);
  assert.equal(f.stop().restored, 1);
  assert.equal(f.scope.getAttribute('aria-label'), '总金额1234.56元');
});

test('host同值写回aria仍是external mutation，不能误认self记录', () => {
  const f = fixture();
  f.apply();
  f.scope.setAttribute('aria-label', f.scope.getAttribute('aria-label'));
  f.flush();
  assert.equal(f.presentation(), null);
  assert.equal(f.apply().applied, 0);
  f.stop();
  assert.notEqual(f.scope.getAttribute('aria-label'), '总金额1234.56元');
});

test('host同值重写display导致字段归属丢失，stop不擅自覆盖host样式', () => {
  const f = fixture();
  f.apply();
  f.number.style.setProperty('display', 'none', 'important');
  f.flush();
  assert.equal(f.presentation(), null);
  f.stop();
  assert.equal(f.number.style.getPropertyValue('display'), 'none');
});

test('仅收益/持仓变化不改原文也不新增目标', () => {
  const f = fixture();
  f.apply();
  f.profitText.nodeValue = '0.30';
  f.holdingText.nodeValue = '999.00';
  f.flush();
  assert.equal(f.apply().applied, 1);
  assert.equal(f.profitText.nodeValue, '0.30');
  assert.equal(f.holdingText.nodeValue, '999.00');
});

test('旧nonce/revision/profile/session不写DOM，revision按字符串精确比较', () => {
  const f = fixture();
  f.apply();
  const before = f.count();
  assert.equal(f.apply({ nonce: 'old' }).status, 'stale-session');
  assert.equal(f.apply({ revision: '9007199254740992' }).status, 'stale-revision');
  assert.equal(f.apply({ profile: 'total-assets' }).status, 'rejected');
  assert.equal(f.apply({ nativeSession: 'other' }).status, 'stale-session');
  assert.equal(
    f.apply({ amounts: { ...f.command.amounts, fund: '1.00' } }).status,
    'revision-conflict',
  );
  assert.equal(f.count(), before);
});

test('合法配置更换支持任意整数分组而不重排宿主5叶', () => {
  const f = fixture();
  f.apply();
  const nodes = f.number.children.slice();
  assert.equal(
    f.apply({ revision: '9007199254740994', amounts: { ...f.command.amounts, fund: '0.01' } })
      .applied,
    1,
  );
  assert.equal(f.presentation().textContent, '0.01');
  assert.deepEqual(f.number.children, nodes);
  f.stop();
  assert.equal(f.number.textContent, '1,234.56');
});

test('abandon跨route只清私有状态，零DOM写，残留显示等待host重绘', () => {
  const f = fixture();
  f.apply();
  f.win.location.href = 'https://example.test/other?next=1#route';
  const before = f.count();
  assert.equal(f.abandon().status, 'abandoned');
  assert.equal(f.count(), before);
  assert.deepEqual(f.memory(), {
    ownershipMaps: 0,
    ownedNodes: 0,
    observers: 0,
    fieldsCleared: true,
  });
  f.hostAmount();
  const changed = f.count();
  f.flush();
  assert.equal(f.count(), changed);
  assert.equal(f.stop().status, 'rejected');
});

test('query/hash失效自动退休nonce并清内存，旧abandon不能影响新会话', () => {
  const f = fixture();
  f.apply();
  f.win.location.href += '-next';
  const before = f.count();
  assert.equal(f.apply().status, 'stale-session');
  assert.equal(f.count(), before);
  assert.deepEqual(f.memory(), {
    ownershipMaps: 0,
    ownedNodes: 0,
    observers: 0,
    fieldsCleared: true,
  });
  const next = f.prepare({ nativeSession: 'fund_2' });
  assert.equal(next.status, 'prepared');
  assert.equal(f.abandon().status, 'stale-session');
  assert.equal(f.prepare().status, 'stale-session');
});

test('self写display同步触发host隐藏时不插入数字或覆盖新隐藏aria', () => {
  const f = fixture();
  f.number.afterAttribute = (name, value) => {
    if (name === 'style' && value.includes('none'))
      f.scope.setAttribute('aria-label', '总金额已隐藏');
  };
  assert.equal(f.apply().applied, 0);
  assert.equal(f.presentation(), null);
  assert.equal(f.scope.getAttribute('aria-label'), '总金额已隐藏');
});

test('stop恢复display同步触发host隐藏时重新遮源且不宣称恢复成功', () => {
  const f = fixture();
  f.apply();
  f.number.afterAttribute = (name, value) => {
    if (name === 'style' && value.includes('inline-block'))
      f.scope.setAttribute('aria-label', '总金额已隐藏');
  };
  const reply = f.stop();
  assert.equal(reply.restored, 0);
  assert.equal(reply.masked, 1);
  assert.equal(f.number.style.getPropertyValue('display'), 'none');
  assert.equal(f.presentation(), null);
  assert.equal(f.scope.getAttribute('aria-label'), '总金额已隐藏');
});

test('同页安全stop后可用新revision重新启用，不复用隐藏后的遮源状态', () => {
  const f = fixture();
  f.apply();
  assert.equal(f.stop().restored, 1);
  assert.equal(f.apply().status, 'stopped');
  assert.equal(f.apply({ revision: '9007199254740994' }).applied, 1);
  assert.equal(f.presentation().textContent, '999,999,999.99');
});

test('新prepare只清私有状态并退休旧session，旧prepare/abandon都不能逆向接管', () => {
  const f = fixture();
  f.apply();
  const before = f.count();
  const next = f.prepare({ nativeSession: 'fund_2' });
  assert.equal(next.status, 'prepared');
  assert.equal(f.count(), before);
  assert.equal(f.prepare().status, 'stale-session');
  assert.equal(f.abandon().status, 'stale-session');
  assert.deepEqual(f.memory(), {
    ownershipMaps: 0,
    ownedNodes: 0,
    observers: 0,
    fieldsCleared: true,
  });
});

test('host改自有SPAN同值文本也必须撤掉自有数字并等待新源', () => {
  const f = fixture();
  f.apply();
  const presentation = f.presentation();
  presentation.firstChild.nodeValue = presentation.textContent;
  f.flush();
  assert.equal(f.presentation(), null);
  assert.equal(f.apply().applied, 0);
});

test('宿主创建同类名SPAN不能冒充模块对象token，不能被插件删除', () => {
  const f = fixture();
  f.apply();
  const unrelated = f.element(
    'SPAN',
    { class: 'wu-number wu-alisans-number', 'aria-hidden': 'true' },
    f.text('宿主新增内容'),
  );
  f.scope.appendChild(unrelated);
  f.flush();
  assert.equal(f.apply().status, 'unknown-shape');
  assert.equal(unrelated.parentElement, f.scope);
  f.stop();
  assert.equal(unrelated.parentElement, f.scope);
});

test('初始隐藏不缓存原数字，仅恢复aria不得重用隐藏前数字', () => {
  const f = fixture();
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  assert.equal(f.apply().applied, 0);
  f.scope.setAttribute('aria-label', '总金额1234.56元');
  f.number.setAttribute('aria-label', '总金额1,234.56元');
  f.flush();
  assert.equal(f.apply().applied, 0);
  assert.equal(f.presentation(), null);
  f.leaves[2].nodeValue = '234';
  f.flush();
  assert.equal(f.apply().applied, 1);
});

test('从实际host掩码恢复display不会露出残留数字，aria原缓存仍不恢复', () => {
  const f = fixture();
  f.apply();
  for (const node of f.leaves) node.nodeValue = '*';
  f.scope.setAttribute('aria-label', '总金额已隐藏');
  f.number.setAttribute('aria-label', '总金额已隐藏');
  f.flush();
  assert.equal(f.presentation(), null);
  const reply = f.stop();
  assert.equal(reply.restored, 1);
  assert.equal(f.number.style.getPropertyValue('display'), 'inline-block');
  assert.equal(f.number.textContent, '*****');
  assert.equal(f.scope.getAttribute('aria-label'), '总金额已隐藏');
});

test('所有返回仅固定状态/计数/nonce，原文不出现在结果中', () => {
  const f = fixture(),
    result = f.apply();
  assert.deepEqual(Object.keys(result).sort(), [
    'applied',
    'hidden',
    'nonce',
    'status',
    'targets',
    'unknown',
    'waiting',
  ]);
  assert.equal(JSON.stringify(result).includes('1234.56'), false);
  assert.equal(JSON.stringify(result).includes('1,234.56'), false);
});

const debugFields = [
  'bindingPresent',
  'shapeKnown',
  'sameShape',
  'sourceVisible',
  'textFresh',
  'outerFresh',
  'innerFresh',
  'displayFresh',
  'displayOwned',
  'outerOwned',
  'innerOwned',
  'textProof',
  'outerProof',
  'innerProof',
  'numberIntroduced',
  'scopeIntroduced',
  'lastOutputAmbiguous',
  'privacyHidden',
  'numericShapeKnown',
  'textMatchesCurrent',
  'outerMatchesCurrent',
  'innerMatchesCurrent',
  'outerEpochCurrent',
  'innerEpochCurrent',
  'outputEchoAllowed',
  'sourceMasked',
  'outerMasked',
  'innerMasked',
  'eyeAnchorKnown',
  'eyeActionShow',
  'eyeActionHide',
  'showReceipt',
  'scopeMaskSeen',
];

test('status只输出既有计数与固定bool白名单，不包含原文或地址', () => {
  const f = fixture();
  f.apply();
  const before = f.count(),
    reads = f.recordReads(),
    memory = f.memory();
  const result = f.status();
  assert.equal(result.status, 'applied');
  assert.equal(result.applied, 1);
  assert.deepEqual(Object.keys(result).sort(), [
    'applied',
    'debug',
    'hidden',
    'nonce',
    'status',
    'targets',
    'unknown',
    'waiting',
  ]);
  assert.deepEqual(Object.keys(result.debug).sort(), debugFields.slice().sort());
  assert(Object.values(result.debug).every((value) => typeof value === 'boolean'));
  assert.equal(result.debug.bindingPresent, true);
  assert.equal(result.debug.sameShape, true);
  assert.equal(result.debug.displayOwned, true);
  assert.equal(result.debug.numericShapeKnown, true);
  for (const denied of [
    '1234.56',
    '1,234.56',
    '999,999,999.99',
    '总金额',
    'example.test',
    'https:',
  ]) {
    assert.equal(JSON.stringify(result).includes(denied), false);
  }
  f.status();
  assert.equal(f.count(), before);
  assert.equal(f.recordReads(), reads);
  assert.deepEqual(f.memory(), memory);
});

test('status不drain待处理隐藏或重建事件，不触发reconcile或DOM写', () => {
  for (const mode of ['hidden', 'rebuilt']) {
    const f = fixture();
    f.apply();
    if (mode === 'hidden') f.scope.setAttribute('aria-label', '总金额已隐藏');
    else f.scope.replace(f.number, rebuiltNumber(f));
    const before = f.count(),
      reads = f.recordReads(),
      pending = f.pendingRecords(),
      presentation = f.scope.children[1];
    const result = f.status();
    assert.equal(result.debug.bindingPresent, true);
    assert.equal(result.debug.shapeKnown, true);
    assert.equal(result.debug.sameShape, mode === 'hidden');
    assert.equal(result.debug.outerMasked, mode === 'hidden');
    assert.equal(f.count(), before);
    assert.equal(f.recordReads(), reads);
    assert.equal(f.pendingRecords(), pending);
    assert.equal(f.scope.children[1], presentation);
    f.flush();
    assert.equal(f.scope.children.length, 1);
  }
});

test('status严格匹配nonce/session/profile/route和当前revision，不推进状态', () => {
  const f = fixture();
  assert.equal(f.status().status, 'stale-revision');
  f.apply();
  const before = f.count(),
    reads = f.recordReads(),
    memory = f.memory();
  for (const [overrides, expected] of [
    [{ nonce: 'old' }, 'stale-session'],
    [{ nativeSession: 'old' }, 'stale-session'],
    [{ profile: 'other' }, 'rejected'],
    [{ route: 'https://example.test/other' }, 'rejected'],
    [{ revision: '9007199254740992' }, 'stale-revision'],
    [{ revision: '9007199254740994' }, 'stale-revision'],
    [{ revision: 'invalid' }, 'stale-revision'],
  ]) {
    const result = f.status(overrides);
    assert.equal(result.status, expected);
    assert.equal(result.debug, undefined);
    assert.equal(result.applied, 0);
    assert.equal(result.targets, 0);
  }
  assert.equal(f.status().applied, 1);
  assert.equal(f.count(), before);
  assert.equal(f.recordReads(), reads);
  assert.deepEqual(f.memory(), memory);
  assert.equal(f.apply().applied, 1);
});

test('status遇href失配或读取异常也不abandon或写DOM', () => {
  const f = fixture();
  f.apply();
  const before = f.count(),
    memory = f.memory(),
    href = f.win.location.href;
  f.win.location.href += '-next';
  assert.equal(f.status().status, 'stale-session');
  assert.equal(f.count(), before);
  assert.deepEqual(f.memory(), memory);
  f.win.location.href = href;
  assert.equal(f.status().status, 'applied');
  const getAttribute = f.number.getAttribute;
  f.number.getAttribute = () => {
    throw new Error('synthetic read failure');
  };
  assert.equal(f.status().status, 'unavailable');
  assert.equal(f.count(), before);
  assert.deepEqual(f.memory(), memory);
  f.number.getAttribute = getAttribute;
  assert.equal(f.status().status, 'applied');
});
