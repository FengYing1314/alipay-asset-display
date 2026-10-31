(() => {
  'use strict';
  const key = Symbol.for('io.github.fengying.alipaydisplay.total-assets.v1');
  const previous = window[key];
  if (previous && previous.document === document && typeof previous.dispatch === 'function')
    return previous.dispatch;
  const kinds = { total: '总资产', balance: '余额', yuebao: '余额宝', fund: '基金' };
  const number = '(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?';
  const numeric = new RegExp('^' + number + '$');
  const format = /^(?:0|[1-9][0-9]{0,2}(?:,[0-9]{3})*)\.[0-9]{2}$/;
  const hiddenText = /[*＊●•·]{2,}|隐藏|不可见|不显示/;
  const textOwners = new WeakMap();
  const ariaOwners = new WeakMap();
  const ownRecords = new WeakMap();
  // 每个scope/kind只留最后一次模块实际输出；数字失归属后不能凭同值自证宿主来源。
  const moduleAmounts = new WeakMap();
  const retired = new Set();
  const doc = document;
  let state = null;
  let observer = null;
  let roots = [];
  let bindings = new Map();
  let writing = false;
  let processing = false;
  let sequence = 0;

  // 外部命令与会话释放。

  function dispatch(command) {
    try {
      if (command && typeof command === 'object' && command.op === 'abandon') {
        if (
          !state ||
          command.nonce !== state.nonce ||
          command.nativeSession !== state.nativeSession
        )
          return result('stale-session');
        abandon();
        return result('abandoned');
      }
      if (state && !state.invalid) currentDocument();
      if (
        !command ||
        typeof command !== 'object' ||
        doc !== document ||
        !validRoute(command.route) ||
        typeof command.nativeSession !== 'string' ||
        !/^[A-Za-z0-9_-]{1,96}$/.test(command.nativeSession)
      ) {
        return result('rejected');
      }
      const profile = command.profile === undefined ? 'total-assets' : command.profile;
      if (profile !== 'total-assets' && profile !== 'yuebao') return result('invalid-config');
      if (command.op === 'prepare') {
        if (retired.has(command.nativeSession)) return result('stale-session');
        if (state && state.nativeSession === command.nativeSession && state.profile !== profile)
          return result('stale-session');
        if (
          state &&
          state.nativeSession === command.nativeSession &&
          state.route === command.route &&
          window.location.href === state.href &&
          !state.invalid
        )
          return result('prepared');
        if (!window.crypto || typeof window.crypto.getRandomValues !== 'function')
          return result('unavailable');
        // prepare只改私有状态；旧字段在下一次受验证的apply/stop时才处理。
        disconnect(false);
        if (state && state.nativeSession !== command.nativeSession)
          retired.add(state.nativeSession);
        for (const binding of bindings.values()) {
          binding.text.fresh = false;
          binding.aria.fresh = false;
          binding.text.host = null;
          binding.aria.host = null;
        }
        const random = new Uint32Array(4);
        window.crypto.getRandomValues(random);
        state = {
          nativeSession: command.nativeSession,
          profile,
          route: command.route,
          href: window.location.href,
          invalid: false,
          nonce: Array.from(random, (n) => n.toString(16).padStart(8, '0')).join(''),
          revision: null,
          amounts: null,
          stopped: false,
          targets: 0,
        };
        Object.defineProperty(window, key, {
          value: { document: doc, dispatch },
          configurable: true,
        });
        return result('prepared');
      }
      if (
        !state ||
        command.nonce !== state.nonce ||
        command.nativeSession !== state.nativeSession ||
        command.route !== state.route ||
        profile !== state.profile ||
        !currentDocument()
      )
        return result('stale-session');
      if (
        !isValidRevision(command.revision) ||
        (state.revision !== null && compareRevisions(command.revision, state.revision) < 0)
      )
        return result('stale-revision');
      if (command.op === 'stop') {
        state.revision = command.revision;
        return stop();
      }
      if (
        command.op !== 'apply' ||
        !command.amounts ||
        Object.keys(kinds).some(
          (kind) =>
            typeof command.amounts[kind] !== 'string' ||
            !format.test(command.amounts[kind]) ||
            normalizeAmount(command.amounts[kind]).length > 12,
        )
      )
        return result('invalid-config');
      if (state.stopped && state.revision === command.revision) return result('stopped');
      if (
        state.revision === command.revision &&
        state.amounts &&
        Object.keys(kinds).some((kind) => state.amounts[kind] !== command.amounts[kind])
      )
        return result('revision-conflict');
      state.revision = command.revision;
      state.amounts = Object.assign({}, command.amounts);
      state.stopped = false;
      reconcile();
      return result(null);
    } catch (_) {
      abandon();
      return result('unavailable');
    }
  }
  function result(status, extra) {
    let applied = 0,
      hidden = 0;
    const targets = state ? state.targets : 0;
    for (const binding of bindings.values()) {
      if (currentText(binding) && currentAria(binding) && binding.text.fresh && binding.aria.fresh)
        applied++;
      else if (binding.hidden) hidden++;
    }
    applied = Math.min(targets, applied);
    hidden = Math.min(targets - applied, hidden);
    const limit = targetLimit();
    if (!status)
      status =
        applied === limit
          ? 'applied'
          : applied > 0
            ? 'partial'
            : targets === 0
              ? 'unknown-shape'
              : hidden > 0
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
        unknown: limit - targets,
      },
      extra || {},
    );
  }
  function stop() {
    drain();
    let restored = 0,
      masked = 0;
    const next = shape();
    for (const [kind, binding] of bindings) {
      const candidate = next && next.found.get(kind);
      const valid =
        candidate &&
        candidate.scope === binding.scope &&
        candidate.text === binding.text.node &&
        trusted(binding);
      if (valid) {
        let textRestored = !binding.text.owned;
        if (currentText(binding)) {
          textRestored =
            write(binding, binding.text, binding.text.host) &&
            binding.text.node.nodeValue === binding.text.host;
          if (textRestored) restored++;
        }
        if (currentAria(binding)) {
          if (textRestored) write(binding, binding.aria, binding.aria.host);
          else {
            invalidate(binding);
            masked++;
          }
        }
      } else {
        if (currentText(binding) || currentAria(binding)) masked++;
        invalidate(binding);
      }
      forget(binding);
    }
    bindings.clear();
    state.targets = 0;
    state.stopped = true;
    state.amounts = null;
    disconnect();
    return result('stopped', { restored, masked });
  }
  function abandon() {
    // 身份失效只清内存；绝不在未知路由恢复或遮写任何DOM字段。
    disconnect(false);
    for (const binding of bindings.values()) forget(binding);
    bindings.clear();
    if (state) {
      retired.add(state.nativeSession);
      state.invalid = true;
      state.href = null;
      state.amounts = null;
      state.targets = 0;
    }
  }
  function disconnect(consumePending = true) {
    if (observer) {
      if (consumePending) drain();
      observer.disconnect();
    }
    observer = null;
    roots = [];
  }

  // 显示协调与宿主变更处理。

  function reconcile() {
    if (!state || state.stopped || !state.amounts || processing || writing || !currentDocument())
      return;
    drain();
    const next = shape();
    state.targets = next ? next.found.size : 0;
    if (!next) {
      for (const binding of bindings.values()) {
        invalidate(binding);
        forget(binding);
      }
      bindings.clear();
      return;
    }
    attach(next.roots);
    const freshReplacements = new Set();
    for (const [kind, binding] of Array.from(bindings)) {
      const candidate = next.found.get(kind);
      if (!candidate || candidate.scope !== binding.scope || candidate.text !== binding.text.node) {
        const transfer =
          binding.replacement &&
          binding.replacement.text === (candidate && candidate.text) &&
          binding.replacement.epoch === binding.epoch &&
          canRetainAria(binding, candidate);
        if (transfer) {
          const aria = { ...binding.aria },
            epoch = binding.epoch;
          const sameSource = trustedSameAmount(
            binding,
            normalizeAmount(candidate.text.nodeValue),
            candidate.text,
          );
          forget(binding);
          const replacement = newBinding(kind, candidate, false, sameSource);
          replacement.epoch = epoch;
          replacement.text.hostEpoch = epoch;
          replacement.aria = aria;
          if (aria.owned) ariaOwners.set(candidate.scope, replacement);
          bindings.set(kind, replacement);
        } else {
          const fresh =
            binding.freshReplacement &&
            candidate &&
            candidate.scope === binding.scope &&
            candidate.root === binding.root &&
            binding.freshReplacement.text === candidate.text &&
            binding.freshReplacement.epoch === binding.epoch &&
            !binding.hidden;
          const previousEpoch = binding.epoch;
          invalidate(binding);
          const moduleMaskedAria =
            fresh &&
            binding.epoch === previousEpoch + 1 &&
            !binding.hidden &&
            visible(candidate.scope) &&
            visible(candidate.text, candidate.ariaProxyLeaf) &&
            currentAria(binding) &&
            binding.aria.applied === binding.label + '，金额隐藏';
          forget(binding);
          bindings.delete(kind);
          if (moduleMaskedAria) freshReplacements.add(kind);
        }
      }
    }
    for (const [kind, candidate] of next.found) {
      let binding = bindings.get(kind);
      if (!binding) {
        binding = newBinding(kind, candidate, freshReplacements.has(kind));
        bindings.set(kind, binding);
      }
      const parts = trusted(binding);
      if (!parts) {
        maskOwned(binding);
        continue;
      }
      const applied = state.amounts[kind];
      if (!currentText(binding) || binding.text.applied !== applied)
        write(binding, binding.text, applied, true);
      if (!currentDocument()) return;
      // 文本setter可同步触发宿主隐藏/替换；朗读写入必须使用重验后的同代原文。
      const freshParts = trusted(binding);
      if (
        !currentText(binding) ||
        binding.text.applied !== applied ||
        !freshParts ||
        hiddenText.test(binding.scope.getAttribute('aria-label') || '')
      ) {
        maskOwned(binding);
        continue;
      }
      const spoken = freshParts.before + applied + freshParts.after;
      if (!currentAria(binding) || binding.aria.applied !== spoken)
        write(binding, binding.aria, spoken);
      if (!currentDocument()) return;
      if (!trusted(binding)) maskOwned(binding);
    }
  }
  function consume(records) {
    if (!records.length) return;
    if (!currentDocument()) return;
    const oldProcessing = processing;
    processing = true;
    try {
      // 在任何自写之前还原整批记录的逐次新值，保留隐藏→恢复的中间证据。
      const events = records.map((record, index) => ({
        record,
        value: afterValue(records, index),
      }));
      const latestShape = shape();
      const changedAria = new Set(
        events
          .filter(
            ({ record }) =>
              !ownRecords.has(record) &&
              record.type === 'attributes' &&
              record.attributeName === 'aria-label',
          )
          .map(({ record }) => record.target),
      );
      const mask = new Set();
      const touched = new Set();
      for (const { record, value } of events) {
        const own = ownRecords.get(record);
        if (own) {
          ownRecords.delete(record);
          continue;
        }
        const eventSequence = ++sequence;
        for (const binding of bindings.values()) {
          if (fieldMatches(record, binding.text) || fieldMatches(record, binding.aria)) {
            touched.add(binding);
            const field = fieldMatches(record, binding.text) ? binding.text : binding.aria;
            const isText = field === binding.text;
            const knownOwnOld = field.owned && field.applied === record.oldValue;
            const oldHidden =
              hiddenText.test(record.oldValue || '') && !knownOwnOld && !field.hiddenKnown;
            const nowHidden = hiddenText.test(value || '');
            if (oldHidden || nowHidden) {
              loseFreshness(binding, true);
              mask.add(binding);
            }
            const priorTextSequence = binding.text.hostSequence;
            const amount = isText ? normalizeAmount(value) : ariaAmount(value, binding);
            const ambiguous = ambiguousAmount(binding, amount);
            releaseField(binding, field);
            field.hiddenKnown = nowHidden;
            field.host = value;
            field.hostEpoch = binding.epoch;
            field.hostSequence = eventSequence;
            field.fresh =
              !ambiguous && (isText ? amount !== null : typeof value === 'string' && !nowHidden);
            if (!field.fresh) {
              // 掩码、占位和模块产物都不能成为后续恢复源。
              field.host = null;
              if (!nowHidden) loseFreshness(binding, false);
              mask.add(binding);
            } else if (isText) {
              const paired =
                binding.aria.fresh &&
                binding.aria.hostEpoch === binding.epoch &&
                binding.aria.hostSequence > priorTextSequence &&
                ariaParts(
                  binding.aria.host,
                  binding.label,
                  normalizeAmount(value),
                  binding.singleNumber,
                );
              if (!paired) {
                binding.aria.fresh = false;
                binding.aria.host = null;
                mask.add(binding);
              }
            }
          } else if (record.type === 'childList' && structureAffects(record, binding)) {
            const candidate = latestShape && latestShape.found.get(binding.kind);
            if (
              !addedHidden(record, candidate) &&
              !changedAria.has(binding.scope) &&
              canRetainAria(binding, candidate)
            ) {
              // 仅数字叶换代时，未变且同值的aria仍属同一DOM字段，不能一并抹除。
              const sameSource = trustedSameAmount(
                binding,
                normalizeAmount(candidate.text.nodeValue),
              );
              binding.text.fresh = false;
              binding.text.host = null;
              binding.text.hostSequence = 0;
              binding.replacement = {
                text: candidate.text,
                epoch: binding.epoch,
                trustedSame: sameSource,
              };
            } else {
              const candidateVisible =
                candidate &&
                candidate.scope === binding.scope &&
                candidate.root === binding.root &&
                visible(candidate.text, candidate.ariaProxyLeaf) &&
                visible(candidate.scope);
              const fresh =
                candidateVisible &&
                candidate.text !== binding.text.node &&
                !binding.hidden &&
                normalizeAmount(candidate.text.nodeValue) !== null &&
                !ambiguousAmount(
                  binding,
                  normalizeAmount(candidate.text.nodeValue),
                  candidate.text,
                ) &&
                !addedHidden(record, candidate) &&
                !changedAria.has(binding.scope) &&
                currentAria(binding);
              loseFreshness(
                binding,
                !visible(binding.scope) || !(candidateVisible || textVisible(binding)),
              );
              if (fresh) binding.freshReplacement = { text: candidate.text, epoch: binding.epoch };
              mask.add(binding);
            }
          } else if (
            record.type === 'attributes' &&
            record.attributeName !== 'aria-label' &&
            record.target.contains(binding.text.node)
          ) {
            if (record.target === binding.ariaProxyLeaf && record.attributeName === 'aria-hidden')
              continue;
            // 无法可靠回看class对应的历史CSS，任何此类祖先变化都保守丢弃旧源。
            const hidden =
              hiddenAttribute(record.attributeName, record.oldValue) ||
              hiddenAttribute(record.attributeName, value) ||
              !visible(binding.scope) ||
              !textVisible(binding);
            if (hidden || record.attributeName === 'class') {
              loseFreshness(binding, hidden);
              mask.add(binding);
            }
          }
        }
      }
      for (const binding of mask) {
        if (!trusted(binding)) maskOwned(binding);
      }
      for (const binding of touched) {
        if (
          binding.text.fresh &&
          binding.aria.fresh &&
          visible(binding.scope) &&
          textVisible(binding)
        )
          binding.hidden = false;
      }
    } finally {
      processing = oldProcessing;
    }
  }
  function drain() {
    if (observer) consume(observer.takeRecords());
  }
  function attach(nextRoots) {
    if (observer && roots.length === nextRoots.length && roots.every((e, i) => e === nextRoots[i]))
      return;
    if (observer) {
      drain();
      observer.disconnect();
    }
    roots = nextRoots;
    const epochNonce = state.nonce;
    const activeObserver = new MutationObserver((records) => {
      try {
        if (
          observer !== activeObserver ||
          !state ||
          state.nonce !== epochNonce ||
          state.stopped ||
          !currentDocument()
        )
          return;
        consume(records);
        reconcile();
      } catch (_) {
        abandon();
      }
    });
    observer = activeObserver;
    const observed = new Set();
    for (const root of roots) {
      observer.observe(root, {
        subtree: true,
        childList: true,
        characterData: true,
        characterDataOldValue: true,
        attributes: true,
        attributeOldValue: true,
        attributeFilter: ['aria-label', 'aria-hidden', 'hidden', 'class', 'style'],
      });
      for (
        let parent = root.parentElement, depth = 0;
        parent && depth < 32;
        parent = parent.parentElement, depth++
      ) {
        if (!observed.has(parent) && !roots.includes(parent)) {
          observed.add(parent);
          observer.observe(parent, {
            childList: true,
            attributes: true,
            attributeOldValue: true,
            attributeFilter: ['aria-hidden', 'hidden', 'class', 'style'],
          });
        }
      }
    }
  }

  // 绑定、来源新鲜度与最后输出屏障。

  function newBinding(kind, candidate, moduleMaskedAria = false, trustedSame = false) {
    const text = candidate.text.nodeValue,
      aria = candidate.scope.getAttribute('aria-label');
    const binding = {
      kind,
      label: state.profile === 'yuebao' ? '总金额' : kinds[kind],
      singleNumber: state.profile === 'yuebao',
      ariaProxyLeaf: candidate.ariaProxyLeaf || null,
      scope: candidate.scope,
      root: candidate.root,
      epoch: 0,
      replacement: null,
      freshReplacement: null,
      hidden:
        !visible(candidate.scope) ||
        !visible(candidate.text, candidate.ariaProxyLeaf) ||
        hiddenText.test(text || '') ||
        hiddenText.test(aria || ''),
      text: {
        node: candidate.text,
        type: 'characterData',
        host: text,
        fresh: normalizeAmount(text) !== null,
        owned: false,
        applied: null,
        hostEpoch: 0,
        hostSequence: 0,
        hiddenKnown: hiddenText.test(text || ''),
      },
      aria: {
        node: candidate.scope,
        type: 'attributes',
        host: aria,
        fresh: typeof aria === 'string' && !hiddenText.test(aria),
        owned: false,
        applied: null,
        hostEpoch: 0,
        hostSequence: 0,
        hiddenKnown: hiddenText.test(aria || ''),
      },
    };
    if (moduleMaskedAria) {
      // 新host数字叶是fresh；此处的隐藏aria只是旧绑定自清理产物，不当作宿主隐私信号。
      binding.hidden = false;
      binding.aria.host = null;
      binding.aria.fresh = false;
    } else if (binding.hidden) loseFreshness(binding, true);
    if (!trustedSame && lastModuleAmount(candidate.scope, kind) === normalizeAmount(text))
      loseFreshness(binding, false);
    return binding;
  }
  function forget(binding) {
    binding.replacement = null;
    binding.freshReplacement = null;
    for (const field of [binding.text, binding.aria]) {
      releaseField(binding, field);
      field.host = null;
      field.fresh = false;
      field.hostSequence = 0;
      field.hostEpoch = -1;
    }
  }
  function trusted(binding) {
    if (
      !binding.text.fresh ||
      !binding.aria.fresh ||
      binding.text.hostEpoch !== binding.epoch ||
      binding.aria.hostEpoch !== binding.epoch ||
      !visible(binding.scope) ||
      !textVisible(binding)
    )
      return null;
    if (binding.text.owned && !currentText(binding)) return null;
    if (binding.aria.owned && !currentAria(binding)) return null;
    const amount = normalizeAmount(binding.text.host);
    return amount === null
      ? null
      : ariaParts(binding.aria.host, binding.label, amount, binding.singleNumber);
  }
  function loseFreshness(binding, hidden) {
    if (typeof hidden === 'boolean') binding.hidden = hidden;
    binding.epoch++;
    binding.replacement = null;
    binding.freshReplacement = null;
    binding.text.fresh = false;
    binding.aria.fresh = false;
    binding.text.host = null;
    binding.aria.host = null;
    binding.text.hostSequence = 0;
    binding.aria.hostSequence = 0;
  }
  function invalidate(binding, hidden) {
    loseFreshness(binding, hidden);
    maskOwned(binding);
  }
  function maskOwned(binding) {
    if (currentText(binding)) write(binding, binding.text, '****');
    if (currentAria(binding)) write(binding, binding.aria, binding.label + '，金额隐藏');
  }
  function canRetainAria(binding, candidate) {
    if (
      !candidate ||
      candidate.scope !== binding.scope ||
      candidate.root !== binding.root ||
      candidate.text === binding.text.node ||
      !visible(candidate.text, candidate.ariaProxyLeaf) ||
      !visible(candidate.scope) ||
      !binding.aria.fresh ||
      binding.aria.hostEpoch !== binding.epoch
    )
      return false;
    if (
      binding.aria.owned
        ? !currentAria(binding)
        : binding.scope.getAttribute('aria-label') !== binding.aria.host
    )
      return false;
    const amount = normalizeAmount(candidate.text.nodeValue);
    return (
      amount !== null &&
      !ambiguousAmount(binding, amount, candidate.text) &&
      ariaParts(binding.aria.host, binding.label, amount, binding.singleNumber) !== null
    );
  }
  function trustedSameAmount(binding, amount, candidateNode = null) {
    if (amount === null) return false;
    if (
      binding.text.fresh &&
      binding.text.hostEpoch === binding.epoch &&
      normalizeAmount(binding.text.host) === amount
    )
      return true;
    const replacement = binding.replacement;
    return (
      !!candidateNode &&
      replacement &&
      replacement.text === candidateNode &&
      replacement.epoch === binding.epoch &&
      replacement.trustedSame &&
      normalizeAmount(candidateNode.nodeValue) === amount
    );
  }
  function ambiguousAmount(binding, amount, candidateNode = null) {
    return (
      amount !== null &&
      lastModuleAmount(binding.scope, binding.kind) === amount &&
      !trustedSameAmount(binding, amount, candidateNode)
    );
  }
  function rememberModuleAmount(binding, value) {
    const amount = normalizeAmount(value);
    if (amount === null) return;
    let values = moduleAmounts.get(binding.scope);
    if (!values) moduleAmounts.set(binding.scope, (values = new Map()));
    values.set(binding.kind, amount);
  }
  function lastModuleAmount(scope, kind) {
    return moduleAmounts.get(scope)?.get(kind) || null;
  }
  function currentText(binding) {
    return (
      textOwners.get(binding.text.node) === binding &&
      binding.text.owned &&
      binding.text.node.nodeValue === binding.text.applied
    );
  }
  function currentAria(binding) {
    return (
      ariaOwners.get(binding.scope) === binding &&
      binding.aria.owned &&
      binding.scope.getAttribute('aria-label') === binding.aria.applied
    );
  }
  function sourceAria(scope, old) {
    return old && old.scope === scope && currentAria(old)
      ? old.aria.host || old.label
      : scope.getAttribute('aria-label');
  }

  // 字段归属、受保护写入与变更分类。

  function write(binding, field, value, trackAmount = false) {
    if (!currentDocument() || !isConnectedToDocument(field.node) || !observer) return false;
    drain();
    if (!currentDocument() || !observer) return false;
    const before = valueOf(field);
    if (before === value && field.owned) return true;
    const previousWriting = writing;
    const activeObserver = observer;
    writing = true;
    try {
      if (field.type === 'attributes') field.node.setAttribute('aria-label', value);
      else field.node.nodeValue = value;
      const records = activeObserver.takeRecords();
      if (!currentDocument()) return false;
      const exact = records.filter((record) => fieldMatches(record, field));
      if (
        trackAmount &&
        field.type === 'characterData' &&
        exact.length &&
        exact[0].oldValue === before &&
        normalizeAmount(afterValue(records, records.indexOf(exact[0]))) === normalizeAmount(value)
      ) {
        rememberModuleAmount(binding, value);
      }
      // 写前先排空队列，写后立即领取本次记录；多条同字段记录无法归因时一律视为宿主更新。
      if (exact.length === 1 && exact[0].oldValue === before && valueOf(field) === value) {
        ownRecords.set(exact[0], { sequence: ++sequence, binding, field });
        field.owned = true;
        field.applied = value;
        (field.type === 'attributes' ? ariaOwners : textOwners).set(field.node, binding);
      } else {
        field.owned = false;
        field.fresh = false;
      }
      consume(records);
      return field.owned;
    } finally {
      writing = previousWriting;
    }
  }
  function releaseField(binding, field) {
    (field.type === 'attributes' ? ariaOwners : textOwners).delete(field.node);
    field.owned = false;
    field.applied = null;
  }
  function fieldMatches(record, field) {
    return (
      record.target === field.node &&
      record.type === field.type &&
      (field.type !== 'attributes' || record.attributeName === 'aria-label')
    );
  }
  function valueOf(field) {
    return field.type === 'attributes'
      ? field.node.getAttribute('aria-label')
      : field.node.nodeValue;
  }
  function sameField(left, right) {
    return (
      left.target === right.target &&
      left.type === right.type &&
      (left.type !== 'attributes' || left.attributeName === right.attributeName)
    );
  }
  function afterValue(records, index) {
    const record = records[index];
    if (record.type === 'childList') return null;
    for (let i = index + 1; i < records.length; i++) {
      if (sameField(record, records[i])) return records[i].oldValue;
    }
    return record.type === 'attributes'
      ? record.target.getAttribute(record.attributeName)
      : record.target.nodeValue;
  }
  function hiddenAttribute(name, value) {
    if (name === 'aria-hidden') return value === 'true';
    if (name === 'hidden') return value !== null;
    return (
      name === 'style' &&
      typeof value === 'string' &&
      /(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*(?:hidden|collapse))(?:\s*!important)?\s*(?:;|$)/i.test(
        value,
      )
    );
  }
  function structureAffects(record, binding) {
    if (!isConnectedToDocument(binding.scope) || !isConnectedToDocument(binding.text.node))
      return true;
    if (binding.scope.contains(record.target) && record.target.contains(binding.text.node))
      return true;
    return Array.from(record.addedNodes || [])
      .concat(Array.from(record.removedNodes || []))
      .some((node) => node.contains(binding.scope) || node.contains(binding.text.node));
  }
  function addedHidden(record, candidate) {
    const pending = Array.from(record.addedNodes || []);
    for (let visits = 0; pending.length; visits++) {
      if (visits >= 128) return true;
      const node = pending.shift();
      if (node.nodeType === 3) {
        if (/^[*＊●•·]{2,}$/.test((node.nodeValue || '').trim())) return true;
      } else if (node.nodeType === 1) {
        if (
          hiddenText.test(node.getAttribute('aria-label') || '') ||
          node.hidden ||
          (node !== (candidate && candidate.ariaProxyLeaf) &&
            node.getAttribute('aria-hidden') === 'true') ||
          hiddenAttribute('style', node.getAttribute('style'))
        )
          return true;
        pending.push(...node.childNodes);
      }
    }
    return false;
  }
  function textVisible(binding) {
    return visible(binding.text.node, binding.ariaProxyLeaf);
  }

  // 当前文档与固定 DOM 结构识别。

  function currentDocument() {
    if (!state || state.invalid) return false;
    if (doc !== document || window.location.href !== state.href || !validRoute(state.route)) {
      abandon();
      return false;
    }
    return true;
  }

  function currentDocumentRoute() {
    const u = new URL(window.location.href);
    if (!/^https?:$/.test(u.protocol) || u.username || u.password) return '';
    return u.protocol + '//' + u.host + u.pathname;
  }
  function validRoute(route) {
    if (typeof route !== 'string' || route.length > 8192) return false;
    try {
      return route === currentDocumentRoute() && !new URL(route).search && !new URL(route).hash;
    } catch (_) {
      return false;
    }
  }
  function shape() {
    return state && state.profile === 'yuebao' ? yuebaoShape() : totalAssetsShape();
  }
  function totalAssetsShape() {
    const over = Array.from(doc.querySelectorAll('#overview-card, .overview-card'));
    const assets = Array.from(doc.querySelectorAll('#asset-card, .asset-card'));
    if (over.length !== 1 || assets.length !== 1 || over[0] === assets[0]) return null;
    const overviewNodes = walk(over[0]),
      assetNodes = walk(assets[0]);
    if (!overviewNodes || !assetNodes) return null;
    const found = new Map();
    const totals = overviewNodes.filter((e) => {
      const old = bindings.get('total');
      const aria = sourceAria(e, old);
      return (
        visible(e) &&
        e.getAttribute('role') === 'button' &&
        typeof aria === 'string' &&
        aria.startsWith(kinds.total)
      );
    });
    if (totals.length === 1) {
      const scope = totals[0];
      const outer = scope.children.length === 1 ? scope.children[0] : null;
      const inner =
        outer && outer.tagName === 'SPAN' && outer.children.length === 1 ? outer.children[0] : null;
      let text = findTextLeaf(inner);
      // 当前隐藏模板把内层数字span替换成outer span中的掩码，不能沿用旧数字叶。
      if (
        !text &&
        outer &&
        outer.tagName === 'SPAN' &&
        hiddenText.test(sourceAria(scope, bindings.get('total')) || '') &&
        /^[*＊●•·]{2,}$/.test(outer.textContent.trim())
      )
        text = findTextLeaf(outer);
      if (text) found.set('total', { root: over[0], scope, text });
    }
    for (const kind of ['balance', 'yuebao', 'fund']) {
      const matches = assetNodes.filter(
        (e) =>
          visible(e) && e.children.length === 2 && e.children[0].textContent.trim() === kinds[kind],
      );
      if (matches.length !== 1) continue;
      const scope = matches[0],
        right = scope.children[1];
      if (right.children.length !== 2) continue;
      const main = right.children[0];
      const text = main.children.length === 1 ? findTextLeaf(main.children[0]) : null;
      if (text) found.set(kind, { root: assets[0], scope, text });
    }
    return { roots: [over[0], assets[0]], found };
  }
  function yuebaoShape() {
    const matches = Array.from(doc.querySelectorAll('#amountView'));
    if (matches.length !== 1) return null;
    const scope = matches[0],
      parent = scope.parentElement;
    if (
      scope.tagName !== 'DIV' ||
      scope.getAttribute('role') !== 'button' ||
      !parent ||
      parent.tagName !== 'DIV' ||
      parent.children.length !== 2 ||
      parent.children[1] !== scope
    )
      return null;
    const title = parent.children[0],
      caption = title && title.children[0];
    if (
      !title ||
      title.tagName !== 'DIV' ||
      title.children.length !== 3 ||
      title.textContent.trim() !== '总金额(元)' ||
      !caption ||
      !findTextLeaf(caption) ||
      caption.textContent !== '总金额(元)' ||
      caption.getAttribute('aria-hidden') !== 'true'
    )
      return null;
    const titleAria = title.getAttribute('aria-label');
    if (titleAria !== null && titleAria.trim() !== '' && titleAria.trim() !== '总金额(元)')
      return null;
    if (scope.children.length !== 2) return null;
    const span = scope.children[0],
      icon = scope.children[1],
      text = findTextLeaf(span);
    if (
      !text ||
      span.getAttribute('aria-hidden') !== 'true' ||
      String(icon.tagName).toUpperCase() !== 'SVG' ||
      icon.children.length !== 1 ||
      icon.getAttribute('aria-hidden') !== 'true'
    )
      return null;
    return {
      roots: [parent],
      found: new Map([['yuebao', { root: parent, scope, text, ariaProxyLeaf: span }]]),
    };
  }
  function targetLimit() {
    return state && state.profile === 'yuebao' ? 1 : 4;
  }
  function walk(root) {
    const result = [],
      pending = [root];
    while (pending.length) {
      if (result.length >= 1024) return null;
      const e = pending.shift();
      result.push(e);
      for (const child of e.children) pending.push(child);
    }
    return result;
  }
  function findTextLeaf(span) {
    return span &&
      span.tagName === 'SPAN' &&
      span.children.length === 0 &&
      span.childNodes.length === 1 &&
      span.firstChild.nodeType === 3
      ? span.firstChild
      : null;
  }
  function visible(node, ariaProxyLeaf = null) {
    if (!isConnectedToDocument(node)) return false;
    for (let e = node.nodeType === 1 ? node : node.parentElement; e; e = e.parentElement) {
      const css = getComputedStyle(e);
      if (
        e.hidden ||
        (e !== ariaProxyLeaf && e.getAttribute('aria-hidden') === 'true') ||
        css.display === 'none' ||
        css.visibility === 'hidden' ||
        css.visibility === 'collapse'
      )
        return false;
    }
    return true;
  }
  function isConnectedToDocument(node) {
    return !!node && node.ownerDocument === doc && node.isConnected;
  }

  // 金额、朗读描述与代次的纯格式判断。

  function ariaParts(value, label, amount, singleNumber = false) {
    if (typeof value !== 'string' || hiddenText.test(value)) return null;
    if (singleNumber && (value.match(new RegExp(number, 'g')) || []).length !== 1) return null;
    // 仅替换紧接固定名称/单位/分隔符之后、与主金额完全同值的首个数字段。
    const prefix = new RegExp(
      '^' + label + '(?:[（(]元[）)])?[\\s:：，,]*[¥￥]?[\\s]*(' + number + ')(?![0-9.,])',
    );
    const match = prefix.exec(value);
    if (!match || normalizeAmount(match[1]) !== amount) return null;
    const start = match[0].length - match[1].length;
    return { before: value.slice(0, start), after: value.slice(match[0].length) };
  }
  function ariaAmount(value, binding) {
    if (typeof value !== 'string' || hiddenText.test(value)) return null;
    const match = new RegExp(
      '^' + binding.label + '(?:[（(]元[）)])?[\\s:：，,]*[¥￥]?[\\s]*(' + number + ')(?![0-9.,])',
    ).exec(value);
    return match ? normalizeAmount(match[1]) : null;
  }
  function normalizeAmount(value) {
    if (typeof value !== 'string') return null;
    const s = value.trim();
    if (!numeric.test(s)) return null;
    const parts = s.replace(/,/g, '').split('.');
    return parts[0].replace(/^0+(?=[0-9])/, '') + '.' + (parts[1] || '').padEnd(2, '0');
  }
  function isValidRevision(value) {
    return typeof value === 'string' && /^(?:0|[1-9][0-9]{0,18})$/.test(value);
  }
  function compareRevisions(a, b) {
    return a.length === b.length ? (a === b ? 0 : a > b ? 1 : -1) : a.length > b.length ? 1 : -1;
  }
  return dispatch;
})();
