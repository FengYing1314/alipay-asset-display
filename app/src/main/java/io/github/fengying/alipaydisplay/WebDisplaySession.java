package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 持有 Activity 的 Web 文档会话；异步执行前后复核页面身份和文档代次。 */
final class WebDisplaySession {
    private static final String WEB_VIEW = "com.alipay.mywebview.sdk.WebView";
    private static final int MAX_VIEWS = 1024;
    private static final String[] FUND_DEBUG_FIELDS = {
        "bindingPresent",
        "shapeKnown",
        "sameShape",
        "sourceVisible",
        "textFresh",
        "outerFresh",
        "innerFresh",
        "displayFresh",
        "displayOwned",
        "outerOwned",
        "innerOwned",
        "textProof",
        "outerProof",
        "innerProof",
        "numberIntroduced",
        "scopeIntroduced",
        "lastOutputAmbiguous",
        "privacyHidden",
        "numericShapeKnown",
        "textMatchesCurrent",
        "outerMatchesCurrent",
        "innerMatchesCurrent",
        "outerEpochCurrent",
        "innerEpochCurrent",
        "outputEchoAllowed",
        "sourceMasked",
        "outerMasked",
        "innerMasked",
        "eyeAnchorKnown",
        "eyeActionShow",
        "eyeActionHide",
        "showReceipt",
        "scopeMaskSeen"
    };
    private static final Set<String> STATUSES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    "prepared",
                                    "applied",
                                    "partial",
                                    "hidden",
                                    "waiting-host",
                                    "unknown-shape",
                                    "stopped",
                                    "rejected",
                                    "stale-session",
                                    "stale-revision",
                                    "unavailable",
                                    "invalid-config",
                                    "revision-conflict",
                                    "abandoned")));
    private final Activity activity;
    private final Supplier<DisplayConfig> config;
    private final Consumer<Class<?>> observer;
    private final String moduleApk;
    private final Executor worker;
    private final Consumer<View> documentProbeReader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object diagnosticToken = new Object();
    private String nativeSession = UUID.randomUUID().toString();
    private final Set<String> emitted = new HashSet<>();
    private final Runnable refresh = () -> runSafely(this::inspectDocument);
    private View view;
    private WebPageIdentity identity;
    private Object pendingCallback;
    private boolean refreshAfterCallback;
    private String nonce;
    private String script;
    private WebDisplayProfile scriptProfile;
    private long generation;
    private boolean queued;
    private boolean closed;
    private boolean loadingScript;
    private boolean foreground;
    private int retries;
    private WebPageIdentity recoveryIdentity;
    private int leaseRecoveries;
    private long highestIssuedRevision;
    private long diagnosticGeneration = -1L;
    private long documentProbeGeneration = -1L;
    // 状态机只处理截止时间与回复计数；页面和异步回调归属仍由本会话复核。
    private WebDrawGate drawGate;
    private View gateView;
    private View seenDrawView;
    private WebPageIdentity gateIdentity;
    private String gateSession, gateNonce;
    private long gateRevision = -1L;
    private long visualSequence, visualRequest;
    private boolean visualAvailable, visualReceived;
    private boolean retryGateOnEntry;
    private int suppressedDraws;
    private int gateFailures;
    private int ungatedDraws;
    private WebDrawGate.State reportedGateState;
    private final Runnable gateTimeout =
            () ->
                    runSafely(
                            () -> {
                                if (drawGate == null) return;
                                drawGate.suppress(SystemClock.uptimeMillis());
                                reportGate();
                                if (gateView != null) gateView.invalidate();
                            });

    WebDisplaySession(
            Activity activity,
            Supplier<DisplayConfig> config,
            Consumer<Class<?>> observer,
            String moduleApk,
            Executor worker,
            Consumer<View> documentProbeReader) {
        this.activity = activity;
        this.config = config;
        this.observer = observer;
        this.moduleApk = moduleApk;
        this.worker = worker;
        this.documentProbeReader = documentProbeReader;
    }

    // 生命周期与宿主事件入口。

    static boolean isCandidate(Activity activity) {
        try {
            activity.getClass().getMethod("getCurrentApp");
            activity.getClass().getMethod("getCurrentUri");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    void onResume() {
        if (closed) return;
        boolean newEntry = !foreground;
        foreground = true;
        retries = 0;
        if (newEntry && drawGate != null) {
            drawGate.suppress(SystemClock.uptimeMillis());
            reportGate();
            retryGateOnEntry = drawGate.timedOut;
        }
        if (visualReceived) finishVisualConfirmation();
        scheduleInspectionNow();
    }

    void onPause() {
        foreground = false;
        main.removeCallbacks(refresh);
        main.removeCallbacksAndMessages(diagnosticToken);
        diagnosticGeneration = -1L;
        documentProbeGeneration = -1L;
        queued = false;
        // 后台回调不以临时detach/未就绪身份退休仍有效的页内observer。
        pendingCallback = null;
        refreshAfterCallback = false;
        if (!config.get().enabled) scheduleInspectionNow();
    }

    void schedule() {
        if (closed || queued || !foreground && config.get().enabled) return;
        queued = true;
        main.postDelayed(refresh, config.get().enabled ? 200L : 0L);
    }

    void onStructureChanged(View scope) {
        if (!closed && scope != null && scope.getRootView() == getActivityRoot()) schedule();
    }

    void onDocumentChanged(View scope, boolean restoreIfSameDocument) {
        if (closed || scope == null || scope != view && scope != gateView && scope != seenDrawView)
            return;
        clearDrawGate();
        seenDrawView = null;
        ungatedDraws = 0;
        gateFailures = 0;
        retireDocument(restoreIfSameDocument);
        recoveryIdentity = null;
        leaseRecoveries = 0;
        retries = 0;
        schedule();
    }

    /** 仅供宿主精确SDK子树的draw拦截使用；未知归属立即放行，不读取页面金额。 */
    boolean shouldSuppressDraw(View exactSdkView) {
        if (closed || exactSdkView == null || findSdkWebViewClass(exactSdkView) == null)
            return false;
        if (!foreground && (drawGate == null || gateView != exactSdkView)) return false;
        try {
            DisplayConfig snapshot = config.get();
            if (!snapshot.enabled || exactSdkView.getRootView() != getActivityRoot()) return false;
            if (view != null && view != exactSdkView) return false;
            if (view == null && !isUniqueSdkWebView(exactSdkView)) return false;
            seenDrawView = exactSdkView;
            WebPageIdentity current = WebPageIdentity.capture(activity, exactSdkView);
            if (current == null || !current.supported()) {
                noteUngatedDraw();
                if (gateView == exactSdkView && drawGate != null) {
                    drawGate.fail(WebDrawGate.Failure.IDENTITY_UNAVAILABLE);
                    reportGate();
                    drawGate.drawReleased(SystemClock.uptimeMillis());
                }
                return false;
            }
            if (!foreground && gateIdentity != null && !gateIdentity.same(current)) {
                noteUngatedDraw();
                drawGate.fail(WebDrawGate.Failure.IDENTITY_CHANGED);
                reportGate();
                drawGate.drawReleased(SystemClock.uptimeMillis());
                return false;
            }
            ensureDrawGate(exactSdkView, current, snapshot);
            if (visualReceived) finishVisualConfirmation();
            boolean suppress = drawGate.suppress(SystemClock.uptimeMillis());
            if (suppress) {
                suppressedDraws = Math.min(10000, suppressedDraws + 1);
                if (foreground && pendingCallback == null && !loadingScript)
                    scheduleInspectionNow();
            } else drawGate.drawReleased(SystemClock.uptimeMillis());
            reportGate();
            return suppress;
        } catch (RuntimeException | LinkageError ignored) {
            noteUngatedDraw();
            if (drawGate != null) {
                drawGate.fail(WebDrawGate.Failure.IDENTITY_UNAVAILABLE);
                reportGate();
                drawGate.drawReleased(SystemClock.uptimeMillis());
            }
            return false;
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        clearDrawGate();
        main.removeCallbacks(refresh);
        queued = false;
        retireDocument(true);
        view = null;
    }

    // 文档检查、脚本加载与握手流程。

    private void scheduleInspectionNow() {
        if (closed || !foreground && config.get().enabled) return;
        main.removeCallbacks(refresh);
        queued = true;
        main.post(refresh);
    }

    private void inspectDocument() {
        queued = false;
        if (closed) return;
        DisplayConfig snapshot = config.get();
        if (!snapshot.enabled) {
            clearDrawGate();
            if (nonce != null) retireDocument(true);
            return;
        }
        if (!foreground) return;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(getActivityRoot());
        View match = null;
        Class<?> webType = null;
        int visits = 0;
        while (!pending.isEmpty() && visits++ < MAX_VIEWS) {
            View candidate = pending.removeFirst();
            if (!candidate.isShown()) continue;
            Class<?> type = findSdkWebViewClass(candidate);
            if (type != null) {
                if (match != null) {
                    retireDocument(false);
                    traceStatus("multiple-webviews");
                    return;
                }
                match = candidate;
                webType = type;
            }
            if (candidate instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++)
                    pending.addLast(group.getChildAt(i));
            }
        }
        if (!pending.isEmpty()) {
            retireDocument(false);
            traceStatus("view-budget-exceeded");
            return;
        }
        if (match == null || !match.isAttachedToWindow()) {
            retireDocument(false);
            scheduleInspectionRetry();
            return;
        }
        observer.accept(webType);
        WebPageIdentity current = WebPageIdentity.capture(activity, match, this::traceStatus);
        if (current == null) {
            if (nonce != null || identity != null) retireDocument(false);
            traceStatus("identity-pending");
            scheduleInspectionRetry();
            return;
        }
        if (!current.supported()) {
            if (nonce != null || identity != null) retireDocument(false);
            traceStatus("profile-unsupported");
            if (config.get().diagnostics && emitted.add("document-profile")) {
                Log.i(
                        "AlipayDisplayDiag",
                        "web-display documentRouteFingerprint="
                                + current.documentFingerprint
                                + " documentRouteKnown="
                                + WebDisplayProfile.acceptsDocument(current.documentRoute));
            }
            return;
        }
        if (recoveryIdentity != null && !recoveryIdentity.same(current)) {
            recoveryIdentity = null;
            leaseRecoveries = 0;
        }
        if (leaseRecoveries > 3) {
            traceStatus("session-recovery-exhausted");
            return;
        }
        if (view != match || identity == null || !identity.same(current)) {
            if (identity != null || nonce != null) retireDocument(false);
            else generation++;
            view = match;
            identity = current;
            nonce = null;
        }
        ensureDrawGate(match, current, snapshot);
        if (drawGate.state == WebDrawGate.State.WAIT_VISUAL
                && visualRequest != 0L
                && nonce != null
                && nonce.equals(gateNonce)) return;
        if (pendingCallback != null) {
            refreshAfterCallback = true;
            return;
        }
        if (script == null || scriptProfile != identity.profile) {
            requestScriptResource(identity.profile);
            return;
        }
        if (nonce == null) {
            evaluateScriptCommand(createScriptCommand("prepare", snapshot), "prepare");
        } else {
            evaluateScriptCommand(createScriptCommand("apply", snapshot), "apply");
        }
    }

    private void scheduleInspectionRetry() {
        if (!foreground) return;
        if (drawGate != null && drawGate.suppress(SystemClock.uptimeMillis())) {
            if (!closed && !queued) {
                queued = true;
                main.postDelayed(refresh, 50L);
            }
            return;
        }
        if (retries >= 5) {
            traceStatus("identity-not-ready");
            return;
        }
        long delay = 400L << retries++;
        if (!closed && !queued) {
            queued = true;
            main.postDelayed(refresh, delay);
        }
    }

    private void requestScriptResource(WebDisplayProfile profile) {
        if (loadingScript || closed) return;
        loadingScript = true;
        worker.execute(
                () -> {
                    String loaded;
                    try {
                        loaded = loadScriptResource(profile);
                    } catch (RuntimeException | LinkageError ignored) {
                        loaded = null;
                    }
                    final String result = loaded;
                    try {
                        main.post(
                                () ->
                                        runSafely(
                                                () -> {
                                                    loadingScript = false;
                                                    // 页面可在后台加载期间返回旧profile；不得覆盖已有lease所用脚本。
                                                    if (identity == null
                                                            || identity.profile != profile) {
                                                        schedule();
                                                        return;
                                                    }
                                                    script = result;
                                                    scriptProfile = profile;
                                                    if (result == null)
                                                        traceStatus("script-unavailable");
                                                    else scheduleInspectionNow();
                                                }));
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                });
    }

    private String loadScriptResource(WebDisplayProfile profile) {
        try (InputStream stream =
                WebDisplaySession.class.getClassLoader().getResourceAsStream(profile.scriptPath)) {
            if (stream != null) return readScriptExpression(stream);
        } catch (IOException | RuntimeException ignored) {
        }
        if (moduleApk == null) return null;
        try (ZipFile zip = new ZipFile(moduleApk)) {
            ZipEntry entry = zip.getEntry(profile.scriptPath);
            if (entry == null) return null;
            try (InputStream stream = zip.getInputStream(entry)) {
                return readScriptExpression(stream);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private JSONObject createScriptCommand(String operation, DisplayConfig snapshot) {
        try {
            JSONObject value =
                    new JSONObject()
                            .put("op", operation)
                            .put("nativeSession", nativeSession)
                            .put("route", identity.documentRoute)
                            .put("profile", identity.profile.key)
                            .put("revision", Long.toString(snapshot.revision));
            if (nonce != null) value.put("nonce", nonce);
            if ("apply".equals(operation)) {
                JSONObject amounts = new JSONObject();
                for (DisplayConfig.Kind kind : DisplayConfig.Kind.values())
                    amounts.put(kind.key(), snapshot.formatted(kind));
                value.put("amounts", amounts);
            }
            return value;
        } catch (Exception error) {
            throw new IllegalStateException("command-unavailable");
        }
    }

    private void evaluateScriptCommand(JSONObject command, String phase) {
        try {
            ClassLoader loader = view.getClass().getClassLoader();
            Class<?> callbackType =
                    Class.forName("com.alipay.mywebview.sdk.ValueCallback", false, loader);
            Method method =
                    view.getClass().getMethod("evaluateJavascript", String.class, callbackType);
            long expectedGeneration = generation;
            WebPageIdentity expected = identity;
            View expectedView = view;
            String expectedRevision = command.optString("revision");
            if ("apply".equals(phase)) {
                highestIssuedRevision =
                        Math.max(highestIssuedRevision, Long.parseLong(expectedRevision));
            }
            Object callback =
                    phase == null
                            ? null
                            : Proxy.newProxyInstance(
                                    loader,
                                    new Class<?>[] {callbackType},
                                    (proxy, target, args) -> {
                                        if (target.getDeclaringClass() == Object.class) {
                                            return switch (target.getName()) {
                                                case "hashCode" -> System.identityHashCode(proxy);
                                                case "equals" ->
                                                        args != null
                                                                && args.length == 1
                                                                && proxy == args[0];
                                                default -> "DisplaySessionCallback";
                                            };
                                        }
                                        if ("onReceiveValue".equals(target.getName())) {
                                            Object result =
                                                    args != null && args.length == 1
                                                            ? args[0]
                                                            : null;
                                            try {
                                                main.post(
                                                        () ->
                                                                runSafely(
                                                                        () ->
                                                                                handleScriptReply(
                                                                                        proxy,
                                                                                        result,
                                                                                        phase,
                                                                                        expectedGeneration,
                                                                                        expected,
                                                                                        expectedView,
                                                                                        expectedRevision)));
                                            } catch (RuntimeException | LinkageError ignored) {
                                            }
                                        }
                                        return null;
                                    });
            if (phase != null) pendingCallback = callback;
            method.invoke(view, "(" + script + ")(" + command + ")", callback);
            if (phase != null) {
                main.postDelayed(
                        () ->
                                runSafely(
                                        () -> {
                                            if (generation == expectedGeneration
                                                    && pendingCallback == callback) {
                                                pendingCallback = null;
                                                traceStatus(
                                                        "status".equals(phase)
                                                                ? "diagnostic-timeout"
                                                                : "callback-timeout");
                                                if (refreshAfterCallback) {
                                                    refreshAfterCallback = false;
                                                    schedule();
                                                } else if (!"status".equals(phase))
                                                    scheduleInspectionRetry();
                                            }
                                        }),
                        3000L);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            pendingCallback = null;
            traceStatus("evaluation-unavailable");
        }
    }

    private void handleScriptReply(
            Object callback,
            Object raw,
            String phase,
            long expectedGeneration,
            WebPageIdentity expected,
            View expectedView,
            String expectedRevision) {
        if (closed || pendingCallback != callback || generation != expectedGeneration) return;
        pendingCallback = null;
        if (!foreground) {
            refreshAfterCallback = false;
            return;
        }
        boolean refreshRequested = refreshAfterCallback;
        refreshAfterCallback = false;
        if (view != expectedView
                || !view.isAttachedToWindow()
                || view.getRootView() != getActivityRoot()
                || !expected.same(WebPageIdentity.capture(activity, view))) {
            retireDocument(false);
            schedule();
            return;
        }
        if (refreshRequested) schedule();
        try {
            if (!(raw instanceof String value) || value.length() > 4096)
                throw new IllegalArgumentException();
            Object parsed = new JSONTokener(value).nextValue();
            if (parsed instanceof String inner) parsed = new JSONTokener(inner).nextValue();
            if (!(parsed instanceof JSONObject reply)) throw new IllegalArgumentException();
            String status = reply.optString("status");
            if (!STATUSES.contains(status)) throw new IllegalArgumentException();
            int targets = reply.optInt("targets", -1);
            int applied = reply.optInt("applied", -1);
            int hidden = reply.optInt("hidden", -1);
            int waiting = reply.optInt("waiting", -1);
            int unknown = reply.optInt("unknown", -1);
            int targetCount = expected.profile.targetCount;
            if (targets < 0
                    || targets > targetCount
                    || applied < 0
                    || hidden < 0
                    || waiting < 0
                    || unknown < 0
                    || targets != applied + hidden + waiting
                    || unknown != targetCount - targets) throw new IllegalArgumentException();
            if ("status".equals(phase)) {
                traceApplyResult(status, applied, hidden, waiting, unknown);
                traceFundState(reply);
                DisplayConfig current = config.get();
                if (!current.enabled || !Long.toString(current.revision).equals(expectedRevision))
                    schedule();
                return;
            }
            if ("prepare".equals(phase) && "prepared".equals(status)) {
                String token = reply.optString("nonce");
                if (!token.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException();
                nonce = token;
                retries = 0;
                scheduleInspectionNow();
            } else {
                traceApplyResult(status, applied, hidden, waiting, unknown);
                if ("apply".equals(phase))
                    acceptDrawGateReply(
                            status, expectedRevision, targets, applied, hidden, waiting, unknown);
                if ("apply".equals(phase)
                        && documentProbeGeneration != generation
                        && config.get().diagnostics) {
                    documentProbeGeneration = generation;
                    long expectedProbeGeneration = generation;
                    main.postDelayed(
                            () ->
                                    runSafely(
                                            () -> {
                                                if (generation == expectedProbeGeneration
                                                        && identity != null
                                                        && view != null
                                                        && identity.same(
                                                                WebPageIdentity.capture(
                                                                        activity, view)))
                                                    documentProbeReader.accept(view);
                                            }),
                            diagnosticToken,
                            1500L);
                }
                if ("apply".equals(phase)) scheduleFundDiagnostics();
                if ("stale-session".equals(status)) {
                    recoveryIdentity = expected;
                    leaseRecoveries++;
                    retireDocument(false);
                    retries = 0;
                    if (leaseRecoveries <= 3) scheduleInspectionRetry();
                    else traceStatus("session-recovery-exhausted");
                    return;
                }
                if ("apply".equals(phase)) {
                    recoveryIdentity = null;
                    leaseRecoveries = 0;
                }
                DisplayConfig current = config.get();
                if (!Long.toString(current.revision).equals(expectedRevision) || !current.enabled)
                    schedule();
                else if ("unknown-shape".equals(status) || "waiting-host".equals(status))
                    scheduleInspectionRetry();
            }
        } catch (Exception ignored) {
            traceStatus("callback-invalid");
        }
    }

    private void retireDocument(boolean restoreIfSameDocument) {
        main.removeCallbacksAndMessages(diagnosticToken);
        diagnosticGeneration = -1L;
        generation++;
        pendingCallback = null;
        refreshAfterCallback = false;
        if (nonce != null && identity != null && view != null) {
            try {
                boolean same = identity.same(WebPageIdentity.capture(activity, view));
                DisplayConfig current = config.get();
                JSONObject retirement =
                        createScriptCommand(
                                restoreIfSameDocument && same ? "stop" : "abandon", current);
                // 坏配置会降为revision=0，不能让旧脚本因拒绝低代次而继续修改页面。
                retirement.put(
                        "revision",
                        Long.toString(Math.max(current.revision, highestIssuedRevision)));
                evaluateScriptCommand(retirement, null);
            } catch (Exception | LinkageError ignored) {
            }
        }
        nonce = null;
        identity = null;
        nativeSession = UUID.randomUUID().toString();
        highestIssuedRevision = 0L;
        visualRequest = 0L;
        visualReceived = false;
        if (drawGate != null) drawGate.invalidateProof(SystemClock.uptimeMillis());
    }

    // 绘制门控与真实 SDK visual 回调。

    private boolean isUniqueSdkWebView(View expected) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(getActivityRoot());
        View found = null;
        int visits = 0;
        while (!pending.isEmpty() && visits++ < MAX_VIEWS) {
            View value = pending.removeFirst();
            if (!value.isShown()) continue;
            if (findSdkWebViewClass(value) != null) {
                if (found != null) return false;
                found = value;
            }
            if (value instanceof ViewGroup group) {
                for (int index = 0; index < group.getChildCount(); index++)
                    pending.addLast(group.getChildAt(index));
            }
        }
        return pending.isEmpty() && found == expected;
    }

    private void ensureDrawGate(View candidate, WebPageIdentity current, DisplayConfig snapshot) {
        long now = SystemClock.uptimeMillis();
        if (drawGate == null
                || gateView != candidate
                || gateIdentity == null
                || !gateIdentity.same(current)
                || gateRevision != snapshot.revision
                || foreground && retryGateOnEntry && drawGate.timedOut) {
            clearDrawGate();
            gateView = candidate;
            gateIdentity = current;
            gateRevision = snapshot.revision;
            gateSession = nativeSession;
            drawGate = new WebDrawGate(now);
        } else if (!nativeSession.equals(gateSession)
                || gateNonce != null && !gateNonce.equals(nonce)) {
            drawGate.invalidateProof(now);
            gateSession = nativeSession;
            gateNonce = null;
            visualRequest = 0L;
            visualReceived = false;
        }
        main.removeCallbacks(gateTimeout);
        if (drawGate.suppress(now)) main.postDelayed(gateTimeout, drawGate.remaining(now));
    }

    private void acceptDrawGateReply(
            String status,
            String repliedRevision,
            int targets,
            int applied,
            int hidden,
            int waiting,
            int unknown) {
        if (drawGate == null
                || gateView != view
                || gateIdentity == null
                || identity == null
                || !gateIdentity.same(identity)
                || gateRevision != config.get().revision
                || !Long.toString(gateRevision).equals(repliedRevision)) return;
        if (!"applied".equals(status)
                && !"hidden".equals(status)
                && !"partial".equals(status)
                && !"waiting-host".equals(status)
                && !"unknown-shape".equals(status)) {
            drawGate.fail(WebDrawGate.Failure.INVALID_REPLY);
            reportGate();
            return;
        }
        if (drawGate.reply(
                identity.profile.targetCount,
                targets,
                applied,
                hidden,
                waiting,
                unknown,
                SystemClock.uptimeMillis())) requestVisualConfirmation();
        else if (drawGate.state == WebDrawGate.State.WAIT_APPLY) {
            visualRequest = 0L;
            visualReceived = false;
            gateNonce = null;
            if (drawGate.suppress(SystemClock.uptimeMillis()) && pendingCallback == null)
                scheduleInspectionRetry();
        }
        reportGate();
    }

    private void requestVisualConfirmation() {
        WebDrawGate expectedGate = drawGate;
        long expectedGeneration = generation, request = ++visualSequence;
        gateSession = nativeSession;
        gateNonce = nonce;
        visualRequest = request;
        visualReceived = false;
        try {
            ClassLoader loader = view.getClass().getClassLoader();
            WeakReference<WebDisplaySession> weak = new WeakReference<>(this);
            worker.execute(
                    () -> {
                        // 首次创建会读取模块DEX资源；不得在宿主主线程执行磁盘IO。
                        Object callback =
                                VisualStateCallbackFactory.create(
                                        loader,
                                        value -> {
                                            WebDisplaySession session = weak.get();
                                            if (session == null) return;
                                            long receivedAt = SystemClock.uptimeMillis();
                                            boolean callbackMain =
                                                    Looper.myLooper() == Looper.getMainLooper();
                                            session.main.post(
                                                    () -> {
                                                        session.reportVisualCallback(
                                                                expectedGate,
                                                                expectedGeneration,
                                                                request,
                                                                value,
                                                                receivedAt,
                                                                callbackMain);
                                                        session.runSafely(
                                                                () -> {
                                                                    if (session.drawGate
                                                                                    != expectedGate
                                                                            || session.generation
                                                                                    != expectedGeneration
                                                                            || session.visualRequest
                                                                                    != request
                                                                            || value != request)
                                                                        return;
                                                                    session.visualReceived = true;
                                                                    session
                                                                            .finishVisualConfirmation();
                                                                });
                                                    });
                                        });
                        main.post(
                                () ->
                                        runSafely(
                                                () ->
                                                        invokeVisualStateCallback(
                                                                expectedGate,
                                                                expectedGeneration,
                                                                request,
                                                                loader,
                                                                callback)));
                    });
        } catch (RuntimeException | LinkageError ignored) {
            visualAvailable = false;
            expectedGate.fail(WebDrawGate.Failure.VISUAL_UNAVAILABLE);
            reportGate();
        }
    }

    private void invokeVisualStateCallback(
            WebDrawGate expectedGate,
            long expectedGeneration,
            long request,
            ClassLoader loader,
            Object callback) {
        if (drawGate != expectedGate
                || generation != expectedGeneration
                || visualRequest != request) return;
        if (!expectedGate.suppress(SystemClock.uptimeMillis())) {
            reportGate();
            return;
        }
        DisplayConfig snapshot = config.get();
        WebPageIdentity current = view == null ? null : WebPageIdentity.capture(activity, view);
        if (!snapshot.enabled
                || snapshot.revision != gateRevision
                || view != gateView
                || current == null
                || !current.supported()
                || !gateIdentity.same(current)
                || !nativeSession.equals(gateSession)
                || nonce == null
                || !nonce.equals(gateNonce)) {
            expectedGate.fail(WebDrawGate.Failure.IDENTITY_CHANGED);
            reportGate();
            return;
        }
        try {
            Class<?> callbackType = Class.forName(WEB_VIEW + "$VisualStateCallback", false, loader);
            visualAvailable = callback != null && callbackType.isInstance(callback);
            if (!visualAvailable) {
                expectedGate.fail(WebDrawGate.Failure.VISUAL_UNAVAILABLE);
                reportGate();
                return;
            }
            expectedGate.visualRequestedAt = SystemClock.uptimeMillis();
            view.getClass()
                    .getMethod("postVisualStateCallback", long.class, callbackType)
                    .invoke(view, request, callback);
            expectedGate.visualInvokeReturned = true;
            try {
                if (config.get().diagnostics)
                    Log.i(
                            "AlipayDisplayDiag",
                            "web-visual-request available=true invoked=true"
                                    + " currentGate="
                                    + (drawGate == expectedGate)
                                    + " elapsedMs="
                                    + expectedGate.elapsed(SystemClock.uptimeMillis()));
            } catch (RuntimeException | LinkageError ignored) {
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            visualAvailable = false;
            expectedGate.fail(WebDrawGate.Failure.VISUAL_UNAVAILABLE);
            reportGate();
        }
    }

    private void finishVisualConfirmation() {
        if (!visualReceived || drawGate == null) return;
        DisplayConfig snapshot = config.get();
        WebPageIdentity current =
                gateView == null ? null : WebPageIdentity.capture(activity, gateView);
        if (!foreground && current == null) return;
        if (!snapshot.enabled
                || snapshot.revision != gateRevision
                || gateView != view
                || current == null
                || !current.supported()
                || !gateIdentity.same(current)
                || identity == null
                || !identity.same(current)
                || !nativeSession.equals(gateSession)
                || nonce == null
                || !nonce.equals(gateNonce)) {
            drawGate.fail(WebDrawGate.Failure.IDENTITY_CHANGED);
        } else drawGate.visualReady(SystemClock.uptimeMillis());
        visualReceived = false;
        reportGate();
        if (gateView != null) gateView.invalidate();
    }

    private void clearDrawGate() {
        main.removeCallbacks(gateTimeout);
        View old = gateView;
        drawGate = null;
        gateView = null;
        gateIdentity = null;
        gateRevision = -1L;
        gateSession = null;
        gateNonce = null;
        visualRequest = 0L;
        visualReceived = false;
        retryGateOnEntry = false;
        visualAvailable = false;
        suppressedDraws = 0;
        reportedGateState = null;
        if (old != null) old.invalidate();
    }

    // 门控结果、时序与只读文档诊断。

    /** 诊断开启时只读取既有基金会话六次，不扫描视图树或修改页面。 */
    private void scheduleFundDiagnostics() {
        if (!config.get().diagnostics
                || identity == null
                || identity.profile != WebDisplayProfile.FUND
                || nonce == null
                || diagnosticGeneration == generation) return;
        diagnosticGeneration = generation;
        long expectedGeneration = generation;
        for (long delay : new long[] {1000L, 3000L, 6000L, 10000L, 20000L, 30000L}) {
            main.postDelayed(
                    () ->
                            runSafely(
                                    () -> {
                                        DisplayConfig snapshot = config.get();
                                        if (!snapshot.enabled
                                                || !snapshot.diagnostics
                                                || generation != expectedGeneration
                                                || pendingCallback != null
                                                || identity == null
                                                || nonce == null
                                                || view == null
                                                || identity.profile != WebDisplayProfile.FUND
                                                || scriptProfile != WebDisplayProfile.FUND
                                                || !identity.same(
                                                        WebPageIdentity.capture(activity, view)))
                                            return;
                                        evaluateScriptCommand(
                                                createScriptCommand("status", snapshot), "status");
                                    }),
                    diagnosticToken,
                    delay);
        }
    }

    private void traceFundState(JSONObject reply) {
        if (!config.get().diagnostics
                || identity == null
                || identity.profile != WebDisplayProfile.FUND) return;
        JSONObject debug = reply.optJSONObject("debug");
        if (debug == null) {
            traceStatus("fund-debug-unavailable");
            return;
        }
        StringBuilder fields = new StringBuilder();
        for (String name : FUND_DEBUG_FIELDS) {
            if (!(debug.opt(name) instanceof Boolean)) {
                traceStatus("fund-debug-unavailable");
                return;
            }
            fields.append(' ').append(name).append('=').append(debug.optBoolean(name));
        }
        if (emitted.add("fund-state" + fields)) Log.i("AlipayDisplayDiag", "fund-state" + fields);
    }

    /** 先报告真实SDK回调到达，再做旧代次过滤；所有输出均为固定布尔和时长计数。 */
    private void reportVisualCallback(
            WebDrawGate expectedGate,
            long expectedGeneration,
            long request,
            long receivedRequest,
            long receivedAt,
            boolean callbackMain) {
        if (expectedGate.callbackReports >= 4) return;
        expectedGate.callbackReports++;
        try {
            if (!config.get().diagnostics) return;
            long processedAt = SystemClock.uptimeMillis();
            boolean released =
                    expectedGate.firstReleasedDrawAt >= 0L
                            && receivedAt >= expectedGate.firstReleasedDrawAt;
            Log.i(
                    "AlipayDisplayDiag",
                    "web-visual-callback received=true onTime="
                            + expectedGate.onTime(receivedAt)
                            + " processedOnTime="
                            + expectedGate.onTime(processedAt)
                            + " currentGate="
                            + (drawGate == expectedGate)
                            + " generationCurrent="
                            + (generation == expectedGeneration)
                            + " requestCurrent="
                            + (visualRequest == request && receivedRequest == request)
                            + " callbackMain="
                            + callbackMain
                            + " closed="
                            + closed
                            + " invoked="
                            + expectedGate.visualInvokeReturned
                            + " afterReleasedDraw="
                            + released
                            + " releasedDraws="
                            + expectedGate.releasedDraws
                            + " elapsedMs="
                            + expectedGate.elapsed(receivedAt)
                            + " sinceRequestMs="
                            + elapsedSince(receivedAt, expectedGate.visualRequestedAt)
                            + " sinceReleasedDrawMs="
                            + (released
                                    ? elapsedSince(receivedAt, expectedGate.firstReleasedDrawAt)
                                    : -1L)
                            + " queueDelayMs="
                            + elapsedSince(processedAt, receivedAt));
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void reportGate() {
        if (drawGate == null || reportedGateState == drawGate.state) return;
        reportedGateState = drawGate.state;
        if (drawGate.state == WebDrawGate.State.FAILED)
            gateFailures = Math.min(10000, gateFailures + 1);
        if (drawGate.state == WebDrawGate.State.READY
                || drawGate.state == WebDrawGate.State.FAILED) {
            main.removeCallbacks(gateTimeout);
            if (gateView != null) gateView.invalidate();
        }
        try {
            if (!config.get().diagnostics) return;
            Log.i(
                    "AlipayDisplayDiag",
                    "web-frame-gate state="
                            + drawGate.state.name().toLowerCase(java.util.Locale.ROOT)
                            + " failure="
                            + drawGate.failure.name().toLowerCase(java.util.Locale.ROOT)
                            + " visualAvailable="
                            + visualAvailable
                            + " visualConfirmed="
                            + (drawGate.state == WebDrawGate.State.READY)
                            + " unproven="
                            + (drawGate.state != WebDrawGate.State.READY
                                    || ungatedDraws != 0
                                    || gateFailures != 0)
                            + " suppressedDraws="
                            + suppressedDraws
                            + " ungatedDraws="
                            + ungatedDraws
                            + " failedEntries="
                            + gateFailures);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void noteUngatedDraw() {
        ungatedDraws = Math.min(10000, ungatedDraws + 1);
        try {
            if (ungatedDraws == 1 && config.get().diagnostics) {
                Log.i(
                        "AlipayDisplayDiag",
                        "web-frame-gate state=unproven failure=identity_unavailable"
                                + " visualAvailable=false visualConfirmed=false unproven=true ungatedDraws=1");
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void traceStatus(String status) {
        if (!config.get().diagnostics || !emitted.add(status)) return;
        Log.i("AlipayDisplayDiag", "web-display status=" + status);
    }

    private void traceApplyResult(
            String status, int applied, int hidden, int waiting, int unknown) {
        String key = status + ":" + applied + ":" + hidden + ":" + waiting + ":" + unknown;
        if (!config.get().diagnostics || !emitted.add(key)) return;
        Log.i(
                "AlipayDisplayDiag",
                "web-display status="
                        + status
                        + " applied="
                        + applied
                        + " hidden="
                        + hidden
                        + " waiting="
                        + waiting
                        + " unknown="
                        + unknown);
    }

    // 底层读取与异常边界。

    private View getActivityRoot() {
        return activity.getWindow().getDecorView();
    }

    private static String readScriptExpression(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            if (bytes.size() + length > 96000) throw new IOException("script-too-large");
            bytes.write(buffer, 0, length);
        }
        return bytes.toString(StandardCharsets.UTF_8.name()).trim().replaceFirst(";\\s*$", "");
    }

    private static long elapsedSince(long now, long start) {
        return start < 0L || now < start ? -1L : Math.min(60000L, now - start);
    }

    private void runSafely(Runnable action) {
        if (closed) return;
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            try {
                traceStatus("runtime-unavailable");
            } catch (RuntimeException | LinkageError ignoredLog) {
            }
            try {
                close();
            } catch (RuntimeException | LinkageError ignoredClose) {
            }
        }
    }

    private static Class<?> findSdkWebViewClass(View value) {
        Class<?> type = value.getClass();
        while (type != null) {
            if (WEB_VIEW.equals(type.getName())) return type;
            type = type.getSuperclass();
        }
        return null;
    }
}
