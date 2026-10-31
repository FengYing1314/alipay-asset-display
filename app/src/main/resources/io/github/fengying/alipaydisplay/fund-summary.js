(() => {
  'use strict';
  const key = Symbol.for('io.github.fengying.alipaydisplay.fund-summary.v1');
  const existing = window[key];
  if (existing && existing.document === document && typeof existing.dispatch === 'function')
    return existing.dispatch;
  const doc = document;
  const owners = new WeakMap(),
    ownNodes = new WeakMap(),
    ownRecords = new WeakMap();
  const retired = new Set();
  const numberPattern = '(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?';
  const hiddenText = /[*＊●•·]|隐藏|不可见|不显示/;
  let state = null,
    binding = null,
    observer = null,
    observedRoot = null;
  let busy = false,
    consuming = false,
    sequence = 0;
  let proof = null,
    proofMasks = new WeakMap(),
    knownAriaNodes = new WeakSet(),
    knownTextNodes = new WeakSet();
  let eyeState = null,
    eyeEpoch = 0;
  resetProof();

  // 外部命令与会话释放。

  function dispatch(command) {
    if (command && command.op === 'status') return status(command);
    try {
      if (command && command.op === 'abandon') {
        if (
          !state ||
          command.nativeSession !== state.nativeSession ||
          command.nonce !== state.nonce
        )
          return output('stale-session');
        abandon();
        return output('abandoned');
      }
      if (state && !state.invalid) current();
      if (
        !command ||
        typeof command !== 'object' ||
        command.profile !== 'fund' ||
        !validRoute(command.route) ||
        typeof command.nativeSession !== 'string' ||
        !/^[A-Za-z0-9_-]{1,96}$/.test(command.nativeSession)
      )
        return output('rejected');
      if (command.op === 'prepare') {
        if (retired.has(command.nativeSession)) return output('stale-session');
        if (state && state.nativeSession === command.nativeSession && !state.invalid)
          return output('prepared');
        if (state) abandon();
        const random = new Uint32Array(4);
        window.crypto.getRandomValues(random);
        state = {
          nativeSession: command.nativeSession,
          route: command.route,
          href: window.location.href,
          nonce: Array.from(random, (value) => value.toString(16).padStart(8, '0')).join(''),
          revision: null,
          amounts: null,
          stopped: false,
          invalid: false,
          targets: 0,
          everBound: false,
          lastOutput: null,
        };
        Object.defineProperty(window, key, {
          value: { document: doc, dispatch },
          configurable: true,
        });
        return output('prepared');
      }
      if (
        !state ||
        command.nativeSession !== state.nativeSession ||
        command.nonce !== state.nonce ||
        command.route !== state.route ||
        !current()
      )
        return output('stale-session');
      if (
        !isValidRevision(command.revision) ||
        (state.revision !== null && compareRevisions(command.revision, state.revision) < 0)
      )
        return output('stale-revision');
      if (command.op === 'stop') {
        state.revision = command.revision;
        return stop();
      }
      const fields = ['balance', 'yuebao', 'fund', 'total'];
      if (
        command.op !== 'apply' ||
        !command.amounts ||
        fields.some(
          (name) =>
            typeof command.amounts[name] !== 'string' ||
            !/^(?:0|[1-9][0-9]{0,2}(?:,[0-9]{3})*)\.[0-9]{2}$/.test(command.amounts[name]) ||
            normalizeAmount(command.amounts[name]).length > 12,
        )
      )
        return output('invalid-config');
      if (state.stopped && state.revision === command.revision) return output('stopped');
      if (
        state.revision === command.revision &&
        state.amounts &&
        fields.some((name) => state.amounts[name] !== command.amounts[name])
      )
        return output('revision-conflict');
      state.revision = command.revision;
      state.amounts = { ...command.amounts };
      state.stopped = false;
      reconcile();
      return output(null);
    } catch (_) {
      abandon();
      return output('unavailable');
    }
  }
  // 诊断不消费MutationRecord，也不调用会退休会话的current()。
  function status(command) {
    const rejected = (reason) => ({
      status: reason,
      nonce: state ? state.nonce : '',
      targets: 0,
      applied: 0,
      hidden: 0,
      waiting: 0,
      unknown: 1,
    });
    try {
      if (
        !command ||
        typeof command !== 'object' ||
        command.profile !== 'fund' ||
        !validRoute(command.route) ||
        typeof command.nativeSession !== 'string' ||
        !/^[A-Za-z0-9_-]{1,96}$/.test(command.nativeSession)
      )
        return rejected('rejected');
      if (
        !state ||
        state.invalid ||
        doc !== document ||
        state.href !== window.location.href ||
        command.nativeSession !== state.nativeSession ||
        command.nonce !== state.nonce ||
        command.route !== state.route
      )
        return rejected('stale-session');
      if (
        !isValidRevision(command.revision) ||
        state.revision === null ||
        command.revision !== state.revision
      )
        return rejected('stale-revision');
      const candidate = shape(),
        amount = candidate ? joined(candidate) : null;
      const eye = eyeAnchor();
      const outer = binding && binding.arias[0],
        inner = binding && binding.arias[1];
      const ariaProof = (node) => {
        if (!node || owners.get(node)?.has('aria')) return false;
        const field = binding && binding.arias.find((item) => item.node === node);
        return (
          proof.arias.get(node) === node.getAttribute('aria-label') ||
          ((field ? field.newField : !knownAriaNodes.has(node)) && introduced(node))
        );
      };
      const textProof =
        !!candidate &&
        candidate.leaves.some((node) => {
          const index = binding ? binding.leaves.indexOf(node) : -1;
          const newLeaf = index >= 0 ? binding.newLeaves[index] : !knownTextNodes.has(node);
          return (
            proof.text.get(node) === node.nodeValue ||
            (newLeaf && /^[0-9]+$/.test(node.nodeValue) && introduced(node))
          );
        });
      const debug = {
        bindingPresent: !!binding,
        shapeKnown: !!candidate,
        sameShape: !!same(candidate),
        sourceVisible: !!sourceVisible(),
        textFresh: !!binding && !!binding.text.fresh,
        outerFresh: !!outer && !!outer.fresh,
        innerFresh: !!inner && !!inner.fresh,
        displayFresh: !!binding && !!binding.display.fresh,
        displayOwned: !!binding && !!owns(binding.display),
        outerOwned: !!outer && !!owns(outer),
        innerOwned: !!inner && !!owns(inner),
        textProof,
        outerProof: !!candidate && !!ariaProof(candidate.scope),
        innerProof: !!candidate && !!ariaProof(candidate.number),
        numberIntroduced: !!candidate && introduced(candidate.number),
        scopeIntroduced: !!candidate && introduced(candidate.scope),
        lastOutputAmbiguous:
          amount !== null && amount === state.lastOutput && (!binding || !binding.echoAllowed),
        privacyHidden: !!binding && !!binding.hidden,
        numericShapeKnown: amount !== null,
        textMatchesCurrent: !!binding && amount !== null && binding.text.host === amount,
        outerMatchesCurrent:
          !!candidate &&
          amount !== null &&
          !!parts(candidate.scope.getAttribute('aria-label'), amount),
        innerMatchesCurrent:
          !!candidate &&
          amount !== null &&
          !!parts(candidate.number.getAttribute('aria-label'), amount),
        outerEpochCurrent: !!outer && outer.epoch === binding.epoch,
        innerEpochCurrent: !!inner && inner.epoch === binding.epoch,
        outputEchoAllowed: !!binding && !!binding.echoAllowed,
        sourceMasked: !!candidate && hiddenText.test(candidate.number.textContent),
        outerMasked:
          !!candidate && hiddenText.test(candidate.scope.getAttribute('aria-label') || ''),
        innerMasked:
          !!candidate && hiddenText.test(candidate.number.getAttribute('aria-label') || ''),
        eyeAnchorKnown: !!eye,
        eyeActionShow: !!eye && eye.action === '显示金额',
        eyeActionHide: !!eye && eye.action === '隐藏金额',
        showReceipt: !!validShowReceipt(eye),
        scopeMaskSeen: !!sameEye(eye) && !!eyeState.maskSeen,
      };
      return output(state.stopped ? 'stopped' : null, { debug });
    } catch (_) {
      return rejected('unavailable');
    }
  }
  function stop() {
    drain();
    let restored = 0,
      masked = 0;
    if (binding) {
      const trusted = fresh();
      removePresentation();
      for (const field of binding.arias)
        if (owns(field)) {
          if (trusted && fresh()) write(field, field.host);
          else write(field, '总金额，金额隐藏');
        }
      const noDigits = sourceHasNoDigits();
      if (owns(binding.display) && binding.display.fresh && ((trusted && fresh()) || noDigits)) {
        if (write(binding.display, binding.display.host)) restored = 1;
        else if (owns(binding.display)) masked = 1;
      } else if (owns(binding.display)) masked = 1;
    }
    forget();
    disconnect();
    resetProof();
    eyeState = null;
    eyeEpoch++;
    state.targets = 0;
    state.amounts = null;
    state.stopped = true;
    if (restored === 1 && masked === 0) state.everBound = false;
    return output('stopped', { restored, masked });
  }
  function abandon() {
    disconnect();
    forget();
    eyeState = null;
    eyeEpoch++;
    resetProof();
    proofMasks = new WeakMap();
    knownAriaNodes = new WeakSet();
    knownTextNodes = new WeakSet();
    if (state) {
      retired.add(state.nativeSession);
      state.invalid = true;
      state.href = null;
      state.amounts = null;
      state.lastOutput = null;
      state.targets = 0;
    }
  }
  function disconnect() {
    if (observer) observer.disconnect();
    observer = null;
    observedRoot = null;
  }
  function output(status, extra) {
    const targets = state ? state.targets : 0;
    const applied =
      targets &&
      binding &&
      fresh() &&
      binding.presentation &&
      ownNodes.get(binding.presentation) === binding.token &&
      binding.presentation.parentElement === binding.scope &&
      owns(binding.display) &&
      binding.arias.every(owns)
        ? 1
        : 0;
    const hidden = !applied && targets && binding && binding.hidden ? 1 : 0;
    if (!status)
      status = applied
        ? 'applied'
        : !targets
          ? 'unknown-shape'
          : hidden
            ? 'hidden'
            : 'waiting-host';
    return Object.assign(
      {
        status,
        nonce: state ? state.nonce : '',
        targets,
        applied,
        hidden,
        waiting: targets - applied - hidden,
        unknown: 1 - targets,
      },
      extra || {},
    );
  }

  // 显示协调与宿主变更处理。

  function reconcile() {
    if (!state || state.stopped || !state.amounts || busy || consuming || !current()) return;
    drain();
    syncEye(eyeAnchor());
    const candidate = shape();
    state.targets = candidate ? 1 : 0;
    if (!candidate) {
      if (binding) {
        clearSources(false);
        hideOwned();
      }
      return;
    }
    attach(candidate.root);
    let retainedDisplay = null;
    if (binding && !same(candidate)) {
      // 数字叶重建不等于display字段换代；仅同一root/scope/number保留仍自有的样式。
      if (
        candidate.root === binding.root &&
        candidate.scope === binding.scope &&
        candidate.number === binding.number &&
        owns(binding.display) &&
        binding.display.applied.value === 'none' &&
        binding.display.applied.priority === 'important'
      ) {
        retainedDisplay = {
          ...binding.display,
          host: binding.display.host && { ...binding.display.host },
          applied: { ...binding.display.applied },
        };
      }
      clearSources(false);
      hideOwned();
      if (retainedDisplay && !owns(binding.display)) retainedDisplay = null;
      forget();
    }
    if (!binding) {
      create(candidate, !state.everBound);
      state.everBound = true;
      if (retainedDisplay) {
        binding.display = retainedDisplay;
        claim(binding.display, retainedDisplay.applied);
      }
      acceptProof();
    }
    acceptShowReceipt(candidate);
    if (!fresh()) {
      hideOwned();
      return;
    }
    present();
  }
  function present() {
    if (!fresh()) return;
    const value = state.amounts.fund;
    if (!write(binding.display, { value: 'none', priority: 'important' }) || !fresh()) {
      hideOwned();
      return;
    }
    if (binding.presentation && binding.presentation.textContent !== value) removePresentation();
    if (!binding.presentation) {
      const node = doc.createElement('span');
      node.setAttribute('class', 'wu-number wu-alisans-number');
      node.setAttribute('aria-hidden', 'true');
      node.appendChild(doc.createTextNode(value));
      ownNodes.set(node, binding.token);
      binding.presentation = node;
      const scope = binding.scope;
      state.lastOutput = normalizeAmount(value);
      binding.echoAllowed = binding.text.host === state.lastOutput;
      perform(
        () => scope.appendChild(node),
        (record) =>
          record.type === 'childList' &&
          record.target === scope &&
          record.addedNodes.length === 1 &&
          record.addedNodes[0] === node &&
          record.removedNodes.length === 0,
      );
    }
    if (!binding || !fresh()) {
      hideOwned();
      return;
    }
    for (const field of binding.arias) {
      const pair = parts(field.host, binding.text.host);
      if (!pair || !fresh()) {
        hideOwned();
        return;
      }
      write(field, pair.before + value + pair.after);
      if (!binding || !fresh()) {
        hideOwned();
        return;
      }
    }
  }
  function consume(records) {
    if (!records.length || !binding || !current()) return;
    const previous = consuming;
    consuming = true;
    try {
      const events = records.map((record, index) => ({
        record,
        value: afterValue(records, index),
      }));
      const candidate = shape();
      const eye = eyeAnchor();
      syncEye(eye);
      const fragments = eye
        ? events
            .filter(
              ({ record }) =>
                !ownRecords.has(record) &&
                record.type === 'childList' &&
                (eye.scope.contains(record.target) ||
                  (binding.scope === eye.scope && binding.number.contains(record.target))),
            )
            .flatMap(({ record }) =>
              Array.from(record.addedNodes).concat(Array.from(record.removedNodes)),
            )
            .filter((node) => !inOwnNode(node))
        : [];
      const amountTarget = (node) =>
        !!eye &&
        (eye.scope.contains(node) || fragments.some((fragment) => fragment.contains(node)));
      let invalid = false;
      for (const { record, value } of events) {
        if (ownRecords.has(record)) {
          ownRecords.delete(record);
          continue;
        }
        if (!binding) break;
        observeEye(record, value, eye, amountTarget);
        const aria = binding.arias.find((field) => matches(record, field));
        if (aria) {
          const ownOld = aria.owned && aria.applied === record.oldValue;
          if (
            (hiddenText.test(record.oldValue || '') && !ownOld && !aria.hiddenKnown) ||
            hiddenText.test(value || '')
          ) {
            clearSources(true);
            invalid = true;
          }
          release(aria);
          aria.hiddenKnown = hiddenText.test(value || '');
          aria.host = !aria.hiddenKnown && typeof value === 'string' ? value : null;
          aria.fresh = aria.host !== null;
          aria.epoch = binding.epoch;
          if (!parts(aria.host, state.lastOutput)) binding.echoAllowed = false;
        } else if (matches(record, binding.display)) {
          release(binding.display);
          binding.display.host = fieldValue(binding.display);
          binding.display.fresh = !styleHidden(record.target.getAttribute('style'));
          clearSources(styleHidden(record.oldValue) || styleHidden(value));
          invalid = true;
        } else if (record.type === 'characterData' && binding.leaves.includes(record.target)) {
          const known = binding.maskKnown.get(record.target);
          if ((hiddenText.test(record.oldValue || '') && !known) || hiddenText.test(value || '')) {
            clearSources(true);
            invalid = true;
          }
          binding.maskKnown.set(record.target, hiddenText.test(value || ''));
          const amount = joined(binding);
          if (amount !== state.lastOutput) binding.echoAllowed = false;
          binding.text.host = amount;
          binding.text.fresh = amount !== null;
          binding.text.epoch = binding.epoch;
          for (const field of binding.arias)
            if (!field.fresh || field.epoch !== binding.epoch || !parts(field.host, amount)) {
              field.host = null;
              field.fresh = false;
            }
        } else if (
          binding.presentation &&
          (record.target === binding.presentation ||
            binding.presentation.contains(record.target) ||
            (record.type === 'childList' &&
              Array.from(record.removedNodes).includes(binding.presentation)))
        ) {
          clearSources(false);
          invalid = true;
        } else if (
          record.type === 'attributes' &&
          record.attributeName !== 'aria-label' &&
          (record.target.contains(binding.number) || binding.number.contains(record.target))
        ) {
          const proxy =
            Array.from(binding.number.children).includes(record.target) &&
            record.attributeName === 'aria-hidden';
          if (
            !proxy &&
            (record.attributeName === 'class' ||
              hiddenAttribute(record.attributeName, record.oldValue) ||
              hiddenAttribute(record.attributeName, value) ||
              !sourceVisible())
          ) {
            clearSources(true);
            invalid = true;
          }
        } else if (
          record.type === 'childList' &&
          (binding.scope.contains(record.target) ||
            !isConnectedToDocument(binding.scope) ||
            Array.from(record.removedNodes)
              .concat(Array.from(record.addedNodes))
              .some((node) => node.contains(binding.scope)))
        ) {
          clearSources(false);
          invalid = true;
        }
        observeProof(record, value, candidate);
      }
      if (invalid || !fresh()) hideOwned();
      if (binding && fresh()) binding.hidden = false;
    } finally {
      consuming = previous;
    }
  }
  function drain() {
    if (observer) consume(observer.takeRecords());
  }
  function attach(root) {
    if (observer && observedRoot === root) return;
    disconnect();
    observedRoot = root;
    const nonce = state.nonce;
    const active = new MutationObserver((records) => {
      try {
        if (observer !== active || !state || state.nonce !== nonce || state.stopped || !current())
          return;
        consume(records);
        reconcile();
      } catch (_) {
        abandon();
      }
    });
    observer = active;
    observer.observe(root, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeOldValue: true,
      characterData: true,
      characterDataOldValue: true,
      attributeFilter: [
        'aria-label',
        'aria-hidden',
        'hidden',
        'class',
        'style',
        'data-mark',
        'role',
      ],
    });
    for (
      let parent = root.parentElement, depth = 0;
      parent && depth < 32;
      parent = parent.parentElement, depth++
    ) {
      observer.observe(parent, {
        childList: true,
        attributes: true,
        attributeOldValue: true,
        attributeFilter: ['aria-hidden', 'hidden', 'class', 'style'],
      });
    }
  }

  // 绑定、来源证明与隐藏恢复。

  function create(candidate, initial) {
    const token = {},
      display = {
        node: candidate.number,
        name: 'display',
        host: null,
        fresh: false,
        owned: false,
        applied: null,
      };
    const arias = [candidate.scope, candidate.number].map((node) => ({
      node,
      name: 'aria',
      host: node.getAttribute('aria-label'),
      fresh: false,
      epoch: 0,
      owned: false,
      applied: null,
      newField: !knownAriaNodes.has(node),
      hiddenKnown: hiddenText.test(node.getAttribute('aria-label') || ''),
    }));
    for (const node of [candidate.scope, candidate.number]) knownAriaNodes.add(node);
    const newLeaves = candidate.leaves.map((node) => !knownTextNodes.has(node));
    for (const node of candidate.leaves) knownTextNodes.add(node);
    binding = {
      ...candidate,
      token,
      epoch: 0,
      hidden: false,
      echoAllowed: false,
      display,
      arias,
      newLeaves,
      presentation: null,
      maskKnown: new WeakMap(),
      text: { host: null, fresh: false, epoch: 0 },
    };
    display.host = fieldValue(display);
    display.fresh = sourceVisible() && display.host.value !== 'none';
    const amount = joined(binding);
    const accepted =
      initial &&
      display.fresh &&
      amount !== null &&
      arias.every((field) => parts(field.host, amount));
    if (accepted) {
      binding.text.host = amount;
      binding.text.fresh = true;
      for (const field of arias) field.fresh = true;
    } else {
      binding.hidden = !sourceVisible() || arias.some((field) => field.hiddenKnown);
      for (const field of arias) field.host = null;
    }
  }
  function forget() {
    if (!binding) return;
    if (binding.presentation) ownNodes.delete(binding.presentation);
    for (const field of [binding.display, ...binding.arias]) {
      release(field);
      field.host = null;
      field.fresh = false;
    }
    binding.text.host = null;
    binding.text.fresh = false;
    binding.presentation = null;
    binding = null;
  }
  function same(candidate) {
    return (
      binding &&
      candidate &&
      candidate.root === binding.root &&
      candidate.scope === binding.scope &&
      candidate.number === binding.number &&
      candidate.leaves.every((node, index) => node === binding.leaves[index])
    );
  }
  function fresh() {
    if (
      !binding ||
      !sourceVisible() ||
      !same(shape()) ||
      !binding.text.fresh ||
      !binding.display.fresh
    )
      return false;
    const amount = joined(binding);
    return (
      amount !== null &&
      amount === binding.text.host &&
      (amount !== state.lastOutput || binding.echoAllowed) &&
      binding.arias.every(
        (field) =>
          field.fresh &&
          field.epoch === binding.epoch &&
          (field.owned ? owns(field) : fieldValue(field) === field.host) &&
          parts(field.host, amount),
      )
    );
  }
  function acceptProof() {
    if (!binding || !sourceVisible() || !binding.display.fresh) return;
    const amount = joined(binding);
    // 新对象也可能只复制模块输出；没有独立宿主来源时保守等待其它值。
    if (
      amount === null ||
      amount === state.lastOutput ||
      !binding.leaves.some(
        (node, index) =>
          proof.text.get(node) === node.nodeValue ||
          (binding.newLeaves[index] && /^[0-9]+$/.test(node.nodeValue) && introduced(node)),
      )
    )
      return;
    if (
      !binding.arias.every(
        (field) =>
          !owners.get(field.node)?.has('aria') &&
          (proof.arias.get(field.node) === fieldValue(field) ||
            (field.newField && introduced(field.node))) &&
          parts(fieldValue(field), amount),
      )
    )
      return;
    binding.text.host = amount;
    binding.text.fresh = true;
    for (const field of binding.arias) {
      field.host = fieldValue(field);
      field.fresh = true;
      field.epoch = binding.epoch;
    }
    binding.hidden = false;
  }

  function resetProof() {
    proof = { text: new WeakMap(), arias: new WeakMap(), added: new WeakSet() };
  }
  // 只保存当前隐私代次的宿主事件证明；结构换代不转交旧绑定的金额缓存。
  function observeProof(record, value, candidate) {
    if (!candidate) return;
    const aria =
      record.type === 'attributes' &&
      record.attributeName === 'aria-label' &&
      [candidate.scope, candidate.number].includes(record.target);
    const text = record.type === 'characterData' && candidate.leaves.includes(record.target);
    if (aria || text) {
      const masked = hiddenText.test(value || '');
      if (masked || (hiddenText.test(record.oldValue || '') && !proofMasks.get(record.target)))
        resetProof();
      proofMasks.set(record.target, masked);
      if (!masked) {
        if (aria && typeof value === 'string') proof.arias.set(record.target, value);
        if (text && /^[0-9]+$/.test(value || '')) proof.text.set(record.target, value);
      }
    } else if (
      record.type === 'attributes' &&
      record.attributeName !== 'aria-label' &&
      (record.target.contains(candidate.number) || candidate.number.contains(record.target))
    ) {
      const proxy =
        Array.from(candidate.number.children).includes(record.target) &&
        record.attributeName === 'aria-hidden';
      if (
        !proxy &&
        (record.attributeName === 'class' ||
          hiddenAttribute(record.attributeName, record.oldValue) ||
          hiddenAttribute(record.attributeName, value))
      )
        resetProof();
    } else if (record.type === 'childList') {
      for (const node of record.addedNodes)
        if (
          !ownNodes.has(node) &&
          (node.contains(candidate.number) || candidate.number.contains(node))
        )
          proof.added.add(node);
    }
  }
  function acceptShowReceipt(candidate) {
    const anchor = eyeAnchor();
    syncEye(anchor);
    if (
      !binding ||
      !validShowReceipt(anchor) ||
      !same(candidate) ||
      eyeState.number !== candidate.number ||
      !sourceVisible() ||
      !visible(anchor.control) ||
      !binding.display.fresh
    )
      return;
    const amount = joined(candidate);
    if (
      amount === null ||
      amount === state.lastOutput ||
      !binding.arias.every(
        (field) =>
          !field.owned && !owners.get(field.node)?.has('aria') && parts(fieldValue(field), amount),
      )
    )
      return;
    binding.text.host = amount;
    binding.text.fresh = true;
    binding.text.epoch = binding.epoch;
    for (const field of binding.arias) {
      field.host = fieldValue(field);
      field.fresh = true;
      field.epoch = binding.epoch;
    }
    binding.hidden = false;
    binding.echoAllowed = false;
    eyeState.used = true;
    eyeState.reveal = false;
  }
  function validShowReceipt(anchor) {
    return (
      sameEye(anchor) &&
      eyeState.epoch === eyeEpoch &&
      eyeState.phase === 'shown' &&
      eyeState.reveal &&
      !eyeState.used &&
      anchor.action === '隐藏金额'
    );
  }
  function observeEye(record, value, anchor, amountTarget) {
    syncEye(anchor);
    if (!anchor) return;
    if (
      record.type === 'attributes' &&
      record.target === anchor.control &&
      record.attributeName === 'aria-label'
    ) {
      if (value === '显示金额') {
        hiddenEye(anchor, false, record.oldValue === '隐藏金额');
        clearSources(true);
      } else if (value === '隐藏金额' && record.oldValue === '显示金额') {
        if (
          sameEye(anchor) &&
          eyeState.phase === 'hidden' &&
          eyeState.hideAction &&
          eyeState.maskSeen
        ) {
          eyeState.phase = 'shown';
          eyeState.reveal = true;
          eyeState.used = false;
        }
      } else if (value !== '隐藏金额') {
        eyeState = null;
        eyeEpoch++;
      }
      return;
    }
    if (
      record.target === anchor.control &&
      (record.type === 'childList' ||
        (record.type === 'attributes' && record.attributeName === 'role' && value !== 'button'))
    ) {
      eyeState = null;
      eyeEpoch++;
      return;
    }
    if (
      record.type === 'childList' &&
      Array.from(record.removedNodes).some(
        (node) =>
          node.contains(anchor.root) ||
          node.contains(anchor.scope) ||
          node.contains(anchor.control),
      )
    ) {
      eyeState = null;
      eyeEpoch++;
      return;
    }
    const inScope = amountTarget(record.target) && !inOwnNode(record.target);
    let mask = false;
    if (inScope && record.type === 'characterData') mask = pureMask(value);
    else if (inScope && record.type === 'attributes' && record.attributeName === 'aria-label') {
      const field = binding && binding.arias.find((item) => item.node === record.target);
      mask = maskedAria(value) && !(field && field.owned && field.applied === value);
    } else if (inScope && record.type === 'childList')
      mask = Array.from(record.addedNodes).some(
        (node) =>
          !inOwnNode(node) &&
          (pureMask(node.textContent) ||
            (node.nodeType === 1 && maskedAria(node.getAttribute('aria-label')))),
      );
    if (
      !mask &&
      inScope &&
      record.type === 'childList' &&
      binding &&
      binding.scope === anchor.scope &&
      anchor.scope.contains(binding.number) &&
      !inOwnNode(binding.number) &&
      (record.target === binding.number || binding.number.contains(record.target))
    ) {
      const text = binding.number.textContent;
      mask = pureMask(text) && (text.match(/[*＊●•·]/g) || []).length >= 2;
    }
    if (mask) {
      hiddenEye(anchor, true, false);
      return;
    }
    if (
      eyeState &&
      record.type === 'attributes' &&
      ['hidden', 'aria-hidden', 'style'].includes(record.attributeName) &&
      (record.target.contains(anchor.scope) || inScope)
    ) {
      const proxy =
        binding &&
        Array.from(binding.number.children).includes(record.target) &&
        record.attributeName === 'aria-hidden';
      if (!proxy && hiddenAttribute(record.attributeName, value)) {
        eyeState = null;
        eyeEpoch++;
      }
    }
  }
  function sameEye(anchor) {
    return (
      !!eyeState &&
      !!anchor &&
      eyeState.root === anchor.root &&
      eyeState.scope === anchor.scope &&
      eyeState.control === anchor.control &&
      eyeState.href === window.location.href &&
      eyeState.session === state.nativeSession &&
      eyeState.nonce === state.nonce
    );
  }
  function syncEye(anchor) {
    if (eyeState && !sameEye(anchor)) {
      eyeState = null;
      eyeEpoch++;
    }
  }
  function hiddenEye(anchor, mask, action) {
    const previous = sameEye(anchor) && eyeState.phase === 'hidden' ? eyeState : null;
    eyeState = {
      root: anchor.root,
      scope: anchor.scope,
      control: anchor.control,
      number: previous ? previous.number : binding && binding.number,
      href: window.location.href,
      session: state.nativeSession,
      nonce: state.nonce,
      epoch: ++eyeEpoch,
      phase: 'hidden',
      maskSeen: mask || !!previous?.maskSeen,
      hideAction: action || !!previous?.hideAction,
      reveal: false,
      used: false,
    };
  }
  function introduced(node) {
    for (let current = node; current; current = current.parentElement)
      if (proof.added.has(current)) return true;
    return false;
  }
  function inOwnNode(node) {
    for (let current = node; current; current = current.parentElement)
      if (ownNodes.has(current)) return true;
    return false;
  }
  function clearSources(hidden) {
    if (!binding) return;
    if (hidden) resetProof();
    binding.epoch++;
    binding.hidden = !!hidden;
    binding.echoAllowed = false;
    binding.text.host = null;
    binding.text.fresh = false;
    for (const field of binding.arias) {
      field.host = null;
      field.fresh = false;
    }
  }
  function sourceVisible() {
    if (!binding || !visible(binding.scope)) return false;
    const ignore =
      owns(binding.display) &&
      binding.display.applied.value === 'none' &&
      binding.display.applied.priority === 'important'
        ? binding.number
        : null;
    if (!visible(binding.number, ignore)) return false;
    return Array.from(binding.number.children).every((span) => {
      const css = getComputedStyle(span);
      return (
        !span.hidden &&
        css.display !== 'none' &&
        css.visibility !== 'hidden' &&
        css.visibility !== 'collapse'
      );
    });
  }
  function sourceHasNoDigits() {
    return (
      binding &&
      isConnectedToDocument(binding.number) &&
      !/[0-9]/.test(binding.number.textContent) &&
      /^[\s*＊●•·.,-]*$/.test(binding.number.textContent)
    );
  }
  function hideOwned() {
    if (!binding || !current()) return;
    removePresentation();
    // stop恢复display的setter也可同步触发隐藏；仍自有时立即重新遮住残留原数字。
    if (
      !sourceHasNoDigits() &&
      owns(binding.display) &&
      (binding.display.applied.value !== 'none' || binding.display.applied.priority !== 'important')
    ) {
      write(binding.display, { value: 'none', priority: 'important' });
    }
    for (const field of binding.arias) if (owns(field)) write(field, '总金额，金额隐藏');
  }
  function removePresentation() {
    if (!binding || !binding.presentation) return;
    const node = binding.presentation;
    if (
      ownNodes.get(node) === binding.token &&
      node.parentElement === binding.scope &&
      isConnectedToDocument(binding.scope)
    ) {
      perform(
        () => binding.scope.removeChild(node),
        (record) =>
          record.type === 'childList' &&
          record.target === binding.scope &&
          record.removedNodes.length === 1 &&
          record.removedNodes[0] === node &&
          record.addedNodes.length === 0,
      );
    }
    ownNodes.delete(node);
    binding.presentation = null;
  }

  // 字段归属、受保护写入与变更分类。

  function perform(effect, expected, field, applied) {
    if (!current() || !observer) return false;
    drain();
    if (!current() || !observer) return false;
    const active = observer,
      wasBusy = busy;
    busy = true;
    try {
      effect();
      const records = active.takeRecords();
      if (!current()) return false;
      const selected = records.filter(expected);
      if (selected.length === 1) {
        ownRecords.set(selected[0], ++sequence);
        if (field) claim(field, applied);
      } else if (field) {
        release(field);
        field.fresh = false;
      }
      consume(records);
      return selected.length === 1 && (!field || owns(field));
    } finally {
      busy = wasBusy;
    }
  }
  function write(field, value) {
    if (!isConnectedToDocument(field.node)) return false;
    if (owns(field) && equal(field, fieldValue(field), value)) return true;
    const attribute = field.name === 'display' ? 'style' : 'aria-label';
    const before = field.node.getAttribute(attribute);
    const completed = perform(
      () => {
        if (field.name !== 'display') field.node.setAttribute('aria-label', value);
        else if (value.value) field.node.style.setProperty('display', value.value, value.priority);
        else field.node.style.removeProperty('display');
      },
      (record) => matches(record, field) && record.oldValue === before,
      field,
      value,
    );
    return completed && owns(field) && equal(field, fieldValue(field), value);
  }
  function owns(field) {
    return (
      !!binding &&
      field.owned &&
      owners.get(field.node)?.get(field.name) === binding.token &&
      equal(field, fieldValue(field), field.applied)
    );
  }
  function claim(field, value) {
    let fields = owners.get(field.node);
    if (!fields) owners.set(field.node, (fields = new Map()));
    fields.set(field.name, binding.token);
    field.owned = true;
    field.applied = value;
    if (field.name === 'aria') {
      proof.arias.delete(field.node);
      proofMasks.set(field.node, hiddenText.test(value || ''));
    }
  }
  function release(field) {
    const fields = owners.get(field.node);
    if (fields) {
      fields.delete(field.name);
      if (!fields.size) owners.delete(field.node);
    }
    field.owned = false;
    field.applied = null;
  }
  function fieldValue(field) {
    return field.name === 'display'
      ? {
          value: field.node.style.getPropertyValue('display'),
          priority: field.node.style.getPropertyPriority('display'),
        }
      : field.node.getAttribute('aria-label');
  }
  function equal(field, left, right) {
    return field.name === 'display'
      ? !!left && !!right && left.value === right.value && left.priority === right.priority
      : left === right;
  }
  function matches(record, field) {
    return (
      record.type === 'attributes' &&
      record.target === field.node &&
      record.attributeName === (field.name === 'display' ? 'style' : 'aria-label')
    );
  }
  function afterValue(records, index) {
    const record = records[index];
    if (record.type === 'childList') return null;
    for (let i = index + 1; i < records.length; i++)
      if (
        record.target === records[i].target &&
        record.type === records[i].type &&
        record.attributeName === records[i].attributeName
      )
        return records[i].oldValue;
    return record.type === 'attributes'
      ? record.target.getAttribute(record.attributeName)
      : record.target.nodeValue;
  }
  function styleHidden(value) {
    return (
      typeof value === 'string' &&
      /(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*(?:hidden|collapse))(?:\s*!important)?\s*(?:;|$)/i.test(
        value,
      )
    );
  }
  function hiddenAttribute(name, value) {
    return name === 'aria-hidden'
      ? value === 'true'
      : name === 'hidden'
        ? value !== null
        : styleHidden(value);
  }
  function pureMask(value) {
    return typeof value === 'string' && /[*＊●•·]/.test(value) && /^[\s*＊●•·.,-]*$/.test(value);
  }
  function maskedAria(value) {
    return typeof value === 'string' && hiddenText.test(value) && !/[0-9]/.test(value);
  }

  // 当前文档与固定 DOM 结构识别。

  function current() {
    if (!state || state.invalid) return false;
    if (doc !== document || state.href !== window.location.href || !validRoute(state.route)) {
      abandon();
      return false;
    }
    return true;
  }

  function currentDocumentRoute() {
    const url = new URL(window.location.href);
    return /^https?:$/.test(url.protocol) && !url.username && !url.password
      ? url.protocol + '//' + url.host + url.pathname
      : '';
  }
  function validRoute(route) {
    try {
      return (
        typeof route === 'string' &&
        route.length <= 8192 &&
        route === currentDocumentRoute() &&
        !new URL(route).search &&
        !new URL(route).hash
      );
    } catch (_) {
      return false;
    }
  }
  function frame() {
    const roots = Array.from(doc.querySelectorAll('.assets-all'));
    const totals = Array.from(doc.querySelectorAll('.total-asset'));
    if (roots.length !== 1 || totals.length !== 1) return null;
    const root = roots[0],
      block = root.children[0],
      scope = totals[0];
    if (
      root.tagName !== 'DIV' ||
      root.children.length !== 3 ||
      !block ||
      block.tagName !== 'DIV' ||
      block.children.length !== 3 ||
      block.children[1] !== scope ||
      scope.tagName !== 'DIV'
    )
      return null;
    const title = block.children[0],
      caption = title && title.children[0];
    if (
      !title ||
      title.tagName !== 'DIV' ||
      title.children.length !== 2 ||
      title.textContent.trim() !== '总金额(元)' ||
      (title.getAttribute('aria-label') && title.getAttribute('aria-label') !== '总金额(元)') ||
      !findProxyTextLeaf(caption) ||
      caption.textContent !== '总金额(元)'
    )
      return null;
    return { root, block, scope, title };
  }
  function shape() {
    const anchor = frame();
    if (!anchor) return null;
    const { root, block, scope } = anchor;
    const hostChildren = Array.from(scope.children).filter(
      (node) => !binding || node !== binding.presentation || ownNodes.get(node) !== binding.token,
    );
    if (hostChildren.length !== 1) return null;
    const number = hostChildren[0];
    if (
      number.tagName !== 'SPAN' ||
      !hasClass(number, 'wu-number') ||
      !hasClass(number, 'wu-alisans-number') ||
      number.getAttribute('role') !== 'text' ||
      number.getAttribute('data-mark') !== 'totalAsset' ||
      number.children.length !== 5
    )
      return null;
    const leaves = Array.from(number.children).map(findProxyTextLeaf);
    if (leaves.some((node) => !node)) return null;
    return { root, block, scope, number, leaves };
  }
  function eyeAnchor() {
    const anchor = frame(),
      control = anchor && anchor.title.children[1];
    if (
      !control ||
      control.tagName !== 'DIV' ||
      control.getAttribute('role') !== 'button' ||
      control.children.length !== 0 ||
      control.textContent !== ''
    )
      return null;
    const action = control.getAttribute('aria-label');
    return action === '显示金额' || action === '隐藏金额' ? { ...anchor, control, action } : null;
  }
  function joined(candidate) {
    const values = candidate.leaves.map((node) => node.nodeValue);
    if (
      !/^[1-9][0-9]{0,2}$/.test(values[0]) ||
      values[1] !== ',' ||
      !/^[0-9]{3}$/.test(values[2]) ||
      values[3] !== '.' ||
      !/^[0-9]{2}$/.test(values[4])
    )
      return null;
    return normalizeAmount(values.join(''));
  }
  function findProxyTextLeaf(node) {
    return node &&
      node.tagName === 'SPAN' &&
      node.children.length === 0 &&
      node.childNodes.length === 1 &&
      node.firstChild.nodeType === 3 &&
      node.getAttribute('aria-hidden') === 'true'
      ? node.firstChild
      : null;
  }
  function visible(element, ignoreDisplay = null) {
    if (!isConnectedToDocument(element)) return false;
    for (let e = element; e; e = e.parentElement) {
      const css = getComputedStyle(e);
      if (
        e.hidden ||
        e.getAttribute('aria-hidden') === 'true' ||
        css.visibility === 'hidden' ||
        css.visibility === 'collapse' ||
        (e !== ignoreDisplay && css.display === 'none')
      )
        return false;
    }
    return true;
  }
  function hasClass(node, name) {
    return (node.getAttribute('class') || '').split(/\s+/).includes(name);
  }
  function isConnectedToDocument(node) {
    return !!node && node.ownerDocument === doc && node.isConnected;
  }

  // 金额与代次的纯格式判断。

  function normalizeAmount(value) {
    if (typeof value !== 'string' || !new RegExp('^' + numberPattern + '$').test(value.trim()))
      return null;
    const parts = value.trim().replace(/,/g, '').split('.');
    return parts[0].replace(/^0+(?=[0-9])/, '') + '.' + (parts[1] || '').padEnd(2, '0');
  }
  function parts(value, amount) {
    if (
      typeof value !== 'string' ||
      hiddenText.test(value) ||
      (value.match(new RegExp(numberPattern, 'g')) || []).length !== 1
    )
      return null;
    const match = new RegExp(
      '^总金额(?:[（(]元[）)])?[\\s:：，,]*[¥￥]?[\\s]*(' + numberPattern + ')(?![0-9.,eE%％])',
    ).exec(value);
    if (!match || normalizeAmount(match[1]) !== amount) return null;
    return {
      before: value.slice(0, match[0].length - match[1].length),
      after: value.slice(match[0].length),
    };
  }
  function isValidRevision(value) {
    return typeof value === 'string' && /^(?:0|[1-9][0-9]{0,18})$/.test(value);
  }
  function compareRevisions(left, right) {
    return left.length === right.length
      ? left === right
        ? 0
        : left > right
          ? 1
          : -1
      : left.length > right.length
        ? 1
        : -1;
  }
  return dispatch;
})();
