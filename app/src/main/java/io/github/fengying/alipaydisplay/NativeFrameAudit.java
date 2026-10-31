package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.graphics.Rect;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** 有界绘制前审计；不改字段、布局、监听器返回值或显示会话状态。 */
final class NativeFrameAudit {
    private static final String TAG = "AlipayDisplayDiag";
    private static final int MAX_DISCOVERY_VIEWS = 4096;
    private static final DisplayConfig.Kind[] KINDS = {
        DisplayConfig.Kind.TOTAL, DisplayConfig.Kind.YUEBAO, DisplayConfig.Kind.FUND
    };

    private final Activity activity;
    private final View root;
    private final Supplier<DisplayConfig> config;
    private final BooleanSupplier configReady;
    private final Function<TextView, FieldState> probe;
    private final NativeFrameAuditState state = new NativeFrameAuditState();
    private final EnumMap<DisplayConfig.Kind, Target> targets =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final EnumMap<DisplayConfig.Kind, Integer> candidateCounts =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final EnumMap<DisplayConfig.Kind, NativeFrameAuditState.Counts> activeCounts =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final EnumMap<DisplayConfig.Kind, NativeFrameAuditState.Counts> pausedCounts =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final ViewTreeObserver.OnPreDrawListener listener = this::onPreDraw;
    private ViewTreeObserver observer;
    private View wealthRoot;
    private int rootCount;
    private boolean complete;
    private boolean dirty = true;
    private boolean paused;
    private boolean closed;
    private boolean started;
    private boolean samplingFailed;

    NativeFrameAudit(
            Activity activity,
            Supplier<DisplayConfig> config,
            BooleanSupplier configReady,
            Function<TextView, FieldState> probe) {
        this.activity = activity;
        this.root = activity.getWindow().getDecorView();
        this.config = config;
        this.configReady = configReady;
        this.probe = probe;
        for (DisplayConfig.Kind kind : KINDS) {
            activeCounts.put(kind, new NativeFrameAuditState.Counts());
            pausedCounts.put(kind, new NativeFrameAuditState.Counts());
        }
    }

