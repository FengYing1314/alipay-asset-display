package io.github.fengying.alipaydisplay;

import android.graphics.Rect;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TabHost;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 只在进入、明确重绑或配置变化时发现节点；字段写入由同步 hook 通知。 */
final class NativeDisplaySession {
    private static final String TRACE_TAG = "AlipayDisplayDiag";
    private static final int MAX_TRACE_LINES = 96;
    private static final int MAX_HITS = 1_000_000;
    private static final int MAX_LAYOUT_DEFERRALS = 2;

    private static final int FIELD_TRACE_BUDGET = MAX_TRACE_LINES - NavigationTrace.values().length;

    private final View root;
    private final Supplier<DisplayConfig> config;
    private final Consumer<View> observeTarget;
    private final Consumer<Runnable> runSafely;
    private final Map<TextView, Binding> bindings = new IdentityHashMap<>();
    private final Map<View, Boolean> privacyScopes = new WeakHashMap<>();
    private final Map<View, Integer> bindingDepths = new IdentityHashMap<>();
    private final Map<View, ArrayDeque<BindingBatch>> bindingBatches = new IdentityHashMap<>();
    private final Set<View> releasingViews = Collections.newSetFromMap(new IdentityHashMap<>());
    private final NativeNavigationState navigation = new NativeNavigationState();
    private final EnumSet<NavigationTrace> navigationTraceStates =
            EnumSet.noneOf(NavigationTrace.class);
    private final EnumSet<TraceState> sessionTraceStates = EnumSet.noneOf(TraceState.class);
    private final EnumMap<DisplayConfig.Kind, EnumSet<TraceState>> fieldTraceStates =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final EnumMap<DisplayConfig.Kind, Integer> candidateCounts =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final EnumMap<DisplayConfig.Kind, DrawCounts> drawCounts =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final ViewTreeObserver.OnPreDrawListener frameSeal = this::sealBeforeDraw;
    private final Runnable refresh = this::refreshBindingsSafely;
    private final Runnable visibleRefresh = this::refreshVisibleBindingsSafely;
    private boolean queued;
    private boolean closed;
    private boolean reconciling;
    private boolean closing;
    private boolean visibleRefreshQueued;
    private boolean stableIdentityQueued;
    private boolean frameDiscoveryDirty = true;
    private boolean sealing;
    private int layoutDeferrals;
    private ViewTreeObserver sealObserver;
    private Runnable stableIdentityFrame;
    private Runnable stableIdentityCheck;
    private View wealthRootIdentity;
    private long generation;
    private int traceLines;
    private int wealthRootCount = -1;
    private int textHits;
    private int descriptionHits;
    private int bindingHits;
    private int selfTextHits;
    private int selfDescriptionHits;

    NativeDisplaySession(View root, Supplier<DisplayConfig> config) {
        this(root, config, ignored -> {});
    }

    NativeDisplaySession(View root, Supplier<DisplayConfig> config, Consumer<View> observeTarget) {
        this(root, config, observeTarget, null);
    }

    NativeDisplaySession(
            View root,
            Supplier<DisplayConfig> config,
            Consumer<View> observeTarget,
            Consumer<Runnable> runSafely) {
        this.root = root;
        this.config = config;
        this.observeTarget = observeTarget;
        this.runSafely = runSafely != null ? runSafely : this::runWithLocalFailureCleanup;
        sealObserver = root.getViewTreeObserver();
        sealObserver.addOnPreDrawListener(frameSeal);
        schedule();
    }

    /** 保留会话只接收自身视图；临时 detach 时仍识别既有字段和绑定宿主。 */
    boolean owns(View scope) {
        if (closed || scope == null) return false;
        if (isDescendantOrSelf(scope, root)) return true;
        for (Binding binding : bindings.values()) {
            if (!binding.released && (scope == binding.view || scope == binding.bindingOwner))
                return true;
        }
        return false;
    }

    /** 仅供绘制审计读取已有绑定归属和隐私事实，不发现节点或改变显示状态。 */
    NativeFrameAudit.FieldState frameAuditState(TextView target) {
        Binding binding = bindings.get(target);
        if (closed || binding == null || binding.released)
            return NativeFrameAudit.FieldState.UNKNOWN;
        return new NativeFrameAudit.FieldState(
                true,
                hasOwnedMarker(target.getText(), binding.textMarker),
                hasOwnedMarker(target.getContentDescription(), binding.descriptionMarker),
                binding.bindingHiddenKnown || binding.state.isDescriptionHidden(),
                binding.state.isPrivacyHidden() || binding.state.isDescriptionHidden());
    }

    /** Runtime 仅给精确金额 onDraw 加空 clip；未知字段及停用后的原绘制放行。 */
    boolean shouldSuppressDraw(TextView target) {
        if (closed || closing || target == null) return false;
        DisplayConfig snapshot = config.get();
        if (!snapshot.enabled) return false;
        Binding binding = bindings.get(target);
        if (binding == null || !binding.isStrictFrameTarget()) return false;
        DrawFacts facts = binding.collectDrawFacts(snapshot);
        if (snapshot.diagnostics)
            drawCounts.computeIfAbsent(binding.kind, ignored -> new DrawCounts()).record(facts);
        return !facts.isSafe();
    }

    void schedule() {
        frameDiscoveryDirty = true;
        if (!closed && !navigation.isSuspended() && !queued) {
            queued = true;
            trace(TraceState.SCHEDULED, null);
            root.post(refresh);
        }
    }

    /** 在宿主原 setText 返回后调用；只排除带预期归属标记的本次自写。 */
    void onHostTextChanged(TextView view) {
        if (closed || closing || navigation.isSuspended() || releasingViews.contains(view)) return;
        Binding binding = bindings.get(view);
        if (binding != null) {
            boolean expected = binding.isExpectedTextWrite();
            if (isTracingEnabled()) {
                textHits = incrementHitCount(textHits);
                if (expected) selfTextHits = incrementHitCount(selfTextHits);
            }
            if (!expected) binding.hostTextChanged();
            return;
        }
        String name = resourceName(view);
        if (isTracingEnabled()
                && (DisplayTargets.isTitleResource(name)
                        || DisplayTargets.isAmountResource(name))) {
            textHits = incrementHitCount(textHits);
            trace(TraceState.TEXT_HOOK_UNBOUND, null);
        }
        if (DisplayTargets.isTitleResource(name) && isDescendantOrSelf(view, root)) {
            onHostStructureChanged(view);
        } else if (DisplayTargets.isAmountResource(name) && identifyAmountKind(view) != null) {
            if (reconciling || !bindingDepths.isEmpty()) schedule();
            else reconcileBindings();
        }
    }

    /** 在宿主原 setContentDescription 返回后调用。 */
    void onHostDescriptionChanged(View view) {
        if (closed
                || closing
                || navigation.isSuspended()
                || releasingViews.contains(view)
                || !(view instanceof TextView text)) return;
        Binding binding = bindings.get(text);
        if (binding != null) {
            boolean expected = binding.isExpectedDescriptionWrite();
            if (isTracingEnabled()) {
                descriptionHits = incrementHitCount(descriptionHits);
                if (expected) selfDescriptionHits = incrementHitCount(selfDescriptionHits);
            }
            if (!expected) binding.hostDescriptionChanged();
        } else if (isTracingEnabled() && DisplayTargets.isAmountResource(resourceName(text))) {
            descriptionHits = incrementHitCount(descriptionHits);
            trace(TraceState.DESCRIPTION_HOOK_UNBOUND, null);
        }
    }

    /** 已知资产绑定方法进入时调用，覆盖宿主 post 延迟改金额的间隙。 */
    void onHostBindingStarted(View scope, boolean hidden) {
        if (!closed && scope != null) {
            bindingBatches
                    .computeIfAbsent(scope, ignored -> new ArrayDeque<>())
                    .addLast(new BindingBatch(hidden));
        }
        TraceState ignored =
                closed
                        ? TraceState.VISIBLE_BINDING_IGNORED_CLOSED
                        : closing
                                ? TraceState.VISIBLE_BINDING_IGNORED_CLOSING
                                : navigation.isSuspended()
                                        ? TraceState.VISIBLE_BINDING_IGNORED_SUSPENDED
                                        : scope == null
                                                ? TraceState.VISIBLE_BINDING_IGNORED_NULL
                                                : !(isRelevantScope(scope)
                                                                || isKnownBindingScope(scope)
                                                                || hidden
                                                                        && containsKnownBinding(
                                                                                scope)
                                                                || !hidden
                                                                        && canAwaitKnownAnchor(
                                                                                scope))
                                                        ? TraceState.VISIBLE_BINDING_IGNORED_SCOPE
                                                        : null;
        if (ignored != null) {
            if (!hidden) trace(ignored, null);
            return;
        }
        BindingBatch batch = bindingBatches.get(scope).getLast();
        batch.accepted = true;
        bindingDepths.put(scope, bindingDepths.getOrDefault(scope, 0) + 1);
        if (hidden || isRelevantScope(scope) || isKnownBindingScope(scope))
            privacyScopes.put(scope, hidden);
        if (isTracingEnabled()) bindingHits = incrementHitCount(bindingHits);
        trace(TraceState.BIND_START, null);
        for (Binding binding : new ArrayList<>(bindings.values())) {
            if (bindings.get(binding.view) == binding && isDescendantOrSelf(binding.view, scope)) {
                if (binding.bindingOwner != scope) {
                    if (!hidden) trace(TraceState.VISIBLE_BINDING_IGNORED_OWNER, binding);
                    continue;
                }
                if (!hidden && binding.assessAnchoredIdentity() != IdentityStatus.VALID) {
                    if (binding.canAwaitWealthAnchor(scope)) {
                        binding.visibleBindingPending = true;
                        batch.observations.add(new BindingObservation(binding, scope, true));
                        trace(TraceState.UNHIDE_WAIT_ANCHOR, binding);
                    } else trace(TraceState.VISIBLE_BINDING_IGNORED_ANCHOR, binding);
                    continue;
                }
                boolean wasHidden = binding.state.isPrivacyHidden();
                if (hidden || wasHidden) {
                    binding.privacyEpoch++;
                    binding.pendingVisibleObservation = null;
                }
                binding.visibleBindingPending = false;
                binding.bindingHiddenKnown = true;
                binding.bindingHidden = hidden;
                binding.state.hostPrivacy(hidden);
                if (!hidden) batch.observations.add(new BindingObservation(binding, scope, false));
                if (wasHidden && !hidden) trace(TraceState.UNHIDE_PENDING, binding);
                trace(hidden ? TraceState.BIND_HIDDEN : TraceState.BIND_VISIBLE, binding);
                if (hidden) {
                    binding.maskOwnedText();
                    binding.clearOwnedDescription();
                    trace(TraceState.BIND_INVALIDATED, binding);
                }
            }
        }
    }

