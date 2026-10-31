package io.github.fengying.alipaydisplay;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import io.github.libxposed.api.XposedInterface;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** 仅探测真实document-start时序；不修改DOM、展示脚本或宿主provider的返回列表。 */
final class WebDocumentStartProbe implements AutoCloseable {
    private static final String WEB = "com.alipay.mywebview.sdk.WebView";
    private static final String PROVIDER = "com.alipay.mywebview.sdk.extension.InjectJSProvider";
    private static final String SCRIPT = PROVIDER + "$DocumentStartJavaScript";
    private static final String SYMBOL = "io.github.fengying.alipaydisplay.document-start-probe.v1";
    private final ModuleEntry module;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pendingExpiry = this::expirePendingNavigations;
    private final Supplier<DisplayConfig> configuration;
    private final OwnerProof ownerProof;
    private final Set<Class<?>> observed = Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakHashMap<View, Lease> leases = new WeakHashMap<>();
    private final Policy.PendingNavigations<View> pending = new Policy.PendingNavigations<>();
    private final WeakHashMap<View, Counts> counts = new WeakHashMap<>();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile boolean closed;

    WebDocumentStartProbe(
            ModuleEntry module, Supplier<DisplayConfig> configuration, OwnerProof ownerProof) {
        this.module = module;
        this.configuration = configuration;
        this.ownerProof = ownerProof;
    }

    // 观测入口与生命周期。

