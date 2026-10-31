package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 主进程的配置与 Activity 会话协调器；具体显示规则由页面会话持有。 */
final class NativeRuntime implements Application.ActivityLifecycleCallbacks {

    private final ModuleEntry module;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readQueued = new AtomicBoolean();
    // 策略不持有Android对象；这里只在主线程接受当前代次的快照。
    private final ConfigReadEpoch configEpoch = new ConfigReadEpoch();
    private final SharedPreferences preferences;
    // SharedPreferences 不保证强引用监听器，因此由运行时持有。
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> requestConfigurationRead();
    private final Set<Class<?>> bindingClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Class<?>> webClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Class<?>> failedWebClasses =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Class<?>> cubeFactories = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Class<?>> cubeDrawClasses =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Activity, NativeDisplaySession> nativeSessions = new IdentityHashMap<>();
    private final Map<Activity, WebDisplaySession> webSessions = new IdentityHashMap<>();
    private final Map<Activity, BalanceCanvasSession> balanceSessions = new IdentityHashMap<>();
    private final Map<Activity, Integer> unprovenCubeDraws = new IdentityHashMap<>();
    private final Map<Activity, NativeFrameAudit> frameAudits = new IdentityHashMap<>();
    private final Set<Activity> renderCandidates =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<View, ActiveRenderIdentity> documentOwners = new IdentityHashMap<>();
    private final Set<String> observationStates = new java.util.HashSet<>();
    private volatile DisplayConfig snapshot = DisplayConfig.disabled();
    private boolean configReady;
    private long hostVersion;
    private final CubeDrawDiagnostics cubeDiagnostics = new CubeDrawDiagnostics(() -> snapshot);
    private final WebDocumentStartProbe documentProbe;
    private ActiveRenderIdentity cubeOwner;
    private View cubeRoot;
    private int cubeAttempts;
    private boolean cubeQueued;
    private boolean balanceSummaryQueued;
    private long cubeLastInspect;
    private final Runnable cubeInspect = this::inspectCubePage;
    private final PageDiagnostics diagnostics =
            new PageDiagnostics(() -> snapshot, this::observeWebClass);
    private Application application;
    private Activity active;
    private NativeDisplaySession session;
    private WebDisplaySession webSession;
    private boolean failed;
    private volatile boolean closed;

    NativeRuntime(ModuleEntry module) {
        this.module = module;
        VisualStateCallbackFactory.setModuleApk(module.getModuleApplicationInfo().sourceDir);
        documentProbe = new WebDocumentStartProbe(module, () -> snapshot, this::findDocumentOwner);
        preferences = module.getRemotePreferences(DisplayConfig.GROUP);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        requestConfigurationRead();
    }

    void attachApplication(Application value) {
        if (application != null || !RuntimePolicy.PACKAGE.equals(value.getPackageName())) return;
        try {
            long version =
                    value.getPackageManager()
                            .getPackageInfo(value.getPackageName(), 0)
                            .getLongVersionCode();
            if (!RuntimePolicy.acceptsVersion(value.getPackageName(), version)) {
                module.log(android.util.Log.INFO, "AlipayDisplay", "当前支付宝版本未适配；保持原样");
                return;
            }
            hostVersion = version;
        } catch (PackageManager.NameNotFoundException error) {
            return;
        }
        application = value;
        value.registerActivityLifecycleCallbacks(this);
        // 仅解析已有SDK类，不创建WebView或SDK核心。
        try {
            observeWebClass(
                    Class.forName(
                            "com.alipay.mywebview.sdk.WebView", false, value.getClassLoader()));
        } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            traceObservationState("web-start-sdk-not-resolved");
        }
        try {
            installCubeHooks(value.getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            traceObservationState("cube-hooks-not-resolved");
        }
    }