    void onHostBindingFinished(View scope) {
        if (closed || scope == null) return;
        ArrayDeque<BindingBatch> stack = bindingBatches.get(scope);
        if (stack == null || stack.isEmpty()) return;
        BindingBatch batch = stack.removeLast();
        if (stack.isEmpty()) bindingBatches.remove(scope);
        if (!batch.accepted) return;
        Integer depth = bindingDepths.get(scope);
        if (depth == null) return;
        if (depth == 1) bindingDepths.remove(scope);
        else bindingDepths.put(scope, depth - 1);
        if (!batch.hidden && batch.pageGeneration == generation && !navigation.isSuspended()) {
            for (BindingObservation observation : batch.observations) observation.captureIfFresh();
        }
        if (bindingDepths.isEmpty()) schedule();
    }

    /** 仅在已知重绑、目标标题或可见性变化后调用，不监听全局布局。 */
    void onHostStructureChanged(View scope) {
        if (closed || closing) return;
        observeNavigation(scope);
        if (navigation.isSuspended() || !isRelevantScope(scope)) return;
        if (reconciling || !bindingDepths.isEmpty()) {
            schedule();
            return;
        }
        // 宿主可能在同一轮结构更新中先移除再挂回，先合并核验而不立即释放。
        schedule();
    }

    void close() {
        if (closed) return;
        closed = true;
        detachFrameSeal();
        generation++;
        root.removeCallbacks(refresh);
        root.removeCallbacks(visibleRefresh);
        cancelStableReconcile();
        queued = false;
        visibleRefreshQueued = false;
        trace(TraceState.CLOSED, null);
        traceDrawCounts();
        releaseBindings();
        bindingDepths.clear();
        bindingBatches.clear();
        privacyScopes.clear();
    }

    // 重绑与可见性刷新分开调度，折叠布局变化不能单独证明新宿主原文。
    private void refreshBindingsSafely() {
        trace(TraceState.REFRESH_RUN, null);
        runSafely.accept(this::reconcileBindings);
    }

    private void refreshVisibleBindingsSafely() {
        runSafely.accept(this::renderExistingBindings);
    }

