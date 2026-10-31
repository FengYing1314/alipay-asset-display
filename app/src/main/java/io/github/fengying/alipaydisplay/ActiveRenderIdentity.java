package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;

/** 只读活动App/Page/Render归属；完整导航只在内存中比较摘要。 */
final class ActiveRenderIdentity {
    final Activity activity;
    final Object app, page, render;
    final View renderView;
    final String appId, version, activityUri;
    private final byte[] navigation;

    private ActiveRenderIdentity(
            Activity activity,
            Object app,
            Object page,
            Object render,
            View renderView,
            String appId,
            String version,
            String activityUri,
            byte[] navigation) {
        this.activity = activity;
        this.app = app;
        this.page = page;
        this.render = render;
        this.renderView = renderView;
        this.appId = appId;
        this.version = version;
        this.activityUri = activityUri;
        this.navigation = navigation;
    }

    static ActiveRenderIdentity capture(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()
                || activity == null
                || activity.isDestroyed()) return null;
        try {
            Object app = activity.getClass().getMethod("getCurrentApp").invoke(activity);
            if (app == null) return null;
            Object page = app.getClass().getMethod("getActivePage").invoke(app);
            Object id = app.getClass().getMethod("getAppId").invoke(app);
            Object version = app.getClass().getMethod("getAppVersion").invoke(app);
            Object uri = activity.getClass().getMethod("getCurrentUri").invoke(activity);
            if (page == null
                    || !(id instanceof String appId)
                    || "unknown".equals(DiagnosticPolicy.appId(appId))
                    || !(version instanceof String resourceVersion)
                    || resourceVersion.length() > 256
                    || !(uri instanceof String route)
                    || route.length() > 16384
                    || DiagnosticPolicy.routeKey(route) == null) return null;
            ClassLoader loader = page.getClass().getClassLoader();
            Class<?> pageApi = Class.forName("com.alibaba.ariver.app.api.Page", false, loader);
            Class<?> renderApi =
                    Class.forName("com.alibaba.ariver.engine.api.Render", false, loader);
            if (!pageApi.isInstance(page)) return null;
            Object render = pageApi.getMethod("getRender").invoke(page);
            if (!renderApi.isInstance(render)) return null;
            Object view = renderApi.getMethod("getView").invoke(render);
            if (!(view instanceof View renderView)) return null;
            return new ActiveRenderIdentity(
                    activity,
                    app,
                    page,
                    render,
                    renderView,
                    appId,
                    resourceVersion,
                    route,
                    MessageDigest.getInstance("SHA-256")
                            .digest(route.getBytes(StandardCharsets.UTF_8)));
        } catch (ReflectiveOperationException
                | RuntimeException
                | LinkageError
                | java.security.NoSuchAlgorithmException ignored) {
            return null;
        }
    }

    boolean same(ActiveRenderIdentity other) {
        return other != null
                && activity == other.activity
                && app == other.app
                && page == other.page
                && render == other.render
                && renderView == other.renderView
                && appId.equals(other.appId)
                && version.equals(other.version)
                && Arrays.equals(navigation, other.navigation);
    }

    boolean owns(View view) {
        return inside(view, renderView);
    }

    View uniqueDescendant(String className) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(renderView);
        View match = null;
        int count = 0;
        while (!pending.isEmpty() && count++ < 2048) {
            View view = pending.removeFirst();
            if (className.equals(view.getClass().getName())) {
                if (match != null) return null;
                match = view;
            }
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++)
                    pending.addLast(group.getChildAt(i));
            }
        }
        return pending.isEmpty() ? match : null;
    }

    static boolean inside(View child, View parent) {
        for (View current = child; current != null; ) {
            if (current == parent) return true;
            ViewParent next = current.getParent();
            current = next instanceof View view ? view : null;
        }
        return false;
    }
}
