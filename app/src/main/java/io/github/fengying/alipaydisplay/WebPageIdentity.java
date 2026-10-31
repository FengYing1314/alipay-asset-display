package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.view.View;
import android.view.ViewParent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/** 只保留页面对象、元数据和短期路由；不输出原URL或页面业务值。 */
final class WebPageIdentity {
    final Object app;
    final Object page;
    final String appId;
    final String version;
    final String documentRoute;
    final String documentFingerprint;
    final WebDisplayProfile profile;
    private final Object render;
    private final View renderView;
    private final byte[] activityNavigation;
    private final byte[] documentNavigation;
    private final String activityRoute;

    private WebPageIdentity(
            Object app,
            Object page,
            String appId,
            String version,
            String activityRoute,
            String documentRoute,
            Object render,
            View renderView,
            byte[] activityNavigation,
            byte[] documentNavigation) {
        this.app = app;
        this.page = page;
        this.appId = appId;
        this.version = version;
        this.activityRoute = activityRoute;
        this.documentRoute = documentRoute;
        this.documentFingerprint =
                DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(documentRoute));
        this.profile = WebDisplayProfile.match(appId, version, activityRoute, documentRoute);
        this.render = render;
        this.renderView = renderView;
        this.activityNavigation = activityNavigation;
        this.documentNavigation = documentNavigation;
    }

    static WebPageIdentity capture(Activity activity, View webView) {
        return capture(activity, webView, ignored -> {});
    }

    static WebPageIdentity capture(Activity activity, View webView, Consumer<String> diagnostic) {
        try {
            Object app = activity.getClass().getMethod("getCurrentApp").invoke(activity);
            if (app == null) return null;
            Object page = app.getClass().getMethod("getActivePage").invoke(app);
            Object id = app.getClass().getMethod("getAppId").invoke(app);
            Object version = app.getClass().getMethod("getAppVersion").invoke(app);
            Object activityUri = activity.getClass().getMethod("getCurrentUri").invoke(activity);
            Object committed = webView.getClass().getMethod("getLastCommittedUrl").invoke(webView);
            if (!(committed instanceof String uri) || uri.isEmpty()) {
                committed = webView.getClass().getMethod("getUrl").invoke(webView);
            }
            if (page == null
                    || !(id instanceof String appId)
                    || !(version instanceof String appVersion)
                    || !(activityUri instanceof String route)
                    || !(committed instanceof String document)) return null;
            ClassLoader loader = page.getClass().getClassLoader();
            Class<?> pageApi = Class.forName("com.alibaba.ariver.app.api.Page", false, loader);
            Class<?> renderApi =
                    Class.forName("com.alibaba.ariver.engine.api.Render", false, loader);
            if (!pageApi.isInstance(page)) {
                diagnostic.accept("page-api-unavailable");
                return null;
            }
            Object render = pageApi.getMethod("getRender").invoke(page);
            if (!renderApi.isInstance(render)) {
                diagnostic.accept("render-pending");
                return null;
            }
            Object rendered = renderApi.getMethod("getView").invoke(render);
            if (!(rendered instanceof View renderView)
                    || !isWithinRenderTree(webView, renderView)) {
                diagnostic.accept("render-owner-unproved");
                return null;
            }
            String canonicalDocument = canonicalizeDocumentRoute(document);
            byte[] appNavigation = hashFullNavigation(route);
            byte[] docNavigation = hashFullNavigation(document);
            if (canonicalDocument == null || appNavigation == null || docNavigation == null)
                return null;
            return new WebPageIdentity(
                    app,
                    page,
                    appId,
                    appVersion,
                    route,
                    canonicalDocument,
                    render,
                    renderView,
                    appNavigation,
                    docNavigation);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            try {
                diagnostic.accept("identity-api-unavailable");
            } catch (RuntimeException | LinkageError ignoredDiagnostic) {
            }
            return null;
        }
    }

    boolean supported() {
        return profile != null;
    }

    boolean same(WebPageIdentity other) {
        return other != null
                && app == other.app
                && page == other.page
                && render == other.render
                && renderView == other.renderView
                && appId.equals(other.appId)
                && version.equals(other.version)
                && Arrays.equals(activityNavigation, other.activityNavigation)
                && Arrays.equals(documentNavigation, other.documentNavigation)
                && documentRoute.equals(other.documentRoute);
    }

    private static boolean isWithinRenderTree(View child, View parent) {
        View current = child;
        while (current != null) {
            if (current == parent) return true;
            ViewParent next = current.getParent();
            current = next instanceof View ? (View) next : null;
        }
        return false;
    }

    private static String canonicalizeDocumentRoute(String value) {
        if (value == null || value.length() > 16384) return null;
        try {
            URI uri = new URI(value).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null) return null;
            String path = uri.getRawPath();
            return "https://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + (path == null || path.isEmpty() ? "/" : path);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 完整导航摘要仅在内存比较，查询参数和片段改变也会使异步回调失效。 */
    private static byte[] hashFullNavigation(String value) {
        if (canonicalizeDocumentRoute(value) == null) return null;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }
}