    void runSafely(Runnable action) {
        if (failed || closed) return;
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            failed = true;
            closeNativeSessions();
            try {
                diagnostics.close();
            } catch (RuntimeException | LinkageError ignored) {
            }
            closeObservations();
            closeWebSessions();
            // 不输出异常对象，防止其中包含宿主业务文本。
            try {
                module.log(android.util.Log.WARN, "AlipayDisplay", "展示会话异常，已停止；请重新进入应用");
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    void close() {
        closed = true;
        try {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        } catch (RuntimeException | LinkageError ignored) {
        }
        io.shutdown();
        main.removeCallbacksAndMessages(null);
        closeNativeSessions();
        try {
            diagnostics.close();
        } catch (RuntimeException | LinkageError ignored) {
        }
        closeObservations();
        closeWebSessions();
        if (application != null) {
            try {
                application.unregisterActivityLifecycleCallbacks(this);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        application = null;
        active = null;
    }

    // Activity 生命周期

    @Override
    public void onActivityPreCreated(Activity activity, Bundle state) {
        if (!closed && !failed && WebDisplaySession.isCandidate(activity))
            renderCandidates.add(activity);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        if (!closed && !failed && WebDisplaySession.isCandidate(activity))
            renderCandidates.add(activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (!closed && !failed && WebDisplaySession.isCandidate(activity))
            renderCandidates.add(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        runSafely(
                () -> {
                    if (active != null && active != activity) pausePageObservation(active);
                    active = activity;
                    if (WebDisplaySession.isCandidate(activity)) renderCandidates.add(activity);
                    requestConfigurationRead();
                    BalanceCanvasSession retainedBalance = balanceSessions.get(activity);
                    if (retainedBalance != null) invalidateBalanceViews(retainedBalance);
                    invalidateCurrentBalanceViews(activity);
                    if (RuntimePolicy.isWealthActivity(activity.getClass().getName())) {
                        session = nativeSessions.get(activity);
                        if (session == null) {
                            session =
                                    new NativeDisplaySession(
                                            activity.getWindow().getDecorView(),
                                            () -> snapshot,
                                            this::observeBindingClasses,
                                            this::runSafely);
                            nativeSessions.put(activity, session);
                        } else session.schedule();
                        NativeFrameAudit audit = frameAudits.get(activity);
                        if (audit == null) {
                            audit =
                                    new NativeFrameAudit(
                                            activity,
                                            () -> snapshot,
                                            () -> configReady,
                                            view -> {
                                                NativeDisplaySession retained =
                                                        nativeSessions.get(activity);
                                                return retained == null
                                                        ? NativeFrameAudit.FieldState.UNKNOWN
                                                        : retained.frameAuditState(view);
                                            });
                            if (audit.start()) frameAudits.put(activity, audit);
                        } else audit.onResume();
                    }
                    webSession = webSessions.get(activity);
                    if (WebDisplaySession.isCandidate(activity)) {
                        if (webSession == null) {
                            webSession =
                                    new WebDisplaySession(
                                            activity,
                                            () -> snapshot,
                                            this::observeWebClass,
                                            module.getModuleApplicationInfo().sourceDir,
                                            io,
                                            this::readDocumentStartProbe);
                            webSessions.put(activity, webSession);
                        }
                        webSession.onResume();
                        webSession.schedule();
                    }
                    diagnostics.onResume(activity);
                    cubeAttempts = 0;
                    scheduleCubeInspection();
                });
    }

    @Override
    public void onActivityPaused(Activity activity) {
        runSafely(() -> pausePageObservation(activity));
    }

    @Override
    public void onActivityStopped(Activity activity) {
        runSafely(
                () -> {
                    NativeFrameAudit audit = frameAudits.remove(activity);
                    if (audit != null) audit.close();
                    closeNativeSession(activity);
                    // 同文档可能再次显示，保留局部observer直到真实导航或Activity销毁。
                    renderCandidates.remove(activity);
                });
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        runSafely(
                () -> {
                    pausePageObservation(activity);
                    NativeFrameAudit audit = frameAudits.remove(activity);
                    if (audit != null) audit.close();
                    closeNativeSession(activity);
                    closeWebSession(activity);
                    BalanceCanvasSession balance = balanceSessions.remove(activity);
                    if (balance != null) balance.close();
                    unprovenCubeDraws.remove(activity);
                    renderCandidates.remove(activity);
                });
    }

    // 宿主视图事件与绘制入口

    void onTextWritten(TextView view) {
        if (!isMainThread()) return;
        String id = NativeDisplaySession.resourceName(view);
        if (!DisplayTargets.isAmountResource(id) && !DisplayTargets.isTitleResource(id)) return;
        for (NativeFrameAudit audit : frameAudits.values()) audit.onStructureChanged(view);
        NativeDisplaySession target = findNativeSession(view);
        if (target == null) return;
        observeBindingClasses(view);
        target.onHostTextChanged(view);
    }

    void onDescriptionWritten(View view) {
        if (!isMainThread()) return;
        NativeDisplaySession target = findNativeSession(view);
        if (target != null) target.onHostDescriptionChanged(view);
    }

    void onStructureChanged(View view) {
        if (!isMainThread()) return;
        for (NativeFrameAudit audit : frameAudits.values()) audit.onStructureChanged(view);
        NativeDisplaySession target = findNativeSession(view);
        if (target != null) {
            observeBindingClasses(view);
            target.onHostStructureChanged(view);
        }
        if (active == null) return;
        if (webSession != null) webSession.onStructureChanged(view);
        diagnostics.onStructureChanged(view);
        scheduleCubeInspection();
    }

    boolean shouldSuppressNativeDraw(TextView view) {
        if (!isMainThread()) return false;
        NativeDisplaySession target = findNativeSession(view);
        return target != null && target.shouldSuppressDraw(view);
    }

    boolean shouldSuppressWebDraw(View view) {
        if (!isMainThread() || view == null || !snapshot.enabled) return false;
        boolean sdk = false;
        for (Class<?> type = view.getClass();
                type != null && View.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            if ("com.alipay.mywebview.sdk.WebView".equals(type.getName())) {
                sdk = true;
                break;
            }
        }
        if (!sdk) return false;
        for (WebDisplaySession value : webSessions.values())
            if (value.shouldSuppressDraw(view)) return true;
        return false;
    }

    // 配置读取与会话归属

    private void requestConfigurationRead() {
        if (closed) return;
        configEpoch.request();
        if (!readQueued.compareAndSet(false, true)) return;
        io.execute(
                () -> {
                    // 先清标记再取代次；两者之间的新请求也能排入后续读取。
                    readQueued.set(false);
                    long epoch = configEpoch.current();
                    DisplayConfig next = DisplayConfig.read(preferences);
                    main.post(
                            () ->
                                    runSafely(
                                            () -> {
                                                if (closed || !configEpoch.publish(epoch)) return;
                                                snapshot = next;
                                                configReady = true;
                                                for (NativeDisplaySession value :
                                                        nativeSessions.values()) value.schedule();
                                                for (WebDisplaySession value : webSessions.values())
                                                    value.schedule();
                                                for (BalanceCanvasSession value :
                                                        balanceSessions.values())
                                                    invalidateBalanceViews(value);
                                                if (active != null)
                                                    invalidateCurrentBalanceViews(active);
                                                if (active != null) diagnostics.onResume(active);
                                                for (NativeFrameAudit audit :
                                                        frameAudits.values()) {
                                                    if (active != null)
                                                        audit.onStructureChanged(
                                                                active.getWindow().getDecorView());
                                                }
                                                scheduleCubeInspection();
                                            }));
                });
    }

    private boolean isConfigurationReadPending() {
        return configEpoch.pending();
    }

    private NativeDisplaySession findNativeSession(View view) {
        if (view == null) return null;
        for (NativeDisplaySession value : nativeSessions.values()) {
            if (value.owns(view)) return value;
        }
        return null;
    }

    private void closeNativeSession(Activity activity) {
        NativeDisplaySession value = nativeSessions.remove(activity);
        if (value == null) return;
        if (session == value) session = null;
        value.close();
    }

    private void closeNativeSessions() {
        for (NativeDisplaySession value : new ArrayList<>(nativeSessions.values())) {
            try {
                value.close();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        nativeSessions.clear();
        session = null;
    }

    private void closeWebSession(Activity activity) {
        WebDisplaySession value = webSessions.remove(activity);
        if (value == null) return;
        if (webSession == value) webSession = null;
        value.close();
    }

    private void closeWebSessions() {
        for (WebDisplaySession value : new ArrayList<>(webSessions.values())) {
            try {
                value.close();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        webSessions.clear();
        webSession = null;
    }

    private void pausePageObservation(Activity activity) {
        if (active != activity) return;
        NativeFrameAudit audit = frameAudits.get(activity);
        if (audit != null) audit.onPause();
        diagnostics.onPause(activity);
        if (webSession != null) webSession.onPause();
        // onPause后的退出动画仍可能绘制，暂不恢复金额原文。
        session = null;
        webSession = null;
        active = null;
        main.removeCallbacks(cubeInspect);
        cubeQueued = false;
        clearCubeObservation();
        for (View view : new ArrayList<>(documentOwners.keySet())) {
            if (documentOwners.get(view).activity == activity) {
                retireDocumentStartProbe(view);
                documentOwners.remove(view);
            }
        }
    }

    // 宿主拦截注册

    private void observeBindingClasses(View view) {
        View current = view;
        while (current != null) {
            Class<?> type = current.getClass();
            while (type != null && View.class.isAssignableFrom(type)) {
                if (RuntimePolicy.isBindingClass(type.getName())) installBindingHooks(type);
                type = type.getSuperclass();
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
    }

    private void installBindingHooks(Class<?> type) {
        if (!bindingClasses.add(type)) return;
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getReturnType() != void.class) continue;
                String name = method.getName();
                boolean setData = "setData".equals(name);
                if (!setData && !"M".equals(name) && !"O".equals(name)) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean header =
                        setData
                                && parameters.length == 2
                                && "com.alipay.android.render.engine.asset.model.AssetsCardModel"
                                        .equals(parameters[0].getName())
                                && parameters[1] == boolean.class;
                boolean card =
                        setData
                                && (parameters.length == 4 || parameters.length == 5)
                                && "com.alipay.android.render.engine.asset.model.AssetProfilesModel"
                                        .equals(parameters[0].getName())
                                && parameters[1] == boolean.class
                                && parameters[2] == int.class
                                && parameters[3] == int.class
                                && (parameters.length == 4 || parameters[4] == int.class);
                String[] parameterNames = new String[parameters.length];
                for (int i = 0; i < parameters.length; i++)
                    parameterNames[i] = parameters[i].getName();
                boolean delegate =
                        BindingHookPolicy.isHeaderDelegate(
                                method.getDeclaringClass().getName(),
                                name,
                                method.getReturnType().getName(),
                                parameterNames);
                if (!header && !card && !delegate) continue;
                module.hook(method)
                        .intercept(
                                chain -> {
                                    View scope = (View) chain.getThisObject();
                                    NativeDisplaySession bindingSession =
                                            isMainThread() ? findNativeSession(scope) : null;
                                    // 已核实此参数 true 为隐藏金额；不读取或修改模型内容。
                                    boolean hidden = Boolean.TRUE.equals(chain.getArg(1));
                                    runSafely(
                                            () -> {
                                                if (bindingSession != null)
                                                    bindingSession.onHostBindingStarted(
                                                            scope, hidden);
                                            });
                                    try {
                                        return chain.proceed();
                                    } finally {
                                        runSafely(
                                                () -> {
                                                    if (bindingSession != null)
                                                        bindingSession.onHostBindingFinished(scope);
                                                    onStructureChanged(scope);
                                                });
                                    }
                                });
            }
        } catch (RuntimeException | LinkageError error) {
            // 延迟类加载失败不会扩展到未验证的模型层。
            module.log(android.util.Log.WARN, "AlipayDisplay", "资产控件绑定钩子不可用；停止原生金额显示");
            throw error;
        }
    }

    private void observeWebClass(Class<?> type) {
        if (failedWebClasses.contains(type))
            throw new IllegalStateException("web-hooks-unavailable");
        if (!"com.alipay.mywebview.sdk.WebView".equals(type.getName()) || !webClasses.add(type))
            return;
        if (!documentProbe.observeWebClass(type))
            traceObservationState("web-start-provider-hook-unavailable");
        try {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] parameters = method.getParameterTypes();
                boolean urlNavigation =
                        "loadUrl".equals(name)
                                && (parameters.length == 1 || parameters.length == 2)
                                && parameters[0] == String.class
                                && (parameters.length == 1 || parameters[1] == java.util.Map.class);
                boolean dataNavigation =
                        ("loadData".equals(name) && parameters.length == 3
                                        || "loadDataWithBaseURL".equals(name)
                                                && parameters.length == 5)
                                && java.util.Arrays.stream(parameters)
                                        .allMatch(value -> value == String.class);
                boolean navigation = urlNavigation || dataNavigation;
                boolean lifecycle =
                        parameters.length == 0
                                && ("onPause".equals(name)
                                        || "onResume".equals(name)
                                        || "destroy".equals(name));
                if (!navigation && !lifecycle) continue;
                module.hook(method)
                        .intercept(
                                chain -> {
                                    View view = (View) chain.getThisObject();
                                    boolean scriptUrl =
                                            urlNavigation
                                                    && chain.getArg(0) instanceof String url
                                                    && url.trim()
                                                            .regionMatches(
                                                                    true, 0, "javascript:", 0, 11);
                                    if (!"onResume".equals(name)
                                            && !"onPause".equals(name)
                                            && !scriptUrl) {
                                        runSafely(
                                                () -> {
                                                    if (!isMainThread()) return;
                                                    retireDocumentStartProbe(view);
                                                    documentOwners.remove(view);
                                                    if (navigation) {
                                                        traceObservationState(
                                                                "web-navigation-" + name);
                                                        // loadData首参是HTML，绝不读取；仅有真实base URL时登记导航。
                                                        if (!"loadData".equals(name)
                                                                && chain.getArg(0)
                                                                        instanceof String url)
                                                            documentProbe.onNavigation(view, url);
                                                    }
                                                    diagnostics.onDocumentChanged(view);
                                                    for (WebDisplaySession value :
                                                            webSessions.values())
                                                        value.onDocumentChanged(view, false);
                                                });
                                    }
                                    Object result = chain.proceed();
                                    if ("onResume".equals(name)) {
                                        runSafely(
                                                () -> {
                                                    if (!isMainThread()) return;
                                                    diagnostics.onDocumentChanged(view);
                                                    if (webSession != null) webSession.schedule();
                                                });
                                    }
                                    return result;
                                });
            }
        } catch (RuntimeException | LinkageError ignored) {
            failedWebClasses.add(type);
            try {
                diagnostics.close();
            } catch (RuntimeException | LinkageError ignoredClose) {
            }
            throw new IllegalStateException("web-hooks-unavailable");
        }
    }

    private void installCubeHooks(ClassLoader loader) throws ReflectiveOperationException {
        installCubeDrawHook(loader);
        Class<?> factory =
                Class.forName(
                        "com.antfin.cube.cubecore.component.CKComponentFactory", false, loader);
        if (cubeFactories.contains(factory)) return;
        Class<?> accessibility =
                Class.forName(
                        "com.antfin.cube.cubecore.accessibility.CKAccessibility", false, loader);
        Class<?> canvas = Class.forName("com.antfin.cube.platform.context.CKCanvas", false, loader);
        Method metadata = factory.getDeclaredMethod("setAccessibility", View.class, accessibility);
        Method flush =
                factory.getDeclaredMethod(
                        "flushViewDrawCommands",
                        View.class,
                        byte[].class,
                        int.class,
                        int.class,
                        long.class,
                        int.class,
                        String.class,
                        float.class,
                        float.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class);
        Method bind =
                factory.getDeclaredMethod(
                        "bindCanvas",
                        View.class,
                        canvas,
                        int.class,
                        int.class,
                        long.class,
                        int.class,
                        String.class,
                        float.class,
                        float.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class);
        Method update =
                factory.getDeclaredMethod(
                        "updateCanvas",
                        View.class,
                        Object.class,
                        long.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class);
        cubeFactories.add(factory);
        module.hook(metadata)
                .intercept(
                        chain -> {
                            Object result = chain.proceed();
                            if (!closed && !failed && snapshot.diagnostics) {
                                try {
                                    View view = (View) chain.getArg(0);
                                    prepareCubeObservation(view);
                                    cubeDiagnostics.onAccessibility(view, chain.getArg(1));
                                } catch (RuntimeException | LinkageError ignored) {
                                    traceObservationState("cube-metadata-unavailable");
                                }
                            }
                            return result;
                        });
        for (Method method : new Method[] {flush, bind, update}) {
            String stage = method == flush ? "flush" : method == bind ? "bind" : "update";
            module.hook(method)
                    .intercept(
                            chain -> {
                                if (!closed
                                        && !failed
                                        && !"flush".equals(stage)
                                        && isMainThread()) {
                                    try {
                                        View view = (View) chain.getArg(0);
                                        BalanceCanvasSession balance =
                                                findOrCreateBalanceSession(view);
                                        if (balance != null)
                                            balance.inspectShape(view, chain.getArg(1));
                                    } catch (RuntimeException | LinkageError ignored) {
                                        traceObservationState("balance-shape-unavailable");
                                    }
                                }
                                if (!closed && !failed && snapshot.diagnostics) {
                                    try {
                                        View view = (View) chain.getArg(0);
                                        prepareCubeObservation(view);
                                        cubeDiagnostics.onCanvas(
                                                stage,
                                                view,
                                                "flush".equals(stage) ? null : chain.getArg(1));
                                    } catch (RuntimeException | LinkageError ignored) {
                                        traceObservationState("cube-canvas-unavailable");
                                    }
                                }
                                return chain.proceed();
                            });
        }
    }

    private void installCubeDrawHook(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type =
                Class.forName("com.antfin.cube.cubecore.component.widget.CKPView", false, loader);
        if (cubeDrawClasses.contains(type)) return;
        Method draw = type.getDeclaredMethod("onDraw", android.graphics.Canvas.class);
        cubeDrawClasses.add(type);
        module.hook(draw)
                .intercept(
                        chain -> {
                            BalanceCanvasSession.DrawLease lease = null;
                            BalanceCanvasSession balance = null;
                            android.graphics.Canvas fencedCanvas = null;
                            int savedLayer = -1;
                            boolean skipKnownDraw = false;
                            if (!closed && !failed && isMainThread()) {
                                try {
                                    View view = (View) chain.getThisObject();
                                    balance = findOrCreateBalanceSession(view);
                                    if (balance != null) {
                                        lease = balance.beforeDraw(view);
                                        if (lease == null
                                                && balance.shouldSuppressDraw(view)
                                                && view.getWidth() > 0
                                                && view.getHeight() > 0
                                                && chain.getArg(0)
                                                        instanceof android.graphics.Canvas canvas) {
                                            // 已知主金额无法创建副本时，保持宿主绘制调度并暂缓这个精确画布的可见输出。
                                            try {
                                                savedLayer =
                                                        canvas.saveLayerAlpha(
                                                                0f,
                                                                0f,
                                                                view.getWidth(),
                                                                view.getHeight(),
                                                                0);
                                                fencedCanvas = canvas;
                                            } catch (RuntimeException
                                                    | LinkageError layerUnavailable) {
                                                try {
                                                    savedLayer = canvas.save();
                                                    fencedCanvas = canvas;
                                                    canvas.clipRect(0, 0, 0, 0);
                                                } catch (RuntimeException
                                                        | LinkageError clipUnavailable) {
                                                    // 两种Canvas保护均失败时，不把已确认主金额交给未受保护的绘制。
                                                    skipKnownDraw = true;
                                                }
                                            }
                                        }
                                    } else if (snapshot.diagnostics) {
                                        for (Activity activity : renderCandidates) {
                                            if (ActiveRenderIdentity.inside(
                                                    view, activity.getWindow().getDecorView())) {
                                                int count =
                                                        unprovenCubeDraws.getOrDefault(activity, 0);
                                                unprovenCubeDraws.put(
                                                        activity, Math.min(10000, count + 1));
                                            }
                                        }
                                    }
                                } catch (RuntimeException | LinkageError ignored) {
                                    traceObservationState("balance-draw-unavailable");
                                }
                            }
                            boolean completed = false;
                            try {
                                if (skipKnownDraw) return null;
                                Object result = chain.proceed();
                                completed = true;
                                if (lease != null) lease.markDrawCompleted();
                                return result;
                            } finally {
                                try {
                                    if (fencedCanvas != null) {
                                        try {
                                            fencedCanvas.restoreToCount(savedLayer);
                                        } catch (RuntimeException | LinkageError ignored) {
                                            traceObservationState(
                                                    "balance-canvas-restore-unavailable");
                                        }
                                    }
                                } finally {
                                    try {
                                        if ((fencedCanvas != null || skipKnownDraw)
                                                && balance != null) {
                                            try {
                                                balance.markDrawSuppressed(completed);
                                            } catch (RuntimeException | LinkageError ignored) {
                                                traceObservationState(
                                                        "balance-draw-audit-unavailable");
                                            }
                                        }
                                    } finally {
                                        if (lease != null) {
                                            try {
                                                lease.close();
                                            } catch (RuntimeException | LinkageError ignored) {
                                                traceObservationState(
                                                        "balance-lease-close-unavailable");
                                            }
                                        }
                                    }
                                }
                            }
                        });
    }

    // Cube 页面观察与重画

    private void scheduleCubeInspection() {
        if (closed
                || failed
                || !snapshot.diagnostics
                || active == null
                || cubeQueued
                || !WebDisplaySession.isCandidate(active)) return;
        cubeQueued = true;
        main.postDelayed(cubeInspect, 100L);
    }

    private void inspectCubePage() {
        cubeQueued = false;
        if (!isMainThread() || closed || failed || !snapshot.diagnostics || active == null) return;
        cubeLastInspect = SystemClock.uptimeMillis();
        try {
            ActiveRenderIdentity owner = ActiveRenderIdentity.capture(active);
            if (owner == null) {
                clearCubeObservation();
                traceObservationState("cube-owner-pending");
                scheduleCubeInspectionRetry();
                return;
            }
            if (!"77700300".equals(owner.appId)) {
                clearCubeObservation();
                return;
            }
            View ng = owner.uniqueDescendant("com.antfin.cube.cubebridge.view.CubeViewNgImpl");
            if (ng == null
                    || !ActiveRenderIdentity.inside(
                            owner.renderView, active.getWindow().getDecorView())) {
                clearCubeObservation();
                traceObservationState("cube-root-pending");
                scheduleCubeInspectionRetry();
                return;
            }
            cubeOwner = owner;
            cubeRoot = ng;
            cubeDiagnostics.bind(
                    owner.activity,
                    owner.renderView,
                    ng,
                    owner.appId,
                    hostVersion,
                    owner.version,
                    DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(owner.activityUri)),
                    () ->
                            active == owner.activity
                                    && owner.same(ActiveRenderIdentity.capture(owner.activity))
                                    && ActiveRenderIdentity.inside(ng, owner.renderView));
            installCubeHooks(ng.getClass().getClassLoader());
            cubeDiagnostics.sampleExisting();
            scheduleBalanceSummary();
            String proof =
                    DiagnosticPolicy.versionFingerprint(owner.version)
                            + ":"
                            + DiagnosticPolicy.routeFingerprint(
                                    DiagnosticPolicy.routeKey(owner.activityUri));
            if (observationStates.add("cube-owner:" + proof)) {
                Log.i(
                        "AlipayDisplayDiag",
                        "cube-owner appId="
                                + owner.appId
                                + " versionFingerprint="
                                + DiagnosticPolicy.versionFingerprint(owner.version)
                                + " routeFingerprint="
                                + DiagnosticPolicy.routeFingerprint(
                                        DiagnosticPolicy.routeKey(owner.activityUri))
                                + " ownerVerified=true");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            clearCubeObservation();
            traceObservationState("cube-inspection-unavailable");
        }
    }

    private void scheduleCubeInspectionRetry() {
        if (cubeAttempts++ < 6) scheduleCubeInspection();
    }

    private void scheduleBalanceSummary() {
        if (balanceSummaryQueued || !snapshot.diagnostics || cubeOwner == null) return;
        ActiveRenderIdentity expected = cubeOwner;
        balanceSummaryQueued = true;
        main.postDelayed(
                () -> {
                    balanceSummaryQueued = false;
                    if (closed
                            || failed
                            || !snapshot.diagnostics
                            || active != expected.activity
                            || !expected.same(ActiveRenderIdentity.capture(active))) return;
                    try {
                        BalanceCanvasSession value = balanceSessions.get(active);
                        if (value != null) Log.i("AlipayDisplayDiag", value.diagnosticSummary());
                    } catch (RuntimeException | LinkageError ignored) {
                        traceObservationState("balance-summary-unavailable");
                    }
                },
                1500L);
    }

    private void clearCubeObservation() {
        cubeDiagnostics.clear();
        cubeOwner = null;
        cubeRoot = null;
    }

    private void prepareCubeObservation(View view) {
        if (!isMainThread() || closed || failed || !snapshot.diagnostics || active == null) return;
        if (cubeOwner != null
                && cubeOwner.owns(view)
                && cubeOwner.same(ActiveRenderIdentity.capture(active))) return;
        if (SystemClock.uptimeMillis() - cubeLastInspect >= 100L) inspectCubePage();
        else scheduleCubeInspection();
    }

    private BalanceCanvasSession findOrCreateBalanceSession(View view) {
        if (!isMainThread()
                || closed
                || failed
                || view == null
                || !snapshot.enabled && !snapshot.diagnostics && !isConfigurationReadPending())
            return null;
        for (BalanceCanvasSession value : balanceSessions.values()) {
            if (value.isCurrent() && value.owns(view)) return value;
        }
        for (Activity activity : renderCandidates) {
            ActiveRenderIdentity owner = ActiveRenderIdentity.capture(activity);
            if (owner == null
                    || !owner.owns(view)
                    || !CubeDrawDiagnostics.Policy.discovery(
                            owner.appId,
                            hostVersion,
                            owner.version,
                            DiagnosticPolicy.routeFingerprint(
                                    DiagnosticPolicy.routeKey(owner.activityUri)))) continue;
            View ng = owner.uniqueDescendant("com.antfin.cube.cubebridge.view.CubeViewNgImpl");
            if (ng == null
                    || !ActiveRenderIdentity.inside(view, ng)
                    || !ActiveRenderIdentity.inside(
                            owner.renderView, activity.getWindow().getDecorView())) continue;
            BalanceCanvasSession previous = balanceSessions.remove(activity);
            if (previous != null) previous.close();
            BalanceCanvasSession created =
                    new BalanceCanvasSession(
                            () -> snapshot,
                            ng,
                            () ->
                                    owner.same(ActiveRenderIdentity.capture(activity))
                                            && ActiveRenderIdentity.inside(ng, owner.renderView)
                                            && ActiveRenderIdentity.inside(
                                                    owner.renderView,
                                                    activity.getWindow().getDecorView()),
                            this::isConfigurationReadPending);
            balanceSessions.put(activity, created);
            if (snapshot.diagnostics)
                Log.i(
                        "AlipayDisplayDiag",
                        "balance-owner initialUnprovenDraws="
                                + unprovenCubeDraws.getOrDefault(activity, 0));
            return created;
        }
        return null;
    }

    private void invalidateBalanceViews(BalanceCanvasSession session) {
        for (Activity activity : balanceSessions.keySet()) {
            if (balanceSessions.get(activity) != session) continue;
            invalidateBalanceDescendants(activity.getWindow().getDecorView(), session, false);
        }
    }

    /** 配置/回页时重新确认当前Ng；不依赖旧session或显示开关，不读取绘制文字。 */
    private void invalidateCurrentBalanceViews(Activity activity) {
        View ng = null;
        ActiveRenderIdentity owner = ActiveRenderIdentity.capture(activity);
        if (owner != null
                && CubeDrawDiagnostics.Policy.discovery(
                        owner.appId,
                        hostVersion,
                        owner.version,
                        DiagnosticPolicy.routeFingerprint(
                                DiagnosticPolicy.routeKey(owner.activityUri)))) {
            View current = owner.uniqueDescendant("com.antfin.cube.cubebridge.view.CubeViewNgImpl");
            if (current != null
                    && ActiveRenderIdentity.inside(current, owner.renderView)
                    && ActiveRenderIdentity.inside(
                            owner.renderView, activity.getWindow().getDecorView())
                    && owner.same(ActiveRenderIdentity.capture(activity))) ng = current;
        }
        invalidateBalanceDescendants(ng, null, true);
    }

    private void invalidateBalanceDescendants(
            View start, BalanceCanvasSession retained, boolean current) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        if (start != null) pending.add(start);
        int visited = 0, owned = 0, matched = 0, exact = 0, subclass = 0;
        String pview = "com.antfin.cube.cubecore.component.widget.CKPView";
        while (!pending.isEmpty() && visited < 2048) {
            View view = pending.removeFirst();
            visited++;
            if (current || retained.owns(view)) {
                owned++;
                for (Class<?> type = view.getClass();
                        type != null && View.class.isAssignableFrom(type);
                        type = type.getSuperclass()) {
                    if (!pview.equals(type.getName())) continue;
                    matched++;
                    if (pview.equals(view.getClass().getName())) exact++;
                    else subclass++;
                    view.invalidate();
                    break;
                }
            }
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++)
                    pending.addLast(group.getChildAt(i));
            }
        }
        if (snapshot.diagnostics)
            Log.i(
                    "AlipayDisplayDiag",
                    "balance-dirty current="
                            + current
                            + " retained="
                            + !current
                            + " scoped="
                            + (start != null)
                            + " visited="
                            + visited
                            + " owned="
                            + owned
                            + " matched="
                            + matched
                            + " exact="
                            + exact
                            + " subclass="
                            + subclass
                            + " capped="
                            + !pending.isEmpty());
    }

    private void closeObservations() {
        main.removeCallbacks(cubeInspect);
        cubeQueued = false;
        for (NativeFrameAudit audit : new ArrayList<>(frameAudits.values())) audit.close();
        frameAudits.clear();
        clearCubeObservation();
        for (BalanceCanvasSession value : balanceSessions.values()) value.close();
        balanceSessions.clear();
        unprovenCubeDraws.clear();
        for (View view : new ArrayList<>(documentOwners.keySet())) retireDocumentStartProbe(view);
        documentProbe.close();
        documentOwners.clear();
        renderCandidates.clear();
    }

    // 文档起始探针与运行时辅助

    private WebDocumentStartProbe.Owner findDocumentOwner(View view, String requestedUrl) {
        if (!isMainThread() || closed || failed || application == null || view == null) return null;
        for (Activity activity : renderCandidates) {
            ActiveRenderIdentity owner = ActiveRenderIdentity.capture(activity);
            if (owner == null
                    || !owner.owns(view)
                    || WebDisplayProfile.match(
                                    owner.appId, owner.version, owner.activityUri, requestedUrl)
                            == null) continue;
            ActiveRenderIdentity previous = documentOwners.get(view);
            if (previous == null || !previous.same(owner)) {
                if (previous == null && documentOwners.size() >= 8) return null;
                documentOwners.put(view, owner);
                previous = owner;
            }
            return new WebDocumentStartProbe.Owner(
                    previous, owner.appId, owner.version, owner.activityUri, hostVersion);
        }
        return null;
    }

    private void readDocumentStartProbe(View view) {
        if (!isMainThread() || closed || failed || !snapshot.diagnostics || view == null) return;
        String script = documentProbe.readoutScript(view);
        if (script == null) {
            Log.i("AlipayDisplayDiag", documentProbe.statusLine(view));
            return;
        }
        try {
            ClassLoader loader = view.getClass().getClassLoader();
            Class<?> callbackType =
                    Class.forName("com.alipay.mywebview.sdk.ValueCallback", false, loader);
            Object callback =
                    Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {callbackType},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class)
                                    return switch (method.getName()) {
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" ->
                                                args != null
                                                        && args.length == 1
                                                        && proxy == args[0];
                                        default -> "DocumentStartProbeCallback";
                                    };
                                if ("onReceiveValue".equals(method.getName())
                                        && args != null
                                        && args.length == 1
                                        && args[0] instanceof String raw) {
                                    main.post(
                                            () -> {
                                                if (closed || failed || !snapshot.diagnostics)
                                                    return;
                                                WebDocumentStartProbe.Result result =
                                                        documentProbe.receive(view, raw);
                                                if (result != null)
                                                    Log.i(
                                                            "AlipayDisplayDiag",
                                                            result.diagnosticLine());
                                            });
                                }
                                return null;
                            });
            view.getClass()
                    .getMethod("evaluateJavascript", String.class, callbackType)
                    .invoke(view, script, callback);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            traceObservationState("web-start-readout-unavailable");
        }
    }

    private void retireDocumentStartProbe(View view) {
        String script = documentProbe.onDocumentChanged(view);
        if (!isMainThread() || script == null || view == null) return;
        try {
            ClassLoader loader = view.getClass().getClassLoader();
            Class<?> callbackType =
                    Class.forName("com.alipay.mywebview.sdk.ValueCallback", false, loader);
            view.getClass()
                    .getMethod("evaluateJavascript", String.class, callbackType)
                    .invoke(view, script, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            traceObservationState("web-start-retirement-unavailable");
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private void traceObservationState(String state) {
        if (snapshot.diagnostics && observationStates.add(state))
            Log.i("AlipayDisplayDiag", "observation=" + state);
    }
}