    boolean start() {
        if (closed) return false;
        if (started) return true;
        try {
            if (!RuntimePolicy.isWealthActivity(activity.getClass().getName())
                    || !RuntimePolicy.acceptsVersion(
                            activity.getPackageName(),
                            activity.getPackageManager()
                                    .getPackageInfo(activity.getPackageName(), 0)
                                    .getLongVersionCode())) return false;
            observer = root.getViewTreeObserver();
            if (!observer.isAlive()) return false;
            observer.addOnPreDrawListener(listener);
            started = true;
            return true;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    void onResume() {
        if (!closed) {
            paused = false;
            dirty = true;
            start();
        }
    }

    /** 必须在显示会话 close 之前标记，保留 listener 到 onStop/onDestroy。 */
    void onPause() {
        if (!closed) paused = true;
    }

    /** 原结构钩子和目标标题写入只使发现缓存失效，不在 hook 内采样。 */
    void onStructureChanged(View scope) {
        if (!closed
                && scope != null
                && (scope.getRootView() == root || isDescendantOrSelf(scope, root))) dirty = true;
    }

    void close() {
        if (closed) return;
        closed = true;
        detachListener();
        reportSnapshot(NativeFrameAuditState.Phase.ACTIVE, true);
        reportSnapshot(NativeFrameAuditState.Phase.PAUSED, true);
        targets.clear();
        wealthRoot = null;
    }

    private boolean onPreDraw() {
        if (closed || samplingFailed) return true;
        try {
            DisplayConfig snapshot = config.get();
            boolean ready = configReady.getAsBoolean();
            // 冷启动只积累匿名 pending 事实；确认关闭诊断时不读取字段。
            if (ready && !snapshot.diagnostics) return true;
            NativeFrameAuditState.Phase phase =
                    paused
                            ? NativeFrameAuditState.Phase.PAUSED
                            : NativeFrameAuditState.Phase.ACTIVE;
            if (!state.begin(phase)) return true;
            if (dirty
                    || wealthRoot == null
                    || targets.isEmpty()
                    || !isDescendantOrSelf(wealthRoot, root)) discoverTargets();
            int visible = 0;
            if (complete && rootCount == 1) {
                EnumMap<DisplayConfig.Kind, NativeFrameAuditState.Counts> counts =
                        countsForPhase(phase);
                for (DisplayConfig.Kind kind : KINDS) {
                    Target target = targets.get(kind);
                    if (target == null) continue;
                    if (identifyStrictAmountKind(target.view, wealthRoot) != kind
                            || findAmountContainer(target.view) != target.container) {
                        dirty = true;
                        continue;
                    }
                    if (!isVisuallyEligible(target.view)) continue;
                    counts.get(kind).record(collectSample(target.view, kind, snapshot, ready));
                    visible++;
                }
            }
            state.observed(phase, ready, snapshot.enabled, complete, rootCount, visible);
            int draws = state.budget(phase).visibleDraws;
            if (visible > 0
                            && (draws == 1
                                    || draws == 3
                                    || draws == 10
                                    || draws == 30
                                    || draws == 60
                                    || draws == 90)
                    || state.exhausted(phase)) reportSnapshot(phase, false);
        } catch (RuntimeException | LinkageError ignored) {
            samplingFailed = true;
            detachListener();
            reportSnapshot(
                    paused
                            ? NativeFrameAuditState.Phase.PAUSED
                            : NativeFrameAuditState.Phase.ACTIVE,
                    false);
        }
        return true;
    }

    private void discoverTargets() {
        dirty = false;
        targets.clear();
        candidateCounts.clear();
        wealthRoot = null;
        rootCount = 0;
        complete = true;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int visits = 0;
        while (!pending.isEmpty()) {
            if (++visits > MAX_DISCOVERY_VIEWS) {
                complete = false;
                return;
            }
            View current = pending.removeFirst();
            if (DisplayTargets.WEALTH_ROOT.equals(NativeDisplaySession.resourceName(current))) {
                rootCount++;
                wealthRoot = current;
            }
            enqueueChildren(current, pending);
        }
        if (rootCount != 1) {
            wealthRoot = null;
            return;
        }
        pending.add(wealthRoot);
        while (!pending.isEmpty()) {
            if (++visits > MAX_DISCOVERY_VIEWS) {
                complete = false;
                targets.clear();
                return;
            }
            View current = pending.removeFirst();
            if (current instanceof TextView text) {
                DisplayConfig.Kind kind = identifyStrictAmountKind(text, wealthRoot);
                if (kind != null) {
                    candidateCounts.put(kind, candidateCounts.getOrDefault(kind, 0) + 1);
                    targets.put(kind, new Target(text, findAmountContainer(text)));
                }
            }
            enqueueChildren(current, pending);
        }
        for (DisplayConfig.Kind kind : KINDS) {
            if (candidateCounts.getOrDefault(kind, 0) != 1) targets.remove(kind);
        }
    }

    private NativeFrameAuditState.Sample collectSample(
            TextView target, DisplayConfig.Kind kind, DisplayConfig snapshot, boolean ready) {
        NativeFrameAuditState.Sample facts = new NativeFrameAuditState.Sample();
        facts.configReady = ready;
        facts.enabled = snapshot.enabled;
        String expected = ready ? snapshot.formatted(kind) : null;
        // 引用仅在本次回调内比较；状态和日志都不接收原文、Layout 或节点对象。
        CharSequence text = target.getText();
        facts.textMatches = expected != null && expected.contentEquals(text);
        facts.textMasked = isMaskedValue(text);
        facts.textNumeric = DisplayTargets.isVisibleAmount(text.toString());
        facts.textDigits = containsDigits(text);
        Layout layout = target.getLayout();
        facts.layoutPresent = layout != null;
        if (layout != null) {
            CharSequence layoutText = layout.getText();
            facts.layoutMatches = expected != null && expected.contentEquals(layoutText);
            facts.layoutMasked = isMaskedValue(layoutText);
            facts.layoutNumeric = DisplayTargets.isVisibleAmount(layoutText.toString());
            facts.layoutDigits = containsDigits(layoutText);
        }
        CharSequence description = target.getContentDescription();
        facts.descriptionPresent = description != null && description.length() != 0;
        facts.descriptionMatches =
                expected != null && description != null && expected.contentEquals(description);
        facts.descriptionMasked =
                isMaskedValue(description) || DisplayTargets.isHiddenDescription(description);
        facts.descriptionDigits = containsDigits(description);
        FieldState field = probe == null ? FieldState.UNKNOWN : probe.apply(target);
        if (field == null) field = FieldState.UNKNOWN;
        facts.bound = field.bound;
        facts.textOwned = field.textOwned;
        facts.descriptionOwned = field.descriptionOwned;
        facts.privacyKnown = field.privacyKnown || facts.textMasked || facts.descriptionMasked;
        facts.privacyHidden = field.privacyHidden || facts.textMasked || facts.descriptionMasked;
        return facts;
    }

    private EnumMap<DisplayConfig.Kind, NativeFrameAuditState.Counts> countsForPhase(
            NativeFrameAuditState.Phase phase) {
        return phase == NativeFrameAuditState.Phase.PAUSED ? pausedCounts : activeCounts;
    }

    /** 共用一个失败边界，任何阶段报告失败都会结束本次输出。 */
    private void reportSnapshot(NativeFrameAuditState.Phase phase, boolean finalReport) {
        try {
            if (!config.get().diagnostics) return;
            reportPhaseBudget(phase, finalReport);
            for (DisplayConfig.Kind kind : KINDS) {
                reportTargetCounts(phase, kind, finalReport);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void reportPhaseBudget(NativeFrameAuditState.Phase phase, boolean finalReport) {
        NativeFrameAuditState.Budget budget = state.budget(phase);
        Log.i(
                TAG,
                "native-frame phase="
                        + phase
                        + " final="
                        + finalReport
                        + " attempts="
                        + budget.attempts
                        + " visiblePreDraws="
                        + budget.visibleDraws
                        + " hasSamples="
                        + (budget.visibleDraws != 0)
                        + " exhausted="
                        + state.exhausted(phase)
                        + " configPendingPreDraws="
                        + budget.configPendingDraws
                        + " disabledPreDraws="
                        + budget.disabledDraws
                        + " rootMissingPreDraws="
                        + budget.rootMissingDraws
                        + " rootAmbiguousPreDraws="
                        + budget.rootAmbiguousDraws
                        + " incompleteDiscoveryPreDraws="
                        + budget.incompleteDiscoveryDraws
                        + " noVisibleTargetPreDraws="
                        + budget.noVisibleTargetDraws
                        + " samplingFailed="
                        + samplingFailed);
    }

    private void reportTargetCounts(
            NativeFrameAuditState.Phase phase, DisplayConfig.Kind kind, boolean finalReport) {
        NativeFrameAuditState.Counts count = countsForPhase(phase).get(kind);
        NativeFrameAuditState.Sample first = count.first;
        Log.i(
                TAG,
                "native-frame-field phase="
                        + phase
                        + " kind="
                        + kind
                        + " final="
                        + finalReport
                        + " discoveryComplete="
                        + complete
                        + " cachedCandidates="
                        + (complete ? candidateCounts.getOrDefault(kind, 0) : -1)
                        + " samples="
                        + count.samples
                        + " configPending="
                        + count.configPending
                        + " disabled="
                        + count.disabled
                        + " checked="
                        + count.checked
                        + " textMatches="
                        + count.textMatches
                        + " textMasked="
                        + count.textMasked
                        + " unexpectedTextNumeric="
                        + count.unexpectedTextNumeric
                        + " layoutMissing="
                        + count.layoutMissing
                        + " layoutMatches="
                        + count.layoutMatches
                        + " layoutMasked="
                        + count.layoutMasked
                        + " unexpectedLayoutNumeric="
                        + count.unexpectedLayoutNumeric
                        + " descriptionMissing="
                        + count.descriptionMissing
                        + " descriptionMatches="
                        + count.descriptionMatches
                        + " descriptionMasked="
                        + count.descriptionMasked
                        + " unexpectedDescriptionDigits="
                        + count.unexpectedDescriptionDigits
                        + " unbound="
                        + count.unbound
                        + " textOwned="
                        + count.textOwned
                        + " descriptionOwned="
                        + count.descriptionOwned
                        + " privacyUnknown="
                        + count.privacyUnknown
                        + " privacyHidden="
                        + count.privacyHidden
                        + " hiddenDigits="
                        + count.hiddenDigits
                        + " firstSampled="
                        + (first != null)
                        + " firstReady="
                        + (first != null && first.configReady)
                        + " firstEnabled="
                        + (first != null && first.enabled)
                        + " firstBound="
                        + (first != null && first.bound)
                        + " firstTextMatches="
                        + (first != null && first.textMatches)
                        + " firstTextMasked="
                        + (first != null && first.textMasked)
                        + " firstLayoutPresent="
                        + (first != null && first.layoutPresent)
                        + " firstLayoutMatches="
                        + (first != null && first.layoutMatches)
                        + " firstLayoutMasked="
                        + (first != null && first.layoutMasked)
                        + " firstDescriptionMatches="
                        + (first != null && first.descriptionMatches)
                        + " firstDescriptionMasked="
                        + (first != null && first.descriptionMasked)
                        + " firstPrivacyKnown="
                        + (first != null && first.privacyKnown)
                        + " firstPrivacyHidden="
                        + (first != null && first.privacyHidden));
    }

    private void detachListener() {
        try {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(listener);
            ViewTreeObserver current = root.getViewTreeObserver();
            if (current != observer && current.isAlive()) current.removeOnPreDrawListener(listener);
        } catch (RuntimeException | LinkageError ignored) {
        }
        observer = null;
    }

    private static DisplayConfig.Kind identifyStrictAmountKind(
            TextView target, View expectedWealth) {
        String id = NativeDisplaySession.resourceName(target);
        if (!DisplayTargets.isAmountResource(id)
                || findNamedAncestor(target, DisplayTargets.WEALTH_ROOT) != expectedWealth)
            return null;
        View container = findAmountContainer(target);
        if (container == null || !isDescendantOrSelf(container, expectedWealth)) return null;
        ArrayList<View> labels =
                findViewsByResourceName(
                        container,
                        DisplayTargets.CARD_AMOUNT.equals(id)
                                ? DisplayTargets.CARD_TITLE
                                : DisplayTargets.TOTAL_TITLE);
        ArrayList<View> amounts = findViewsByResourceName(container, id);
        if (labels == null
                || amounts == null
                || labels.size() != 1
                || amounts.size() != 1
                || amounts.get(0) != target
                || !(labels.get(0) instanceof TextView title)) return null;
        return DisplayTargets.wealthKind(id, title.getText().toString());
    }

    private static View findAmountContainer(TextView target) {
        return findNamedAncestor(
                target,
                DisplayTargets.WEALTH
                        + (DisplayTargets.CARD_AMOUNT.equals(
                                        NativeDisplaySession.resourceName(target))
                                ? "asset_horizon_title_container"
                                : "fh_assets_amount_layout"));
    }

    private static ArrayList<View> findViewsByResourceName(View root, String name) {
        ArrayList<View> found = new ArrayList<>();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int visits = 0;
        while (!pending.isEmpty()) {
            if (++visits > MAX_DISCOVERY_VIEWS) return null;
            View current = pending.removeFirst();
            if (name.equals(NativeDisplaySession.resourceName(current))) found.add(current);
            enqueueChildren(current, pending);
        }
        return found;
    }

    private static void enqueueChildren(View view, ArrayDeque<View> pending) {
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
        }
    }

    private static View findNamedAncestor(View view, String name) {
        View current = view;
        while (current != null) {
            if (name.equals(NativeDisplaySession.resourceName(current))) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean isDescendantOrSelf(View view, View ancestor) {
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean isVisuallyEligible(View target) {
        if (!target.isAttachedToWindow()
                || !target.isShown()
                || target.getWidth() <= 0
                || target.getHeight() <= 0
                || !target.getGlobalVisibleRect(new Rect())) return false;
        View current = target;
        while (current != null) {
            if (!(current.getAlpha() > 0f)) return false;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    private static boolean isMaskedValue(CharSequence value) {
        if (value == null || value.length() < 2) return false;
        for (int i = 0; i < value.length(); i++) {
            if ("*＊●•·".indexOf(value.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static boolean containsDigits(CharSequence value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++)
            if (Character.isDigit(value.charAt(i))) return true;
        return false;
    }

    static final class FieldState {
        static final FieldState UNKNOWN = new FieldState(false, false, false, false, false);
        final boolean bound;
        final boolean textOwned;
        final boolean descriptionOwned;
        final boolean privacyKnown;
        final boolean privacyHidden;

        FieldState(
                boolean bound,
                boolean textOwned,
                boolean descriptionOwned,
                boolean privacyKnown,
                boolean privacyHidden) {
            this.bound = bound;
            this.textOwned = textOwned;
            this.descriptionOwned = descriptionOwned;
            this.privacyKnown = privacyKnown;
            this.privacyHidden = privacyHidden;
        }
    }

    private static final class Target {
        final TextView view;
        final View container;

        Target(TextView view, View container) {
            this.view = view;
            this.container = container;
        }
    }
}