    /** 应在宿主首次设置provider之前调用；不主动创建SDK核心或调用其setter。 */
    synchronized boolean observeWebClass(Class<?> type) {
        if (closed || type == null || !WEB.equals(type.getName())) return false;
        synchronized (this) {
            if (observed.contains(type)) return true;
        }
        try {
            Class<?> api = Class.forName(PROVIDER, false, type.getClassLoader());
            Method setter = type.getDeclaredMethod("setInjectJSProvider", api);
            if (!api.isInterface()
                    || api.getMethod("getDocumentStartScripts").getReturnType() != List.class)
                return false;
            XposedInterface.HookHandle handle =
                    module.hook(setter)
                            .intercept(
                                    chain -> {
                                        Object argument = chain.getArg(0), replacement = argument;
                                        try {
                                            if (!closed
                                                    && chain.getThisObject() instanceof View view
                                                    && argument != null
                                                    && api.isInstance(argument)) {
                                                synchronized (this) {
                                                    countsForView(view).setters =
                                                            incrementCappedCount(
                                                                    countsForView(view).setters);
                                                }
                                                Object original = argument;
                                                if (Proxy.isProxyClass(argument.getClass())
                                                        && Proxy.getInvocationHandler(argument)
                                                                instanceof Forwarder own) {
                                                    original = own.original;
                                                }
                                                WeakReference<View> weakView =
                                                        new WeakReference<>(view);
                                                replacement =
                                                        Policy.composePrepared(
                                                                original,
                                                                api,
                                                                () ->
                                                                        prepareDocumentStartAddition(
                                                                                weakView.get(),
                                                                                type
                                                                                        .getClassLoader()));
                                            }
                                        } catch (RuntimeException | LinkageError ignored) {
                                            replacement = argument;
                                        }
                                        // 宿主原调用仅一次；异常不在探针catch中捕获或转换。
                                        return replacement == argument
                                                ? chain.proceed()
                                                : chain.proceed(new Object[] {replacement});
                                    });
            synchronized (this) {
                observed.add(type);
                handles.add(handle);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 仅保存已知路由的短期请求；owner尚未就绪时不注入，留待真实getter重新证明。 */
    void onNavigation(View view, String requestedUrl) {
        if (view == null) return;
        synchronized (this) {
            leases.remove(view);
            pending.remove(view);
            Counts value = countsForView(view);
            value.generation++;
            value.navigations = incrementCappedCount(value.navigations);
            if (!isMainThread()) {
                value.navOffMain = incrementCappedCount(value.navOffMain);
                value.reason = Reason.OFF_MAIN;
                return;
            }
        }
        try {
            if (closed) {
                recordRejection(view, Reason.CLOSED);
                return;
            }
            if (!Policy.knownRoute(requestedUrl)) {
                recordRejection(view, Reason.UNKNOWN_ROUTE);
                return;
            }
            Policy.PendingNavigation request;
            synchronized (this) {
                request = pending.offer(view, requestedUrl, SystemClock.uptimeMillis());
            }
            schedulePendingNavigationExpiry();
            if (request == null) {
                recordRejection(view, Reason.CAPACITY);
                return;
            }
            if (!isDiagnosticsEnabled()) {
                recordRejection(view, Reason.DISABLED);
                return;
            }
            promotePendingNavigation(view);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 可由调用方求值返回的退休脚本；只释放匹配nonce的模块内存，不接触DOM。 */
    String onDocumentChanged(View view) {
        Lease old;
        synchronized (this) {
            old = leases.remove(view);
            pending.remove(view);
            if (view != null) {
                Counts value = countsForView(view);
                value.generation++;
                value.reason = Reason.RETIRED;
            }
        }
        if (isMainThread()) schedulePendingNavigationExpiry();
        if (old == null) return null;
        return "(()=>{const k=Symbol.for("
                + Policy.quote(SYMBOL)
                + "),p=window[k];if(!p||p.nonce!=="
                + Policy.quote(old.nonce)
                + ")return false;p.close();if(window[k]===p)delete window[k];return true;})()";
    }

    /** 调用方仅可向重新验证过的同一View求值；返回脚本不含业务金额或URL。 */
    String readoutScript(View view) {
        Lease lease = findCurrentLease(view);
        if (lease == null) return null;
        return "(()=>{const p=window[Symbol.for("
                + Policy.quote(SYMBOL)
                + ")];return p?p.read("
                + Policy.quote(lease.nonce)
                + "):{nonce:"
                + Policy.quote(lease.nonce)
                + ",executed:false,documentCurrent:false,initialCandidateVisible:false,candidateSeen:false,"
                + "probeBeforeCandidate:false,paintObserved:false,probeBeforePaint:false,firstPaintAlreadyOccurred:false,"
                + "finished:false,expired:false,animationFrames:0,mutationBatches:0,candidateCount:0};})()";
    }

    /** 不记录raw；原文、nonce和URL均不进入结果对象。旧回调返回null。 */
    Result receive(View view, String raw) {
        Lease lease = findCurrentLease(view);
        if (lease == null || raw == null || raw.length() > 4096) return null;
        try {
            Object parsed = new JSONTokener(raw).nextValue();
            if (parsed instanceof String nested) parsed = new JSONTokener(nested).nextValue();
            if (!(parsed instanceof JSONObject object) || !lease.nonce.equals(object.opt("nonce")))
                return null;
            String[] booleans = {
                "executed",
                "documentCurrent",
                "initialCandidateVisible",
                "candidateSeen",
                "probeBeforeCandidate",
                "paintObserved",
                "probeBeforePaint",
                "firstPaintAlreadyOccurred",
                "finished",
                "expired"
            };
            boolean[] flags = new boolean[booleans.length];
            for (int index = 0; index < booleans.length; index++) {
                if (!(object.opt(booleans[index]) instanceof Boolean value)) return null;
                flags[index] = value;
            }
            int frames = Policy.count(object.opt("animationFrames")),
                    batches = Policy.count(object.opt("mutationBatches"));
            int candidates = Policy.count(object.opt("candidateCount"));
            if (frames < 0
                    || batches < 0
                    || candidates < 0
                    || candidates > 4
                    || flags[0] && !flags[1]
                    || flags[4] && (!flags[3] || flags[2])
                    || flags[6] && (!flags[5] || flags[7])) return null;
            if (!flags[0]) {
                for (boolean flag : flags) if (flag) return null;
                if (frames != 0 || batches != 0 || candidates != 0) return null;
            }
            if (findCurrentLease(view) != lease) return null;
            synchronized (this) {
                Counts value = countsForView(view);
                return new Result(
                        flags,
                        frames,
                        batches,
                        candidates,
                        value.setters,
                        value.getters,
                        value.appended,
                        value.offMain);
            }
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    /** 即使没有lease也可取证；只读模块计数，不调用provider或读取当前URL。 */
    String statusLine(View view) {
        boolean enabled;
        try {
            enabled = isDiagnosticsEnabled();
        } catch (RuntimeException | LinkageError ignored) {
            enabled = false;
        }
        synchronized (this) {
            Counts value = counts.get(view);
            if (value == null) value = new Counts();
            Policy.PendingNavigation request = pending.get(view, SystemClock.uptimeMillis());
            return "web-document-start-native closed="
                    + closed
                    + " diagnostics="
                    + enabled
                    + " mainThread="
                    + isMainThread()
                    + " leasePresent="
                    + leases.containsKey(view)
                    + " pendingPresent="
                    + (request != null)
                    + " pendingSlots="
                    + pending.size(SystemClock.uptimeMillis())
                    + " expiredPending="
                    + pending.expired
                    + " reason="
                    + value.reason.name().toLowerCase(Locale.ROOT)
                    + " setterCalls="
                    + value.setters
                    + " getterCalls="
                    + value.getters
                    + " appendedScripts="
                    + value.appended
                    + " offMainGetters="
                    + value.offMain
                    + " navigations="
                    + value.navigations
                    + " navOffMain="
                    + value.navOffMain
                    + " navUnknownRoute="
                    + value.navUnknownRoute
                    + " navDisabled="
                    + value.navDisabled
                    + " navCapacity="
                    + value.navCapacity
                    + " ownerUnavailable="
                    + value.ownerUnavailable
                    + " ownerProfileRejected="
                    + value.ownerProfileRejected
                    + " ownerChanged="
                    + value.ownerChanged
                    + " promoted="
                    + value.promoted
                    + " scriptUnavailable="
                    + value.scriptUnavailable;
        }
    }

    @Override
    public void close() {
        List<XposedInterface.HookHandle> copy;
        synchronized (this) {
            closed = true;
            leases.clear();
            pending.clear();
            counts.clear();
            copy = new ArrayList<>(handles);
            handles.clear();
            observed.clear();
        }
        main.removeCallbacks(pendingExpiry);
        for (XposedInterface.HookHandle handle : copy) {
            try {
                handle.unhook();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    // 追加准备、待定导航与当前租约；追加项仍只由真实 getter 取得。

    private Supplier<Object> prepareDocumentStartAddition(View view, ClassLoader loader) {
        if (view == null || closed) return () -> null;
        long generation;
        synchronized (this) {
            Counts value = countsForView(view);
            value.getters = incrementCappedCount(value.getters);
            if (!isMainThread()) {
                value.offMain = incrementCappedCount(value.offMain);
                return () -> null;
            }
            generation = value.generation;
        }
        return () -> {
            synchronized (this) {
                if (closed || countsForView(view).generation != generation) return null;
            }
            return createDocumentStartScript(view, loader);
        };
    }

    private Object createDocumentStartScript(View view, ClassLoader loader) {
        if (view == null || closed) return null;
        Lease lease = findCurrentLease(view);
        if (lease == null) lease = promotePendingNavigation(view);
        if (lease == null) return null;
        try {
            Class<?> script = Class.forName(SCRIPT, false, loader);
            // 真实SDK TYPE_HEAD_START=2。禁止使用默认通配origin的双参构造。
            Object result =
                    script.getConstructor(String.class, String[].class, int.class)
                            .newInstance(
                                    Policy.script(lease.nonce, lease.route, lease.profile.key),
                                    new String[] {lease.origin},
                                    2);
            if (findCurrentLease(view) != lease) return null;
            synchronized (this) {
                countsForView(view).appended = incrementCappedCount(countsForView(view).appended);
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            recordRejection(view, Reason.SCRIPT_UNAVAILABLE);
            return null;
        }
    }

    private Lease promotePendingNavigation(View view) {
        if (!isMainThread() || view == null) return null;
        Policy.PendingNavigation request;
        synchronized (this) {
            request = pending.get(view, SystemClock.uptimeMillis());
        }
        if (request == null) return null;
        try {
            if (!isDiagnosticsEnabled()) {
                recordRejection(view, Reason.DISABLED);
                return null;
            }
            Owner owner = ownerProof.capture(view, request.requestedUrl);
            if (owner == null) {
                recordRejection(view, Reason.OWNER_UNAVAILABLE);
                return null;
            }
            WebDisplayProfile profile = Policy.profile(owner, request.requestedUrl);
            if (profile == null) {
                recordRejection(view, Reason.OWNER_PROFILE_REJECTED);
                return null;
            }
            synchronized (this) {
                // owner读取可同步重入导航；只能接纳仍在同一请求代次且未过期的pending。
                if (closed || !pending.take(view, request, SystemClock.uptimeMillis())) return null;
                Lease lease =
                        new Lease(
                                owner,
                                request.requestedUrl,
                                Policy.route(request.requestedUrl),
                                Policy.origin(request.requestedUrl),
                                profile);
                leases.put(view, lease);
                Counts value = countsForView(view);
                value.promoted = incrementCappedCount(value.promoted);
                value.reason = Reason.PROMOTED;
                return lease;
            }
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private Lease findCurrentLease(View view) {
        if (!isMainThread() || view == null) return null;
        Lease lease;
        synchronized (this) {
            lease = leases.get(view);
        }
        try {
            if (lease != null
                    && isDiagnosticsEnabled()
                    && lease.owner.same(ownerProof.capture(view, lease.requestedUrl))
                    && Policy.profile(lease.owner, lease.requestedUrl) == lease.profile)
                return lease;
        } catch (RuntimeException | LinkageError ignored) {
        }
        synchronized (this) {
            if (lease != null && leases.get(view) == lease) {
                leases.remove(view);
                recordRejection(view, Reason.OWNER_CHANGED);
            }
        }
        return null;
    }

    private void schedulePendingNavigationExpiry() {
        main.removeCallbacks(pendingExpiry);
        long delay;
        synchronized (this) {
            delay = closed ? -1L : pending.nextDelay(SystemClock.uptimeMillis());
        }
        if (delay >= 0L) main.postDelayed(pendingExpiry, delay);
    }

    private void expirePendingNavigations() {
        if (!closed) schedulePendingNavigationExpiry();
    }

    private synchronized void recordRejection(View view, Reason reason) {
        Counts value = countsForView(view);
        value.reason = reason;
        switch (reason) {
            case UNKNOWN_ROUTE ->
                    value.navUnknownRoute = incrementCappedCount(value.navUnknownRoute);
            case DISABLED -> value.navDisabled = incrementCappedCount(value.navDisabled);
            case CAPACITY -> value.navCapacity = incrementCappedCount(value.navCapacity);
            case OWNER_UNAVAILABLE ->
                    value.ownerUnavailable = incrementCappedCount(value.ownerUnavailable);
            case OWNER_PROFILE_REJECTED ->
                    value.ownerProfileRejected = incrementCappedCount(value.ownerProfileRejected);
            case OWNER_CHANGED -> value.ownerChanged = incrementCappedCount(value.ownerChanged);
            case SCRIPT_UNAVAILABLE ->
                    value.scriptUnavailable = incrementCappedCount(value.scriptUnavailable);
            default -> {}
        }
    }

    // 计数与线程辅助。

    private boolean isDiagnosticsEnabled() {
        DisplayConfig config = configuration.get();
        return !closed && config != null && config.diagnostics;
    }

    private Counts countsForView(View view) {
        Counts result = counts.get(view);
        if (result == null) {
            result = new Counts();
            counts.put(view, result);
        }
        return result;
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static boolean sameValue(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private static int incrementCappedCount(int value) {
        return Math.min(10000, value + 1);
    }

    // 归属、租约、转发与纯策略类型。

    interface OwnerProof {
        /** 每次重新证明此View属于当前App/Page/Render；未知或非当前代次返回null。 */
        Owner capture(View view, String requestedUrl);
    }

    static final class Owner {
        final Object identityToken;
        final String appId, version, activityUri;
        final long hostVersion;

        /** token须在App/Page/Render或导航代次改变时更换；URI只留短期内存。 */
        Owner(
                Object identityToken,
                String appId,
                String version,
                String activityUri,
                long hostVersion) {
            this.identityToken = identityToken;
            this.appId = appId;
            this.version = version;
            this.activityUri = activityUri;
            this.hostVersion = hostVersion;
        }

        boolean same(Owner other) {
            return other != null
                    && identityToken != null
                    && identityToken == other.identityToken
                    && hostVersion == other.hostVersion
                    && sameValue(appId, other.appId)
                    && sameValue(version, other.version)
                    && sameValue(activityUri, other.activityUri);
        }
    }

    private enum Reason {
        NONE,
        CLOSED,
        OFF_MAIN,
        UNKNOWN_ROUTE,
        DISABLED,
        CAPACITY,
        OWNER_UNAVAILABLE,
        OWNER_PROFILE_REJECTED,
        OWNER_CHANGED,
        PROMOTED,
        SCRIPT_UNAVAILABLE,
        RETIRED
    }

    private static final class Counts {
        long generation;
        int setters,
                getters,
                appended,
                offMain,
                navigations,
                navOffMain,
                navUnknownRoute,
                navDisabled,
                navCapacity;
        int ownerUnavailable, ownerProfileRejected, ownerChanged, promoted, scriptUnavailable;
        Reason reason = Reason.NONE;
    }

    private static final class Lease {
        final Owner owner;
        final String requestedUrl,
                route,
                origin,
                nonce = UUID.randomUUID().toString().replace("-", "");
        final WebDisplayProfile profile;

        Lease(
                Owner owner,
                String requestedUrl,
                String route,
                String origin,
                WebDisplayProfile profile) {
            this.owner = owner;
            this.requestedUrl = requestedUrl;
            this.route = route;
            this.origin = origin;
            this.profile = profile;
        }
    }

    static final class Result {
        private final boolean[] flags;
        final int animationFrames,
                mutationBatches,
                candidateCount,
                setterCalls,
                getterCalls,
                appendedScripts,
                offMainGetters;

        Result(
                boolean[] flags,
                int frames,
                int batches,
                int candidates,
                int setters,
                int getters,
                int appended,
                int offMain) {
            this.flags = flags.clone();
            animationFrames = frames;
            mutationBatches = batches;
            candidateCount = candidates;
            setterCalls = setters;
            getterCalls = getters;
            appendedScripts = appended;
            offMainGetters = offMain;
        }

        /** 固定字段；候选结构出现早晚不等同于实际屏幕内容或无闪现保证。 */
        String diagnosticLine() {
            return "web-document-start executed="
                    + flags[0]
                    + " documentCurrent="
                    + flags[1]
                    + " initialCandidateVisible="
                    + flags[2]
                    + " candidateSeen="
                    + flags[3]
                    + " probeBeforeCandidate="
                    + flags[4]
                    + " paintObserved="
                    + flags[5]
                    + " probeBeforePaint="
                    + flags[6]
                    + " firstPaintAlreadyOccurred="
                    + flags[7]
                    + " finished="
                    + flags[8]
                    + " expired="
                    + flags[9]
                    + " animationFrames="
                    + animationFrames
                    + " mutationBatches="
                    + mutationBatches
                    + " candidateCount="
                    + candidateCount
                    + " setterCalls="
                    + setterCalls
                    + " getterCalls="
                    + getterCalls
                    + " appendedScripts="
                    + appendedScripts
                    + " offMainGetters="
                    + offMainGetters;
        }
    }

    /** 原getter先执行一次；未授权/追加失败时原结果对象不变，宿主异常原样传播。 */
    private static final class Forwarder implements InvocationHandler {
        final Object original;
        final Supplier<Supplier<Object>> preparation;

        Forwarder(Object original, Supplier<Supplier<Object>> preparation) {
            this.original = original;
            this.preparation = preparation;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean getter =
                    "getDocumentStartScripts".equals(method.getName())
                            && method.getParameterTypes().length == 0;
            Supplier<Object> addition = () -> null;
            if (getter) {
                try {
                    addition = preparation.get();
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            Object result;
            try {
                result = method.invoke(original, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
            if (!getter) return result;
            try {
                Object item = addition.get();
                if (item == null || result != null && !(result instanceof List<?>)) return result;
                if (result instanceof List<?> list && list.size() > 256) return result;
                List<Object> combined = new ArrayList<>();
                if (result instanceof List<?> list) combined.addAll(list);
                combined.add(item);
                return combined;
            } catch (RuntimeException | LinkageError ignored) {
                return result;
            }
        }
    }

    static final class Policy {

        static Object compose(Object original, Class<?> api, Supplier<Object> addition) {
            return composePrepared(original, api, () -> addition);
        }

        static Object composePrepared(
                Object original, Class<?> api, Supplier<Supplier<Object>> preparation) {
            return Proxy.newProxyInstance(
                    api.getClassLoader(),
                    new Class<?>[] {api},
                    new Forwarder(original, preparation));
        }

        static WebDisplayProfile profile(Owner owner, String requested) {
            return owner == null
                            || owner.identityToken == null
                            || owner.hostVersion != 212370L
                            || route(requested) == null
                            || route(owner.activityUri) == null
                    ? null
                    : WebDisplayProfile.match(
                            owner.appId, owner.version, owner.activityUri, requested);
        }

        static boolean knownRoute(String requested) {
            return route(requested) != null && WebDisplayProfile.acceptsDocument(requested);
        }

        static String route(String value) {
            if (value == null || value.length() > 8192) return null;
            try {
                URI uri = new URI(value).normalize();
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || uri.getPort() > 65535
                        || !uri.getHost().matches("[A-Za-z0-9.-]+")) return null;
                String path = uri.getRawPath();
                return "https://"
                        + uri.getHost().toLowerCase(Locale.ROOT)
                        + (uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort())
                        + (path == null || path.isEmpty() ? "/" : path);
            } catch (Exception ignored) {
                return null;
            }
        }

        static String origin(String value) {
            String route = route(value);
            if (route == null) return null;
            return route.substring(0, route.indexOf('/', "https://".length()));
        }

        static int count(Object value) {
            if (!(value instanceof Integer number) || number < 0 || number > 10000) return -1;
            return number;
        }

        static String quote(String value) {
            return "\""
                    + value.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\u2028", "\\u2028")
                            .replace("\u2029", "\\u2029")
                    + "\"";
        }

        static String script(String nonce, String route, String profile) {
            if (!nonce.matches("[A-Za-z0-9_-]{16,128}")
                    || route(route) == null
                    || !("total-assets".equals(profile)
                            || "yuebao".equals(profile)
                            || "fund".equals(profile))) throw new IllegalArgumentException();
            return String.format(
                    Locale.ROOT,
                    """
                    (()=>{'use strict';try{
                    if(window.top!==window)return;
                    const route=location.origin+location.pathname;if(route!==%s)return;
                    const key=Symbol.for(%s),nonce=%s,href=location.href,doc=document;
                    const old=window[key];if(old&&old.nonce===nonce)return;if(old&&typeof old.close==='function')old.close();
                    let observer=null,paintObserver=null,timer=null,raf=null,active=true;
                    const start=performance.now(),profile=%s;
                    const s={nonce,executed:true,documentCurrent:true,initialCandidateVisible:false,candidateSeen:false,
                    probeBeforeCandidate:false,paintObserved:false,probeBeforePaint:false,firstPaintAlreadyOccurred:false,
                    finished:false,expired:false,animationFrames:0,mutationBatches:0,candidateCount:0};
                    function current(){return doc===document&&href===location.href&&window[key]===api;}
                    function close(expired=false){active=false;s.finished=true;s.expired=expired;
                    if(observer)observer.disconnect();if(paintObserver)paintObserver.disconnect();
                    if(timer!==null)clearTimeout(timer);if(raf!==null)cancelAnimationFrame(raf);}
                    function visible(e){if(!e||!e.isConnected)return false;let p=e;
                    for(let i=0;p&&i<64;i++,p=p.parentElement){if(p.hidden||p.getAttribute('aria-hidden')==='true')return false;
                    const c=getComputedStyle(p);if(c.display==='none'||c.visibility==='hidden'||c.visibility==='collapse'||Number(c.opacity)===0)return false;}
                    if(p)return false;
                    const r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
                    function candidates(){const selector=profile==='total-assets'?'#overview-card,.overview-card,#asset-card,.asset-card':
                    profile==='yuebao'?'#amountView':'.assets-all .total-asset';
                    const list=doc.querySelectorAll(selector);let n=0;
                    for(let i=0;i<list.length&&i<32;i++)if(visible(list[i]))n++;
                    return Math.min(n,profile==='total-assets'?4:1);}
                    function scan(initial=false){if(!current()){s.documentCurrent=false;close();return;}
                    const n=candidates();s.candidateCount=Math.max(s.candidateCount,n);
                    if(n>0&&!s.candidateSeen){s.candidateSeen=true;s.initialCandidateVisible=initial;s.probeBeforeCandidate=!initial;}}
                    function paints(entries){for(const e of entries)if(e.name==='first-paint'||e.name==='first-contentful-paint'){
                    if(!s.paintObserved){s.paintObserved=true;s.probeBeforePaint=e.startTime>start;s.firstPaintAlreadyOccurred=e.startTime<=start;}
                    else if(e.startTime<=start){s.probeBeforePaint=false;s.firstPaintAlreadyOccurred=true;}}}
                    const api={nonce,close:()=>close(),read(token){if(token!==nonce)return null;
                    if(!current()){s.documentCurrent=false;close();return null;}return {...s};}};
                    window[key]=api;scan(true);
                    paints(performance.getEntriesByType('paint'));
                    if(typeof PerformanceObserver==='function'){try{paintObserver=new PerformanceObserver(list=>{try{if(!active)return;if(current())paints(list.getEntries());else close();}catch(_){close(true);}});
                    paintObserver.observe({type:'paint',buffered:true});}catch(_){paintObserver=null;}}
                    observer=new MutationObserver(()=>{try{if(!active)return;s.mutationBatches=Math.min(10000,s.mutationBatches+1);scan();
                    if(s.mutationBatches>=512)close(true);}catch(_){close(true);}});
                    observer.observe(doc,{subtree:true,childList:true,attributes:true,attributeFilter:['hidden','class','style','aria-hidden']});
                    function frame(){try{if(!active)return;s.animationFrames++;scan();paints(performance.getEntriesByType('paint'));
                    if(s.animationFrames>=600)close(true);else if(s.candidateSeen&&s.paintObserved&&s.animationFrames>=2)close();
                    else raf=requestAnimationFrame(frame);}catch(_){close(true);}}
                    raf=requestAnimationFrame(frame);timer=setTimeout(()=>close(true),10000);
                    }catch(_){}})()
                    """,
                    quote(route),
                    quote(SYMBOL),
                    quote(nonce),
                    quote(profile));
        }

        static final class PendingNavigation {
            final String requestedUrl;
            final long generation, createdAt;

            PendingNavigation(String requestedUrl, long generation, long createdAt) {
                this.requestedUrl = requestedUrl;
                this.generation = generation;
                this.createdAt = createdAt;
            }

            boolean expired(long now) {
                return now < createdAt || now - createdAt >= 10000L;
            }
        }

        /** 仅短期保存请求身份；调用方另行严格校验owner，队列本身从不授权注入。 */
        static final class PendingNavigations<K> {
            private final WeakHashMap<K, PendingNavigation> entries = new WeakHashMap<>();
            private long generation;
            int expired;

            PendingNavigation offer(K key, String requested, long now) {
                entries.remove(key);
                removeExpiredEntries(now);
                if (key == null || entries.size() >= 8) return null;
                PendingNavigation value = new PendingNavigation(requested, ++generation, now);
                entries.put(key, value);
                return value;
            }

            PendingNavigation get(K key, long now) {
                removeExpiredEntries(now);
                return entries.get(key);
            }

            boolean take(K key, PendingNavigation expected, long now) {
                if (expected == null || get(key, now) != expected) return false;
                entries.remove(key);
                return true;
            }

            void remove(K key) {
                entries.remove(key);
            }

            void clear() {
                entries.clear();
            }

            int size(long now) {
                removeExpiredEntries(now);
                return entries.size();
            }

            long nextDelay(long now) {
                removeExpiredEntries(now);
                long delay = Long.MAX_VALUE;
                for (PendingNavigation value : entries.values())
                    delay = Math.min(delay, 10000L - (now - value.createdAt));
                return delay == Long.MAX_VALUE ? -1L : delay;
            }

            private void removeExpiredEntries(long now) {
                java.util.Iterator<PendingNavigation> iterator = entries.values().iterator();
                while (iterator.hasNext())
                    if (iterator.next().expired(now)) {
                        iterator.remove();
                        expired = incrementCappedCount(expired);
                    }
            }
        }
    }
}