    private void runWithLocalFailureCleanup(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            try {
                close();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private void reconcileBindings() {
        reconcileBindings(false);
    }

    private void reconcileBindings(boolean stableIdentity) {
        if (closed || closing) return;
        observeNavigation(null);
        if (navigation.isSuspended()) return;
        if (reconciling) {
            schedule();
            return;
        }
        root.removeCallbacks(refresh);
        queued = false;
        reconciling = true;
        try {
            reconcileBindingsOnce(stableIdentity);
        } catch (RuntimeException | LinkageError error) {
            trace(TraceState.RECONCILE_ERROR, null);
            throw error;
        } finally {
            reconciling = false;
            observeNavigation(null);
        }
    }

    private void reconcileBindingsOnce(boolean stableIdentity) {
        if (!bindingDepths.isEmpty()) return;
        frameDiscoveryDirty = false;
        DisplayConfig snapshot = config.get();
        if (!snapshot.enabled) {
            trace(TraceState.CONFIG_DISABLED, null);
            releaseBindings();
            return;
        }
        ArrayList<View> wealthRoots = findViewsByResourceName(root, DisplayTargets.WEALTH_ROOT);
        wealthRootCount = wealthRoots.size();
        wealthRootIdentity = wealthRootCount == 1 ? wealthRoots.get(0) : null;
        observeNavigation(null);
        candidateCounts.clear();
        Map<TextView, DisplayConfig.Kind> matches = new IdentityHashMap<>();
        if (wealthRoots.size() == 1) {
            EnumMap<DisplayConfig.Kind, ArrayList<TextView>> byKind =
                    new EnumMap<>(DisplayConfig.Kind.class);
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(wealthRoots.get(0));
            while (!pending.isEmpty()) {
                View view = pending.removeFirst();
                if (view instanceof TextView text
                        && DisplayTargets.isAmountResource(resourceName(text))) {
                    DisplayConfig.Kind kind = identifyAmountKind(text);
                    if (kind != null)
                        byKind.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(text);
                }
                enqueueChildren(view, pending);
            }
            for (Map.Entry<DisplayConfig.Kind, ArrayList<TextView>> entry : byKind.entrySet()) {
                candidateCounts.put(entry.getKey(), entry.getValue().size());
                // 相同语义出现多次时不猜测其中哪一项是主金额。
                if (entry.getValue().size() == 1)
                    matches.put(entry.getValue().get(0), entry.getKey());
            }
        }
        trace(
                wealthRootCount == 0
                        ? TraceState.ROOT_NONE
                        : wealthRootCount == 1 ? TraceState.ROOT_ONE : TraceState.ROOT_MULTIPLE,
                null);
        for (DisplayConfig.Kind kind :
                new DisplayConfig.Kind[] {
                    DisplayConfig.Kind.TOTAL, DisplayConfig.Kind.YUEBAO, DisplayConfig.Kind.FUND
                }) {
            int count = candidateCounts.getOrDefault(kind, 0);
            trace(
                    count == 0
                            ? TraceState.CANDIDATE_NONE
                            : count == 1 ? TraceState.CANDIDATE_ONE : TraceState.CANDIDATE_MULTIPLE,
                    kind,
                    null);
        }
        for (TextView text : new ArrayList<>(bindings.keySet())) {
            Binding binding = bindings.get(text);
            if (binding != null
                    && (matches.get(text) != binding.kind || !binding.matchesIdentity())) {
                IdentityStatus status = binding.assessIdentity();
                if (status == IdentityStatus.VALID) {
                    status =
                            wealthRootCount == 0
                                    ? IdentityStatus.ROOT_MISSING
                                    : wealthRootCount > 1
                                            ? IdentityStatus.ROOT_DUPLICATE
                                            : candidateCounts.getOrDefault(binding.kind, 0) > 1
                                                    ? IdentityStatus.SEMANTIC_DUPLICATE
                                                    : IdentityStatus.SEMANTIC_MISSING;
                }
                traceIdentity(status, binding, stableIdentity);
                if (stableIdentity) {
                    bindings.remove(text);
                    binding.close(false);
                } else {
                    binding.identityPending = true;
                    scheduleStableReconcile();
                }
            } else if (binding != null) {
                binding.identityPending = false;
            }
        }
        for (Map.Entry<TextView, DisplayConfig.Kind> entry : matches.entrySet()) {
            if (closed || closing) return;
            observeTarget.accept(entry.getKey());
            Binding binding = bindings.get(entry.getKey());
            if (binding == null) {
                binding = new Binding(entry.getKey(), entry.getValue());
                bindings.put(entry.getKey(), binding);
            }
            binding.scheduleGeometryTrace();
            binding.render(snapshot);
        }
        scheduleVisibleRender();
    }

    /** 恢复前先解除所有绑定，宿主同步监听器不能再改动本轮集合。 */
    private void releaseBindings() {
        ArrayList<Binding> releasing = new ArrayList<>(bindings.values());
        bindings.clear();
        closing = true;
        try {
            for (Binding binding : releasing) {
                binding.close(binding.matchesIdentity());
            }
        } finally {
            closing = false;
        }
    }

    /** 仅已知金额容器/折叠容器的布局变化触发，不扫描视图树。 */
    private void scheduleVisibleRender() {
        if (closed
                || closing
                || navigation.isSuspended()
                || visibleRefreshQueued
                || bindings.isEmpty()) return;
        visibleRefreshQueued = true;
        root.post(visibleRefresh);
    }

    private void renderExistingBindings() {
        visibleRefreshQueued = false;
        if (closed || closing) return;
        observeNavigation(null);
        if (navigation.isSuspended()) return;
        if (queued || reconciling || !bindingDepths.isEmpty()) {
            schedule();
            return;
        }
        reconciling = true;
        try {
            DisplayConfig snapshot = config.get();
            if (!snapshot.enabled) {
                releaseBindings();
                return;
            }
            for (Binding binding : new ArrayList<>(bindings.values())) {
                if (bindings.get(binding.view) != binding) continue;
                if (!binding.matchesIdentity()) {
                    traceIdentity(binding.assessIdentity(), binding, false);
                    binding.identityPending = true;
                    scheduleStableReconcile();
                } else binding.render(snapshot);
            }
        } finally {
            reconciling = false;
            observeNavigation(null);
        }
    }

    /** 一次帧后稳定核验，期间保留新宿主原文；真实隐藏仍由同步信号清源。 */
    private void scheduleStableReconcile() {
        if (closed || navigation.isSuspended() || stableIdentityQueued) return;
        stableIdentityQueued = true;
        long expectedGeneration = generation;
        Runnable check =
                () ->
                        runSafely.accept(
                                () -> {
                                    if (closed
                                            || navigation.isSuspended()
                                            || generation != expectedGeneration) return;
                                    stableIdentityQueued = false;
                                    stableIdentityCheck = null;
                                    if (reconciling
                                            || !bindingDepths.isEmpty()
                                            || anyFieldWriting()) {
                                        scheduleStableReconcile();
                                        return;
                                    }
                                    reconcileBindings(true);
                                });
        stableIdentityCheck = check;
        stableIdentityFrame =
                () ->
                        runSafely.accept(
                                () -> {
                                    if (closed
                                            || navigation.isSuspended()
                                            || generation != expectedGeneration) return;
                                    stableIdentityFrame = null;
                                    root.post(check);
                                });
        root.postOnAnimation(stableIdentityFrame);
    }

    private void cancelStableReconcile() {
        if (stableIdentityFrame != null) root.removeCallbacks(stableIdentityFrame);
        if (stableIdentityCheck != null) root.removeCallbacks(stableIdentityCheck);
        stableIdentityQueued = false;
        stableIdentityFrame = null;
        stableIdentityCheck = null;
    }

    // 布局重排次数有界；当前绑定的字段事实决定是否放行这一帧。
    private boolean sealBeforeDraw() {
        if (closed || closing || sealing) return true;
        boolean[] allowDraw = {true};
        runSafely.accept(
                () -> {
                    if (closed || closing || sealing) return;
                    sealing = true;
                    try {
                        observeNavigation(null);
                        if (navigation.isSuspended()) return;
                        boolean identityPending = false;
                        for (Binding binding : bindings.values())
                            identityPending |= binding.identityPending;
                        if (frameDiscoveryDirty || queued || identityPending) reconcileBindings();
                        else {
                            root.removeCallbacks(visibleRefresh);
                            renderExistingBindings();
                        }
                        DisplayConfig snapshot = config.get();
                        if (!snapshot.enabled || closed || closing || navigation.isSuspended()) {
                            layoutDeferrals = 0;
                            return;
                        }
                        boolean layoutUnsafe = false;
                        for (Binding binding : new ArrayList<>(bindings.values())) {
                            if (closed
                                    || bindings.get(binding.view) != binding
                                    || !binding.isStrictFrameTarget()
                                    || !isVisuallyEligible(binding.view)) continue;
                            binding.sealFields(snapshot);
                            if (!binding.isStrictFrameTarget() || !isVisuallyEligible(binding.view))
                                continue;
                            DrawFacts facts = binding.collectDrawFacts(snapshot);
                            if (!facts.layoutSafe) {
                                layoutUnsafe = true;
                                binding.view.requestLayout();
                                trace(
                                        layoutDeferrals < MAX_LAYOUT_DEFERRALS
                                                ? TraceState.SEAL_LAYOUT_DEFERRED
                                                : TraceState.SEAL_LAYOUT_FENCED,
                                        binding);
                            }
                        }
                        if (PreDrawPolicy.deferLayout(layoutUnsafe, layoutDeferrals)) {
                            layoutDeferrals++;
                            allowDraw[0] = false;
                        } else if (!layoutUnsafe) layoutDeferrals = 0;
                    } finally {
                        sealing = false;
                    }
                });
        return allowDraw[0];
    }

    private void detachFrameSeal() {
        try {
            if (sealObserver != null && sealObserver.isAlive())
                sealObserver.removeOnPreDrawListener(frameSeal);
            ViewTreeObserver current = root.getViewTreeObserver();
            if (current != sealObserver && current.isAlive())
                current.removeOnPreDrawListener(frameSeal);
        } catch (RuntimeException | LinkageError ignored) {
            // closed 也会使尚未移除的旧监听器失效，继续执行安全释放。
        } finally {
            sealObserver = null;
        }
    }

    private boolean isRelevantScope(View scope) {
        if (scope == null || !isDescendantOrSelf(scope, root)) return false;
        if (findNamedAncestor(scope, DisplayTargets.WEALTH_ROOT) != null) return true;
        if (wealthRootIdentity != null && isDescendantOrSelf(wealthRootIdentity, scope))
            return true;
        for (Binding binding : bindings.values()) {
            if (isDescendantOrSelf(binding.view, scope)) return true;
        }
        return false;
    }

    private boolean isKnownBindingScope(View scope) {
        if (scope == null) return false;
        for (Binding binding : bindings.values()) {
            if (binding.bindingOwner == scope
                    && isDescendantOrSelf(binding.view, scope)
                    && binding.assessAnchoredIdentity() == IdentityStatus.VALID) return true;
        }
        return false;
    }

    private boolean containsKnownBinding(View scope) {
        if (scope == null) return false;
        for (Binding binding : bindings.values()) {
            if (binding.bindingOwner == scope && isDescendantOrSelf(binding.view, scope))
                return true;
        }
        return false;
    }

    private boolean canAwaitKnownAnchor(View scope) {
        if (scope == null) return false;
        for (Binding binding : bindings.values())
            if (binding.canAwaitWealthAnchor(scope)) return true;
        return false;
    }

    /** 只沿已知页面祖先比较当前内容，不遍历 decor 或读取 tab 标签。 */
    private void observeNavigation(View changedScope) {
        if (closed || closing) return;
        View page = wealthRootIdentity;
        if (navigation.isSuspended()
                && changedScope != null
                && isDescendantOrSelf(changedScope, root)) {
            View replacement = findNamedAncestor(changedScope, DisplayTargets.WEALTH_ROOT);
            if (replacement != null) page = replacement;
        }
        TabHost host = null;
        boolean known = false;
        boolean currentWealth = false;
        boolean wealthShown = false;
        try {
            View current = page;
            while (current != null) {
                if (current instanceof TabHost found) {
                    host = found;
                    break;
                }
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            View selected = host == null ? null : host.getCurrentView();
            known = selected != null && isDescendantOrSelf(selected, host);
            currentWealth = known && page != null && isDescendantOrSelf(page, selected);
            wealthShown = page != null && page.isShown();
        } catch (RuntimeException | LinkageError ignored) {
            known = false;
            currentWealth = false;
        }
        boolean batchActive = !bindingDepths.isEmpty();
        boolean busy = batchActive || reconciling || anyFieldWriting();
        NativeNavigationState.Outcome outcome =
                navigation.observe(host, page, known, currentWealth, wealthShown, busy);
        traceNavigation(outcome, host, page, known, currentWealth, wealthShown, batchActive, busy);
        if (outcome == NativeNavigationState.Outcome.LEFT) {
            suspendPage();
        } else if (outcome == NativeNavigationState.Outcome.ENTERED) {
            generation++;
            wealthRootIdentity = page;
            schedule();
        }
    }

    private boolean anyFieldWriting() {
        for (Binding binding : bindings.values()) if (binding.writing) return true;
        return false;
    }

    private void suspendPage() {
        generation++;
        root.removeCallbacks(refresh);
        root.removeCallbacks(visibleRefresh);
        cancelStableReconcile();
        queued = false;
        visibleRefreshQueued = false;
        // 只恢复本页仍可信且由模块拥有的原文；真实隐私隐藏已使其不可信。
        releaseBindings();
        bindingDepths.clear();
        bindingBatches.clear();
        privacyScopes.clear();
    }

    private boolean isPrivacyHidden(View view) {
        return Boolean.TRUE.equals(findPrivacyFlag(view));
    }

    private Boolean findPrivacyFlag(View view) {
        View owner = findBindingOwner(view);
        return owner == null ? null : privacyScopes.get(owner);
    }

    private static View findBindingOwner(View view) {
        View current = view;
        while (current != null) {
            Class<?> type = current.getClass();
            while (type != null && View.class.isAssignableFrom(type)) {
                if (RuntimePolicy.isBindingClass(type.getName())) return current;
                type = type.getSuperclass();
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    // 跟踪按状态去重并限制预算，只输出固定分类、布尔事实和计数。
    private boolean isTracingEnabled() {
        try {
            return config.get().diagnostics;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void traceNavigation(
            NativeNavigationState.Outcome outcome,
            TabHost host,
            View page,
            boolean known,
            boolean currentWealth,
            boolean wealthShown,
            boolean batchActive,
            boolean busy) {
        if (!isTracingEnabled() || traceLines >= MAX_TRACE_LINES) return;
        try {
            boolean trusted = navigation.trusts(host, page);
            NavigationTrace reason =
                    switch (outcome) {
                        case CURRENT -> NavigationTrace.CURRENT;
                        case DEFERRED -> NavigationTrace.DEFERRED;
                        case LEFT -> NavigationTrace.LEFT;
                        case AWAY -> NavigationTrace.AWAY;
                        case ENTERED -> NavigationTrace.ENTERED;
                        case UNKNOWN ->
                                !known
                                        ? NavigationTrace.UNKNOWN_UNAVAILABLE
                                        : !trusted
                                                ? NavigationTrace.UNKNOWN_UNTRUSTED
                                                : currentWealth && !wealthShown
                                                        ? NavigationTrace.UNKNOWN_CURRENT_HIDDEN
                                                        : NavigationTrace.UNKNOWN_INCONSISTENT;
                    };
            if (!navigationTraceStates.add(reason)) return;
            traceLines++;
            boolean confirmed =
                    outcome == NativeNavigationState.Outcome.LEFT
                            || outcome == NativeNavigationState.Outcome.ENTERED;
            Log.i(
                    TRACE_TAG,
                    "native "
                            + (outcome == NativeNavigationState.Outcome.UNKNOWN
                                    ? "navigation-unknown"
                                    : "navigation")
                            + " reason="
                            + reason.name()
                            + " known="
                            + known
                            + " currentWealth="
                            + currentWealth
                            + " wealthShown="
                            + wealthShown
                            + " batchActive="
                            + batchActive
                            + " busy="
                            + busy
                            + " trusted="
                            + trusted
                            + " confirmed="
                            + confirmed
                            + " suspended="
                            + navigation.isSuspended()
                            + " generationChanged="
                            + confirmed);
        } catch (RuntimeException | LinkageError ignored) {
            // 导航诊断不输出内部异常，也不影响宿主。
        }
    }

    private void trace(TraceState reason, Binding binding) {
        trace(reason, binding == null ? null : binding.kind, binding);
    }

    private void trace(TraceState reason, DisplayConfig.Kind kind, Binding binding) {
        trace(
                reason,
                kind,
                binding,
                binding == null ? IdentityStatus.UNASSESSED : binding.assessIdentity());
    }

    /** 仅记录固定枚举、结构计数和布尔值；不接收业务文本或任意名称。 */
    private void trace(
            TraceState reason, DisplayConfig.Kind kind, Binding binding, IdentityStatus identity) {
        if (!isTracingEnabled() || traceLines >= FIELD_TRACE_BUDGET) return;
        try {
            EnumSet<TraceState> seen =
                    kind == null
                            ? sessionTraceStates
                            : fieldTraceStates.computeIfAbsent(
                                    kind, ignored -> EnumSet.noneOf(TraceState.class));
            if (!seen.add(reason)) return;
            traceLines++;
            boolean textMarker =
                    binding != null && hasOwnedMarker(binding.view.getText(), binding.textMarker);
            boolean descriptionMarker =
                    binding != null
                            && hasOwnedMarker(
                                    binding.view.getContentDescription(),
                                    binding.descriptionMarker);
            TraceText textState =
                    binding == null
                            ? TraceText.NONE
                            : classifyTraceText(binding.view.getText(), textMarker);
            boolean sizePositive =
                    binding != null && binding.view.getWidth() > 0 && binding.view.getHeight() > 0;
            boolean globalVisibleRectNonempty =
                    binding != null && binding.view.getGlobalVisibleRect(new Rect());
            boolean selfAlphaPositive = binding != null && binding.view.getAlpha() > 0f;
            boolean ancestorsAlphaPositive =
                    binding != null && hasPositiveAncestorAlpha(binding.view);
            Boolean scopePrivacy = binding == null ? null : findPrivacyFlag(binding.view);
            Log.i(
                    TRACE_TAG,
                    "native state="
                            + reason.name()
                            + " kind="
                            + (kind == null ? "SESSION" : kind.name())
                            + " attached="
                            + root.isAttachedToWindow()
                            + " shown="
                            + root.isShown()
                            + " fieldShown="
                            + (binding != null && binding.view.isShown())
                            + " wealthRoots="
                            + wealthRootCount
                            + " totalCandidates="
                            + candidateCounts.getOrDefault(DisplayConfig.Kind.TOTAL, 0)
                            + " yuebaoCandidates="
                            + candidateCounts.getOrDefault(DisplayConfig.Kind.YUEBAO, 0)
                            + " fundCandidates="
                            + candidateCounts.getOrDefault(DisplayConfig.Kind.FUND, 0)
                            + " canRender="
                            + (binding != null && binding.state.canRender())
                            + " sameValueEchoBlocked="
                            + (binding != null && binding.lastOutput.isBlocked())
                            + " privacyHidden="
                            + (binding != null && binding.state.isPrivacyHidden())
                            + " descriptionBlocksDisplay="
                            + (binding != null && binding.state.isDescriptionHidden())
                            + " bindingHiddenKnown="
                            + (binding != null && binding.bindingHiddenKnown)
                            + " bindingHidden="
                            + (binding != null && binding.bindingHidden)
                            + " visibleBindingPending="
                            + (binding != null && binding.visibleBindingPending)
                            + " scopePrivacyKnown="
                            + (scopePrivacy != null)
                            + " scopePrivacyHidden="
                            + Boolean.TRUE.equals(scopePrivacy)
                            + " hiddenDescription="
                            + (binding != null
                                    && DisplayTargets.isHiddenDescription(
                                            binding.view.getContentDescription()))
                            + " textState="
                            + textState.name()
                            + " identityStatus="
                            + identity.name()
                            + " textMarker="
                            + textMarker
                            + " descriptionMarker="
                            + descriptionMarker
                            + " sizePositive="
                            + sizePositive
                            + " globalVisibleRectNonempty="
                            + globalVisibleRectNonempty
                            + " selfAlphaPositive="
                            + selfAlphaPositive
                            + " ancestorsAlphaPositive="
                            + ancestorsAlphaPositive
                            + " textHits="
                            + textHits
                            + " hostTextHits="
                            + (textHits - selfTextHits)
                            + " descriptionHits="
                            + descriptionHits
                            + " hostDescriptionHits="
                            + (descriptionHits - selfDescriptionHits)
                            + " bindingHits="
                            + bindingHits);
        } catch (RuntimeException | LinkageError ignored) {
            // 诊断失败不影响展示会话，也不输出原始异常中的宿主数据。
        }
    }

    private void traceIdentity(IdentityStatus status, Binding binding, boolean stable) {
        trace(
                stable ? TraceState.IDENTITY_STABLE_REJECTED : TraceState.IDENTITY_CHANGED,
                binding.kind,
                binding,
                status);
    }

    private void traceDrawCounts() {
        if (!isTracingEnabled()) return;
        for (DisplayConfig.Kind kind :
                new DisplayConfig.Kind[] {
                    DisplayConfig.Kind.TOTAL, DisplayConfig.Kind.YUEBAO, DisplayConfig.Kind.FUND
                }) {
            DrawCounts value = drawCounts.get(kind);
            if (value == null) value = new DrawCounts();
            Log.i(
                    TRACE_TAG,
                    "native-draw kind="
                            + kind
                            + " checked="
                            + value.checked
                            + " hasSamples="
                            + (value.checked > 0)
                            + " capped="
                            + (value.checked >= MAX_HITS)
                            + " safe="
                            + value.safe
                            + " suppressed="
                            + value.suppressed
                            + " unexpectedAllowed=0"
                            + " layoutMissing="
                            + value.layoutMissing
                            + " textUnsafe="
                            + value.textUnsafe
                            + " layoutUnsafe="
                            + value.layoutUnsafe
                            + " descriptionUnsafe="
                            + value.descriptionUnsafe
                            + " textConfig="
                            + value.textConfig
                            + " textMasked="
                            + value.textMasked
                            + " layoutConfig="
                            + value.layoutConfig
                            + " layoutMasked="
                            + value.layoutMasked
                            + " descriptionConfig="
                            + value.descriptionConfig
                            + " descriptionMasked="
                            + value.descriptionMasked);
        }
    }

    private static TraceText classifyTraceText(CharSequence value, boolean moduleMarker) {
        String text = value == null ? "" : value.toString();
        boolean masked = text.matches("[*＊●•·]{2,}");
        if (moduleMarker) return masked ? TraceText.MODULE_MASK : TraceText.MODULE_TEXT;
        if (DisplayTargets.isVisibleAmount(text)) return TraceText.AMOUNT;
        if (masked) return TraceText.MASK;
        if (text.isEmpty() || "--".equals(text)) return TraceText.PLACEHOLDER;
        return TraceText.OTHER;
    }

    private static int incrementHitCount(int count) {
        return Math.min(count + 1, MAX_HITS);
    }

    private DisplayConfig.Kind identifyAmountKind(TextView view) {
        String id = resourceName(view);
        if (!DisplayTargets.isAmountResource(id) || !isDescendantOrSelf(view, root)) return null;
        if (findNamedAncestor(view, DisplayTargets.WEALTH_ROOT) == null) return null;
        boolean card = DisplayTargets.CARD_AMOUNT.equals(id);
        View container = findAmountContainer(view);
        if (container == null) return null;
        ArrayList<View> labels =
                findViewsByResourceName(
                        container, card ? DisplayTargets.CARD_TITLE : DisplayTargets.TOTAL_TITLE);
        ArrayList<View> amounts = findViewsByResourceName(container, id);
        if (labels.size() != 1 || amounts.size() != 1 || !(labels.get(0) instanceof TextView label))
            return null;
        return DisplayTargets.wealthKind(id, label.getText().toString());
    }

    private static View findAmountContainer(TextView view) {
        return findNamedAncestor(
                view,
                DisplayTargets.WEALTH
                        + (DisplayTargets.CARD_AMOUNT.equals(resourceName(view))
                                ? "asset_horizon_title_container"
                                : "fh_assets_amount_layout"));
    }

    private static boolean isVisuallyEligible(View view) {
        return view.isShown()
                && view.getWidth() > 0
                && view.getHeight() > 0
                && view.getAlpha() > 0f
                && hasPositiveAncestorAlpha(view)
                && view.getGlobalVisibleRect(new Rect());
    }

    private static boolean hasPositiveAncestorAlpha(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View ancestor) {
            if (!(ancestor.getAlpha() > 0f)) return false;
            parent = ancestor.getParent();
        }
        return true;
    }

    static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try {
            return view.getResources().getResourceName(view.getId());
        } catch (android.content.res.Resources.NotFoundException ignored) {
            return "";
        }
    }

    private static View findNamedAncestor(View view, String name) {
        View current = view;
        while (current != null) {
            if (name.equals(resourceName(current))) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean isDescendantOrSelf(View view, View parent) {
        View current = view;
        while (current != null) {
            if (current == parent) return true;
            ViewParent next = current.getParent();
            current = next instanceof View ? (View) next : null;
        }
        return false;
    }

    private static ArrayList<View> findViewsByResourceName(View start, String name) {
        ArrayList<View> result = new ArrayList<>();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (name.equals(resourceName(view))) result.add(view);
            enqueueChildren(view, pending);
        }
        return result;
    }

    private static void enqueueChildren(View view, ArrayDeque<View> pending) {
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
        }
    }

    private static CharSequence copyDisplayText(CharSequence value) {
        return value == null ? null : new SpannedString(value);
    }

    private static boolean hasOwnedMarker(CharSequence value, Object marker) {
        return value instanceof Spanned spanned && spanned.getSpanStart(marker) >= 0;
    }

    private static boolean containsDigits(CharSequence value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++)
            if (Character.isDigit(value.charAt(i))) return true;
        return false;
    }

    private static boolean isMaskedValue(CharSequence value) {
        if (value == null || value.length() < 2) return false;
        for (int i = 0; i < value.length(); i++)
            if ("*＊●•·".indexOf(value.charAt(i)) < 0) return false;
        return true;
    }

    /** 单个目标的来源、模块产物与释放状态，不在绑定之间共享金额原文。 */
    private final class Binding {
        final TextView view;
        final DisplayConfig.Kind kind;
        final View wealthIdentity;
        final View containerIdentity;
        final View bindingOwner;
        final View layoutScope;
        final View.OnLayoutChangeListener layoutListener;
        final Object textMarker = new Object();
        final Object descriptionMarker = new Object();
        final DisplayValueState<CharSequence> state;
        final LastOutputLease lastOutput = new LastOutputLease();
        boolean writing;
        boolean sealedMask;
        boolean writingText;
        boolean writingDescription;
        CharSequence expectedText;
        CharSequence expectedDescription;
        boolean released;
        boolean identityPending;
        boolean bindingHiddenKnown;
        boolean bindingHidden;
        boolean visibleBindingPending;
        BindingObservation pendingVisibleObservation;
        long privacyEpoch;
        long hostTextRevision;
        long hostDescriptionRevision;
        boolean geometryTraceScheduled;
        Runnable geometryFrame;
        Runnable geometrySample;

        Binding(TextView view, DisplayConfig.Kind kind) {
            this.view = view;
            this.kind = kind;
            wealthIdentity = findNamedAncestor(view, DisplayTargets.WEALTH_ROOT);
            containerIdentity = findAmountContainer(view);
            bindingOwner = findBindingOwner(view);
            View fold = findNamedAncestor(view, DisplayTargets.WEALTH + "asset_fold");
            layoutScope = fold != null ? fold : containerIdentity;
            layoutListener =
                    (changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        if (left == oldLeft
                                && top == oldTop
                                && right == oldRight
                                && bottom == oldBottom) return;
                        runSafely.accept(
                                () -> {
                                    if (!closed && !released && bindings.get(view) == this)
                                        scheduleVisibleRender();
                                });
                    };
            state =
                    new DisplayValueState<>(
                            copyDisplayText(view.getText()),
                            copyDisplayText(view.getContentDescription()),
                            DisplayTargets.isVisibleAmount(view.getText().toString()),
                            DisplayTargets.isHiddenDescription(view.getContentDescription()));
            state.hostPrivacy(isPrivacyHidden(view));
            if (layoutScope != null) layoutScope.addOnLayoutChangeListener(layoutListener);
            trace(TraceState.BOUND, this);
        }

        void hostTextChanged() {
            if (released) return;
            sealedMask = false;
            hostTextRevision++;
            CharSequence value = view.getText();
            // 宿主拷贝回模块产物时不能把它当成真实原文。
            boolean derivedFromModule = hasOwnedMarker(value, textMarker);
            boolean pendingModuleEcho =
                    writingText
                            && expectedText != null
                            && expectedText.toString().contentEquals(value);
            boolean delayedEcho = !derivedFromModule && lastOutput.rejectsUnowned(value.toString());
            boolean visible =
                    DisplayTargets.isHostAmount(
                            value.toString(), derivedFromModule, pendingModuleEcho || delayedEcho);
            state.hostText(visible ? copyDisplayText(value) : null, visible);
            if (visible && state.canRender()) lastOutput.acceptHost(value.toString(), false);
            if (delayedEcho) trace(TraceState.AMBIGUOUS_TEXT_ECHO, this);
            if (!visible) pendingVisibleObservation = null;
            trace(
                    derivedFromModule
                            ? TraceState.HOST_TEXT_MODULE_COPY
                            : visible
                                    ? TraceState.HOST_TEXT_AMOUNT
                                    : classifyTraceText(value, false) == TraceText.MASK
                                            ? TraceState.HOST_TEXT_MASK
                                            : TraceState.HOST_TEXT_OTHER,
                    this);
            if (derivedFromModule) maskOwnedText();
            clearOwnedDescription();
            if (reconciling || writing || !bindingDepths.isEmpty()) {
                schedule();
                return;
            }
            if (matchesIdentity()) render(config.get());
            else {
                identityPending = true;
                traceIdentity(assessIdentity(), this, false);
                scheduleStableReconcile();
            }
        }

        void hostDescriptionChanged() {
            if (released) return;
            hostDescriptionRevision++;
            CharSequence value = view.getContentDescription();
            if (hasOwnedMarker(value, descriptionMarker)) {
                privacyEpoch++;
                pendingVisibleObservation = null;
                state.invalidate();
                trace(TraceState.HOST_DESCRIPTION_MODULE_COPY, this);
                maskOwnedText();
                clearOwnedDescription();
                return;
            }
            boolean hidden = DisplayTargets.isHiddenDescription(value);
            if (hidden) {
                privacyEpoch++;
                pendingVisibleObservation = null;
            }
            state.hostDescription(copyDisplayText(value), hidden);
            trace(
                    hidden
                            ? TraceState.HOST_DESCRIPTION_HIDDEN
                            : TraceState.HOST_DESCRIPTION_VISIBLE,
                    this);
            if (hidden) maskOwnedText();
            else if (reconciling || writing || !bindingDepths.isEmpty()) schedule();
            else if (matchesIdentity()) render(config.get());
            else {
                identityPending = true;
                traceIdentity(assessIdentity(), this, false);
                scheduleStableReconcile();
            }
        }

        void invalidate() {
            if (released) return;
            state.invalidate();
            trace(TraceState.STATE_INVALIDATED, this);
            maskOwnedText();
            clearOwnedDescription();
        }

        void render(DisplayConfig snapshot) {
            if (released || bindings.get(view) != this) return;
            if (!snapshot.enabled) {
                bindings.remove(view);
                close(matchesIdentity());
                return;
            }
            if (pendingVisibleObservation != null) pendingVisibleObservation.acceptPendingIfValid();
            if (!refreshHostFieldSource()) return;
            if (!state.canRender()) {
                trace(TraceState.WAIT_HOST_AMOUNT, this);
                return;
            }
            if (!matchesIdentity()) {
                identityPending = true;
                traceIdentity(assessIdentity(), this, false);
                scheduleStableReconcile();
                return;
            }
            if (identityPending) {
                scheduleStableReconcile();
                return;
            }
            if (!bindingDepths.isEmpty()) {
                trace(TraceState.WAIT_BINDING, this);
                return;
            }
            if (!isVisuallyEligible(view)) {
                trace(TraceState.NOT_VISIBLE, this);
                return;
            }
            String next = snapshot.formatted(kind);
            writing = true;
            try {
                if (!hasOwnedMarker(view.getText(), textMarker)
                        || !next.contentEquals(view.getText())) {
                    SpannableString decorated = new SpannableString(next);
                    decorated.setSpan(
                            textMarker, 0, next.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    trace(TraceState.TEXT_WRITE, this);
                    writeText(decorated);
                }
                // 同步宿主 TextWatcher 仍可改写本控件；朗读必须配合当前文本。
                if (!hasOwnedMarker(view.getText(), textMarker)
                        || !next.contentEquals(view.getText())) {
                    trace(
                            hasOwnedMarker(view.getText(), textMarker)
                                    ? TraceState.TEXT_CONTENT_CHANGED
                                    : TraceState.TEXT_MARKER_LOST,
                            this);
                    CharSequence current = view.getText();
                    boolean visible =
                            DisplayTargets.isHostAmount(
                                    current.toString(),
                                    hasOwnedMarker(current, textMarker),
                                    next.contentEquals(current));
                    state.hostText(visible ? copyDisplayText(current) : null, visible);
                    clearOwnedDescription();
                    return;
                }
                lastOutput.confirmOwned(next);
                sealedMask = false;
                trace(TraceState.TEXT_MARKER_HELD, this);
                if (!hasOwnedMarker(view.getContentDescription(), descriptionMarker)
                        && DisplayTargets.isHiddenDescription(view.getContentDescription())) {
                    state.hostDescription(copyDisplayText(view.getContentDescription()), true);
                    trace(TraceState.HOST_DESCRIPTION_HIDDEN, this);
                    maskOwnedText();
                    clearOwnedDescription();
                    return;
                }
                if (released || !state.canRender()) {
                    state.invalidate();
                    trace(TraceState.STATE_INVALIDATED, this);
                    maskOwnedText();
                    clearOwnedDescription();
                    return;
                }
                if (!matchesIdentity()) {
                    identityPending = true;
                    traceIdentity(assessIdentity(), this, false);
                    scheduleStableReconcile();
                    return;
                }
                String spoken = next;
                if (!hasOwnedMarker(view.getContentDescription(), descriptionMarker)
                        || !spoken.contentEquals(view.getContentDescription())) {
                    SpannableString description = new SpannableString(spoken);
                    description.setSpan(
                            descriptionMarker,
                            0,
                            spoken.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    trace(TraceState.DESCRIPTION_WRITE, this);
                    writeDescription(description);
                }
                // 描述写入也可能同步触发宿主改写文本，必须再次核验两个字段。
                if (!hasOwnedMarker(view.getText(), textMarker)
                        || !next.contentEquals(view.getText())) {
                    trace(
                            hasOwnedMarker(view.getText(), textMarker)
                                    ? TraceState.TEXT_CONTENT_CHANGED
                                    : TraceState.TEXT_MARKER_LOST,
                            this);
                    CharSequence current = view.getText();
                    boolean visible =
                            DisplayTargets.isHostAmount(
                                    current.toString(),
                                    hasOwnedMarker(current, textMarker),
                                    next.contentEquals(current));
                    state.hostText(visible ? copyDisplayText(current) : null, visible);
                    clearOwnedDescription();
                    return;
                }
                CharSequence currentDescription = view.getContentDescription();
                trace(
                        hasOwnedMarker(currentDescription, descriptionMarker)
                                ? TraceState.DESCRIPTION_MARKER_HELD
                                : TraceState.DESCRIPTION_MARKER_LOST,
                        this);
                if (!hasOwnedMarker(currentDescription, descriptionMarker)) {
                    boolean hidden = DisplayTargets.isHiddenDescription(currentDescription);
                    state.hostDescription(copyDisplayText(currentDescription), hidden);
                    if (hidden) maskOwnedText();
                }
                if (!state.canRender()) {
                    maskOwnedText();
                    clearOwnedDescription();
                } else if (hasOwnedMarker(view.getText(), textMarker)
                        && hasOwnedMarker(view.getContentDescription(), descriptionMarker)
                        && next.contentEquals(view.getText())
                        && spoken.contentEquals(view.getContentDescription())) {
                    trace(TraceState.RENDERED, this);
                }
            } finally {
                writing = false;
                if (!reconciling && !closed) observeNavigation(null);
            }
        }

        /** 仅遮罩已确认字段中的不可信数字；原文只沿原有 setter/绑定凭据更新。 */
        void sealFields(DisplayConfig snapshot) {
            DrawFacts facts = collectDrawFacts(snapshot);
            boolean wasWriting = writing;
            writing = true;
            try {
                if (!facts.textSafe) {
                    sealedMask = state.canRender();
                    SpannableString masked = new SpannableString("****");
                    masked.setSpan(
                            textMarker, 0, masked.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    writeText(masked);
                    trace(TraceState.SEAL_MASKED, this);
                }
                // 文本 setter 可重入写描述/切隐藏，必须重新读取而不复用旧事实。
                if (!collectDrawFacts(snapshot).descriptionSafe) {
                    SpannableString masked = new SpannableString("****");
                    masked.setSpan(
                            descriptionMarker,
                            0,
                            masked.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    writeDescription(masked);
                    trace(TraceState.SEAL_MASKED, this);
                }
            } finally {
                writing = wasWriting;
            }
        }

        void close(boolean sameTarget) {
            if (released) return;
            released = true;
            pendingVisibleObservation = null;
            cancelGeometryTrace();
            if (layoutScope != null) layoutScope.removeOnLayoutChangeListener(layoutListener);
            releasingViews.add(view);
            writing = true;
            try {
                boolean restoreDescription = false;
                if (hasOwnedMarker(view.getText(), textMarker)) {
                    CharSequence original =
                            sameTarget && state.canRender() ? state.hostText() : null;
                    writeText(original != null ? original : "****");
                    restoreDescription =
                            original != null && original.toString().contentEquals(view.getText());
                    trace(
                            restoreDescription
                                    ? TraceState.RELEASE_RESTORED
                                    : original == null
                                            ? TraceState.RELEASE_MASKED
                                            : TraceState.RELEASE_HOST_FIELD,
                            this);
                } else {
                    trace(TraceState.RELEASE_HOST_FIELD, this);
                }
                if (hasOwnedMarker(view.getContentDescription(), descriptionMarker)) {
                    writeDescription(restoreDescription ? state.hostDescription() : null);
                }
            } finally {
                writing = false;
                state.invalidate();
                lastOutput.clear();
                releasingViews.remove(view);
            }
        }

        boolean matchesIdentity() {
            return assessIdentity() == IdentityStatus.VALID;
        }

        boolean isStrictFrameTarget() {
            return !released
                    && bindings.get(view) == this
                    && wealthRootCount == 1
                    && wealthRootIdentity == wealthIdentity
                    && candidateCounts.getOrDefault(kind, 0) == 1
                    && matchesIdentity();
        }

        IdentityStatus assessIdentity() {
            if (!isDescendantOrSelf(view, root)) return IdentityStatus.DETACHED;
            return assessAnchoredIdentity();
        }

        IdentityStatus assessAnchoredIdentity() {
            if (findNamedAncestor(view, DisplayTargets.WEALTH_ROOT) != wealthIdentity)
                return IdentityStatus.WEALTH_ROOT_CHANGED;
            return assessLocalIdentity();
        }

        boolean canAwaitWealthAnchor(View scope) {
            return !released
                    && bindingOwner == scope
                    && isDescendantOrSelf(view, scope)
                    && !isDescendantOrSelf(scope, root)
                    && findNamedAncestor(view, DisplayTargets.WEALTH_ROOT) == null
                    && wealthIdentity != null
                    && (wealthRootIdentity == null || wealthRootIdentity == wealthIdentity)
                    && assessLocalIdentity() == IdentityStatus.VALID;
        }

        private IdentityStatus assessLocalIdentity() {
            String id = resourceName(view);
            String expected =
                    kind == DisplayConfig.Kind.TOTAL
                            ? DisplayTargets.TOTAL_AMOUNT
                            : DisplayTargets.CARD_AMOUNT;
            if (!expected.equals(id)) return IdentityStatus.FIELD_CHANGED;
            if (findAmountContainer(view) != containerIdentity)
                return IdentityStatus.CONTAINER_CHANGED;
            ArrayList<View> labels =
                    findViewsByResourceName(
                            containerIdentity,
                            kind == DisplayConfig.Kind.TOTAL
                                    ? DisplayTargets.TOTAL_TITLE
                                    : DisplayTargets.CARD_TITLE);
            if (labels.isEmpty()) return IdentityStatus.LABEL_MISSING;
            if (labels.size() > 1) return IdentityStatus.LABEL_DUPLICATE;
            if (!(labels.get(0) instanceof TextView label))
                return IdentityStatus.LABEL_TYPE_MISMATCH;
            ArrayList<View> amounts = findViewsByResourceName(containerIdentity, expected);
            if (amounts.isEmpty()) return IdentityStatus.AMOUNT_MISSING;
            if (amounts.size() > 1) return IdentityStatus.AMOUNT_DUPLICATE;
            return DisplayTargets.wealthKind(id, label.getText().toString()) == kind
                    ? IdentityStatus.VALID
                    : IdentityStatus.TITLE_MISMATCH;
        }

        DrawFacts collectDrawFacts(DisplayConfig snapshot) {
            DrawFacts facts = new DrawFacts();
            String expected = snapshot.formatted(kind);
            boolean trusted = state.canRender();
            boolean pending = identityPending || visibleBindingPending || !bindingDepths.isEmpty();
            CharSequence text = view.getText();
            facts.textConfig = expected.contentEquals(text);
            facts.textMasked = isMaskedValue(text);
            facts.textSafe =
                    PreDrawPolicy.safeField(
                            containsDigits(text),
                            facts.textConfig,
                            hasOwnedMarker(text, textMarker),
                            trusted,
                            pending);
            Layout layout = view.getLayout();
            facts.layoutPresent = layout != null;
            CharSequence layoutText = layout == null ? null : layout.getText();
            facts.layoutConfig = layoutText != null && expected.contentEquals(layoutText);
            facts.layoutMasked = isMaskedValue(layoutText);
            facts.layoutSafe =
                    PreDrawPolicy.safeLayout(
                            facts.layoutPresent,
                            containsDigits(layoutText),
                            facts.layoutConfig,
                            facts.textSafe,
                            trusted,
                            pending);
            CharSequence description = view.getContentDescription();
            facts.descriptionConfig = description != null && expected.contentEquals(description);
            facts.descriptionMasked =
                    isMaskedValue(description) || DisplayTargets.isHiddenDescription(description);
            facts.descriptionSafe =
                    PreDrawPolicy.safeField(
                            containsDigits(description),
                            facts.descriptionConfig,
                            hasOwnedMarker(description, descriptionMarker),
                            trusted,
                            pending);
            return facts;
        }

        boolean isExpectedTextWrite() {
            CharSequence current = view.getText();
            return writingText
                    && expectedText != null
                    && hasOwnedMarker(current, textMarker)
                    && expectedText.toString().contentEquals(current);
        }

        boolean isExpectedDescriptionWrite() {
            if (!writingDescription) return false;
            CharSequence current = view.getContentDescription();
            return expectedDescription == null
                    ? current == null
                    : hasOwnedMarker(current, descriptionMarker)
                            && expectedDescription.toString().contentEquals(current);
        }

        /** 捕获未经过 setter 钩子的隐藏/占位写入；数字不能解除隐私屏障。 */
        private boolean refreshHostFieldSource() {
            CharSequence description = view.getContentDescription();
            if (!hasOwnedMarker(description, descriptionMarker)
                    && DisplayTargets.isHiddenDescription(description)) {
                if (!state.isDescriptionHidden()) privacyEpoch++;
                pendingVisibleObservation = null;
                state.hostDescription(copyDisplayText(description), true);
                maskOwnedText();
                return false;
            }
            CharSequence current = view.getText();
            boolean moduleOwned = hasOwnedMarker(current, textMarker);
            boolean amount = DisplayTargets.isVisibleAmount(current.toString());
            if (!amount) {
                // 临时绘制封口保留既有可信来源；真实隐藏已由 state 单独清源。
                if (PreDrawPolicy.retainTrustedMask(
                        moduleOwned, sealedMask, isMaskedValue(current), state.canRender()))
                    return true;
                if (!moduleOwned || state.canRender()) {
                    state.hostText(null, false);
                    trace(TraceState.UNREPORTED_NON_AMOUNT, this);
                }
                clearOwnedDescription();
                return false;
            }
            if (!moduleOwned && lastOutput.rejectsUnowned(current.toString())) {
                state.hostText(null, false);
                clearOwnedDescription();
                trace(TraceState.AMBIGUOUS_TEXT_ECHO, this);
                return false;
            }
            if (!moduleOwned) {
                if (!state.canRender()) {
                    trace(TraceState.UNPROVEN_NUMERIC, this);
                    return false;
                }
                CharSequence previous = state.hostText();
                if (assessAnchoredIdentity() == IdentityStatus.VALID
                        && (previous == null || !previous.toString().contentEquals(current))) {
                    state.hostText(copyDisplayText(current), true);
                    if (state.canRender()) lastOutput.acceptHost(current.toString(), false);
                    hostTextRevision++;
                    clearOwnedDescription();
                }
            } else if (!state.canRender()) {
                maskOwnedText();
                clearOwnedDescription();
                return false;
            }
            return true;
        }

        private void maskOwnedText() {
            if (!hasOwnedMarker(view.getText(), textMarker)) return;
            if ("****".contentEquals(view.getText())) return;
            SpannableString masked = new SpannableString("****");
            masked.setSpan(textMarker, 0, masked.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            writeText(masked);
            trace(TraceState.MASK_WRITE, this);
        }

        private void clearOwnedDescription() {
            if (!hasOwnedMarker(view.getContentDescription(), descriptionMarker)) return;
            writeDescription(null);
            trace(TraceState.OWNED_DESCRIPTION_CLEARED, this);
        }

        private void writeText(CharSequence value) {
            boolean wasWriting = writing;
            boolean wasWritingText = writingText;
            CharSequence previousExpected = expectedText;
            writing = true;
            writingText = true;
            expectedText = value;
            try {
                view.setText(value);
            } finally {
                expectedText = previousExpected;
                writingText = wasWritingText;
                writing = wasWriting;
            }
        }

        private void writeDescription(CharSequence value) {
            boolean wasWriting = writing;
            boolean wasWritingDescription = writingDescription;
            CharSequence previousExpected = expectedDescription;
            writing = true;
            writingDescription = true;
            expectedDescription = value;
            try {
                view.setContentDescription(value);
            } finally {
                expectedDescription = previousExpected;
                writingDescription = wasWritingDescription;
                writing = wasWriting;
            }
        }

        /** 每个绑定只采样一次；帧回调后排入消息队列，避开本帧布局之前的读取。 */
        void scheduleGeometryTrace() {
            if (geometryTraceScheduled || closed || released || !isTracingEnabled()) return;
            geometryTraceScheduled = true;
            long expectedGeneration = generation;
            Runnable sample =
                    () -> {
                        try {
                            if (!closed
                                    && !released
                                    && generation == expectedGeneration
                                    && bindings.get(view) == this)
                                trace(TraceState.BOUND_GEOMETRY, this);
                        } catch (RuntimeException | LinkageError ignored) {
                            // 几何诊断不影响展示，也不输出异常对象。
                        } finally {
                            geometrySample = null;
                        }
                    };
            geometrySample = sample;
            geometryFrame =
                    () -> {
                        try {
                            if (!closed
                                    && !released
                                    && generation == expectedGeneration
                                    && bindings.get(view) == this) root.post(sample);
                        } catch (RuntimeException | LinkageError ignored) {
                            geometrySample = null;
                        } finally {
                            geometryFrame = null;
                        }
                    };
            try {
                root.postOnAnimation(geometryFrame);
            } catch (RuntimeException | LinkageError ignored) {
                geometryFrame = null;
                geometrySample = null;
            }
        }

        private void cancelGeometryTrace() {
            try {
                if (geometryFrame != null) root.removeCallbacks(geometryFrame);
                if (geometrySample != null) root.removeCallbacks(geometrySample);
            } catch (RuntimeException | LinkageError ignored) {
                // 关闭及代次检查仍会使旧诊断回调失效。
            } finally {
                geometryFrame = null;
                geometrySample = null;
            }
        }
    }

    /** 保留宿主同步重入的批次边界，显式绑定结束后再接受来源证明。 */
    private final class BindingBatch {
        final boolean hidden;
        final long pageGeneration = generation;
        final ArrayList<BindingObservation> observations = new ArrayList<>();
        boolean accepted;

        BindingBatch(boolean hidden) {
            this.hidden = hidden;
        }
    }

    /** 显式绑定前后的来源凭据；隐私代次变化后不能移交旧观察。 */
    private final class BindingObservation {
        final Binding binding;
        final View scope;
        final long pageGeneration = generation;
        final long privacyEpoch;
        final long descriptionRevision;
        final long textRevision;
        final CharSequence descriptionBefore;
        final BindingTextProof textProof;
        final boolean waitForWealthAnchor;
        CharSequence textAfter;
        long textRevisionAfter;

        BindingObservation(Binding binding, View scope, boolean waitForWealthAnchor) {
            this.binding = binding;
            this.scope = scope;
            this.waitForWealthAnchor = waitForWealthAnchor;
            privacyEpoch = binding.privacyEpoch;
            descriptionRevision = binding.hostDescriptionRevision;
            textRevision = binding.hostTextRevision;
            descriptionBefore = binding.view.getContentDescription();
            CharSequence text = binding.view.getText();
            textProof =
                    new BindingTextProof(
                            text,
                            DisplayTargets.isVisibleAmount(text.toString()),
                            hasOwnedMarker(text, binding.textMarker),
                            privacyEpoch,
                            binding.hostTextRevision);
        }

        void captureIfFresh() {
            if (closed
                    || navigation.isSuspended()
                    || pageGeneration != generation
                    || binding.released
                    || bindings.get(binding.view) != binding
                    || binding.bindingOwner != scope
                    || binding.privacyEpoch != privacyEpoch) return;
            if (!waitForWealthAnchor
                    && (binding.state.isPrivacyHidden()
                            || !Boolean.FALSE.equals(privacyScopes.get(scope))
                            || binding.assessAnchoredIdentity() != IdentityStatus.VALID)) return;
            CharSequence text = binding.view.getText();
            boolean amount = DisplayTargets.isVisibleAmount(text.toString());
            boolean rejectedEcho = binding.lastOutput.isBlocked() && !binding.state.canRender();
            if (!textProof.changedToHostAmount(
                    text,
                    amount,
                    hasOwnedMarker(text, binding.textMarker),
                    binding.privacyEpoch,
                    waitForWealthAnchor || rejectedEcho
                            ? textRevision
                            : binding.hostTextRevision)) {
                if (amount && !binding.state.canRender())
                    trace(TraceState.BIND_SOURCE_UNCHANGED, binding);
                return;
            }
            if (waitForWealthAnchor) {
                textAfter = text;
                textRevisionAfter = binding.hostTextRevision;
                binding.pendingVisibleObservation = this;
                trace(TraceState.BIND_SOURCE_DEFERRED, binding);
                scheduleStableReconcile();
                return;
            }
            acceptText(text, descriptionRevision, descriptionBefore, privacyEpoch);
        }

        void acceptPendingIfValid() {
            if (closed
                    || navigation.isSuspended()
                    || pageGeneration != generation
                    || binding.released
                    || bindings.get(binding.view) != binding
                    || binding.privacyEpoch != privacyEpoch) {
                binding.pendingVisibleObservation = null;
                return;
            }
            if (!bindingDepths.isEmpty() || binding.identityPending || !binding.matchesIdentity())
                return;
            if (binding.view.getText() != textAfter
                    || binding.hostTextRevision != textRevisionAfter
                    || hasOwnedMarker(textAfter, binding.textMarker)
                    || !DisplayTargets.isVisibleAmount(textAfter.toString())) {
                binding.pendingVisibleObservation = null;
                trace(TraceState.BIND_SOURCE_STALE, binding);
                return;
            }
            binding.pendingVisibleObservation = null;
            binding.visibleBindingPending = false;
            privacyScopes.put(scope, false);
            binding.bindingHiddenKnown = true;
            binding.bindingHidden = false;
            binding.privacyEpoch++;
            binding.state.hostPrivacy(false);
            trace(TraceState.UNHIDE_PENDING, binding);
            acceptText(textAfter, descriptionRevision, descriptionBefore, binding.privacyEpoch);
        }

        private void acceptText(
                CharSequence text,
                long priorDescriptionRevision,
                CharSequence priorDescription,
                long expectedPrivacyEpoch) {
            binding.lastOutput.acceptHost(text.toString(), true);
            binding.state.hostText(copyDisplayText(text), true);
            binding.hostTextRevision++;
            binding.clearOwnedDescription();
            if (binding.privacyEpoch != expectedPrivacyEpoch || binding.state.isPrivacyHidden())
                return;
            CharSequence description = binding.view.getContentDescription();
            boolean freshDescription =
                    binding.hostDescriptionRevision != priorDescriptionRevision
                            || description != null && description != priorDescription;
            if (freshDescription && !hasOwnedMarker(description, binding.descriptionMarker)) {
                binding.state.hostDescription(
                        copyDisplayText(description),
                        DisplayTargets.isHiddenDescription(description));
            }
            trace(
                    binding.state.canRender()
                            ? TraceState.BIND_SOURCE_CAPTURED
                            : TraceState.BIND_SOURCE_BLOCKED,
                    binding);
        }
    }

    /** 纯布尔决策同时用于绘制封口和定向边界测试。 */
    static final class PreDrawPolicy {
        static boolean safeField(
                boolean containsDigits,
                boolean matchesConfig,
                boolean owned,
                boolean sourceTrusted,
                boolean privacyPending) {
            return !containsDigits || matchesConfig && owned && sourceTrusted && !privacyPending;
        }

        static boolean safeLayout(
                boolean present,
                boolean containsDigits,
                boolean matchesConfig,
                boolean textSafe,
                boolean sourceTrusted,
                boolean privacyPending) {
            return present
                    && textSafe
                    && (!containsDigits || matchesConfig && sourceTrusted && !privacyPending);
        }

        static boolean deferLayout(boolean unsafe, int consecutiveDeferrals) {
            return unsafe && consecutiveDeferrals < MAX_LAYOUT_DEFERRALS;
        }

        static boolean retainTrustedMask(
                boolean moduleOwned,
                boolean temporarySealMask,
                boolean masked,
                boolean sourceTrusted) {
            return moduleOwned && temporarySealMask && masked && sourceTrusted;
        }
    }

    private static final class DrawFacts {
        boolean textSafe;
        boolean layoutSafe;
        boolean descriptionSafe;
        boolean layoutPresent;
        boolean textConfig;
        boolean textMasked;
        boolean layoutConfig;
        boolean layoutMasked;
        boolean descriptionConfig;
        boolean descriptionMasked;

        boolean isSafe() {
            return textSafe && layoutSafe && descriptionSafe;
        }
    }

    private static final class DrawCounts {
        int checked;
        int safe;
        int suppressed;
        int layoutMissing;
        int textUnsafe;
        int layoutUnsafe;
        int descriptionUnsafe;
        int textConfig;
        int textMasked;
        int layoutConfig;
        int layoutMasked;
        int descriptionConfig;
        int descriptionMasked;

        void record(DrawFacts facts) {
            if (checked >= MAX_HITS) return;
            checked++;
            if (facts.isSafe()) safe++;
            else suppressed++;
            if (!facts.layoutPresent) layoutMissing++;
            if (!facts.textSafe) textUnsafe++;
            if (!facts.layoutSafe) layoutUnsafe++;
            if (!facts.descriptionSafe) descriptionUnsafe++;
            if (facts.textConfig) textConfig++;
            if (facts.textMasked) textMasked++;
            if (facts.layoutConfig) layoutConfig++;
            if (facts.layoutMasked) layoutMasked++;
            if (facts.descriptionConfig) descriptionConfig++;
            if (facts.descriptionMasked) descriptionMasked++;
        }
    }

    private enum NavigationTrace {
        UNKNOWN_UNAVAILABLE,
        UNKNOWN_UNTRUSTED,
        UNKNOWN_CURRENT_HIDDEN,
        UNKNOWN_INCONSISTENT,
        CURRENT,
        DEFERRED,
        LEFT,
        AWAY,
        ENTERED
    }

    private enum TraceState {
        SCHEDULED,
        REFRESH_RUN,
        CONFIG_DISABLED,
        ROOT_NONE,
        ROOT_ONE,
        ROOT_MULTIPLE,
        CANDIDATE_NONE,
        CANDIDATE_ONE,
        CANDIDATE_MULTIPLE,
        BOUND,
        BOUND_GEOMETRY,
        TEXT_HOOK_UNBOUND,
        DESCRIPTION_HOOK_UNBOUND,
        BIND_START,
        BIND_INVALIDATED,
        BIND_HIDDEN,
        BIND_VISIBLE,
        WAIT_BINDING,
        NOT_VISIBLE,
        UNHIDE_PENDING,
        VISIBLE_BINDING_IGNORED_CLOSED,
        VISIBLE_BINDING_IGNORED_CLOSING,
        VISIBLE_BINDING_IGNORED_SUSPENDED,
        VISIBLE_BINDING_IGNORED_NULL,
        VISIBLE_BINDING_IGNORED_SCOPE,
        VISIBLE_BINDING_IGNORED_ANCHOR,
        VISIBLE_BINDING_IGNORED_OWNER,
        BIND_SOURCE_CAPTURED,
        BIND_SOURCE_BLOCKED,
        BIND_SOURCE_UNCHANGED,
        UNREPORTED_NON_AMOUNT,
        UNPROVEN_NUMERIC,
        AMBIGUOUS_TEXT_ECHO,
        UNHIDE_WAIT_ANCHOR,
        BIND_SOURCE_DEFERRED,
        BIND_SOURCE_STALE,
        HOST_TEXT_AMOUNT,
        HOST_TEXT_MASK,
        HOST_TEXT_OTHER,
        HOST_TEXT_MODULE_COPY,
        HOST_DESCRIPTION_VISIBLE,
        HOST_DESCRIPTION_HIDDEN,
        HOST_DESCRIPTION_MODULE_COPY,
        WAIT_HOST_AMOUNT,
        IDENTITY_CHANGED,
        IDENTITY_STABLE_REJECTED,
        TEXT_WRITE,
        TEXT_MARKER_HELD,
        TEXT_MARKER_LOST,
        TEXT_CONTENT_CHANGED,
        DESCRIPTION_WRITE,
        DESCRIPTION_MARKER_HELD,
        DESCRIPTION_MARKER_LOST,
        MASK_WRITE,
        OWNED_DESCRIPTION_CLEARED,
        STATE_INVALIDATED,
        RENDERED,
        RELEASE_RESTORED,
        RELEASE_MASKED,
        RELEASE_HOST_FIELD,
        CLOSED,
        RECONCILE_ERROR,
        SEAL_MASKED,
        SEAL_LAYOUT_DEFERRED,
        SEAL_LAYOUT_FENCED
    }

    private enum TraceText {
        NONE,
        AMOUNT,
        MASK,
        PLACEHOLDER,
        OTHER,
        MODULE_MASK,
        MODULE_TEXT
    }

    private enum IdentityStatus {
        UNASSESSED,
        VALID,
        DETACHED,
        FIELD_CHANGED,
        WEALTH_ROOT_CHANGED,
        CONTAINER_CHANGED,
        LABEL_MISSING,
        LABEL_DUPLICATE,
        LABEL_TYPE_MISMATCH,
        AMOUNT_MISSING,
        AMOUNT_DUPLICATE,
        TITLE_MISMATCH,
        ROOT_MISSING,
        ROOT_DUPLICATE,
        SEMANTIC_MISSING,
        SEMANTIC_DUPLICATE
    }
}
