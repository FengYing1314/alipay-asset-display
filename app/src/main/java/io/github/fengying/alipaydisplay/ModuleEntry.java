package io.github.fengying.alipaydisplay;

import android.app.Application;
import android.app.Instrumentation;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.util.ArrayList;
import java.util.List;

/** API 102 入口；注册 Android 基础拦截，将页面归属和显示保护交给运行时。 */
public final class ModuleEntry extends XposedModule {
    private String processName;
    private NativeRuntime runtime;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.isSystemServer() ? null : param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (runtime != null
                || !RuntimePolicy.acceptsProcess(
                        processName,
                        param.getPackageName(),
                        param.isFirstPackage(),
                        getApiVersion(),
                        (getFrameworkProperties() & PROP_CAP_REMOTE) != 0)) return;
        NativeRuntime candidate = null;
        try {
            candidate = new NativeRuntime(this);
            installHooks(candidate);
            runtime = candidate;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            for (XposedInterface.HookHandle handle : handles) {
                try {
                    handle.unhook();
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            handles.clear();
            try {
                log(android.util.Log.WARN, "AlipayDisplay", "入口初始化失败；未启用展示会话");
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private void installHooks(NativeRuntime target) throws NoSuchMethodException {
        installApplicationLifecycleHook(target);
        installNativeTextHooks(target);
        installViewStructureHooks(target);
        installWebDrawHook(target);
    }

    private void installApplicationLifecycleHook(NativeRuntime target)
            throws NoSuchMethodException {
        handles.add(
                hook(Instrumentation.class.getDeclaredMethod(
                                "callApplicationOnCreate", Application.class))
                        .intercept(
                                chain -> {
                                    target.runSafely(
                                            () ->
                                                    target.attachApplication(
                                                            (Application) chain.getArg(0)));
                                    return chain.proceed();
                                }));
    }

    private void installNativeTextHooks(NativeRuntime target) throws NoSuchMethodException {
        handles.add(
                hook(TextView.class.getDeclaredMethod(
                                "setText", CharSequence.class, TextView.BufferType.class))
                        .intercept(
                                chain -> {
                                    Object result = chain.proceed();
                                    target.runSafely(
                                            () ->
                                                    target.onTextWritten(
                                                            (TextView) chain.getThisObject()));
                                    return result;
                                }));
        handles.add(
                hook(View.class.getDeclaredMethod("setContentDescription", CharSequence.class))
                        .intercept(
                                chain -> {
                                    Object result = chain.proceed();
                                    target.runSafely(
                                            () ->
                                                    target.onDescriptionWritten(
                                                            (View) chain.getThisObject()));
                                    return result;
                                }));
        handles.add(
                hook(TextView.class.getDeclaredMethod("onDraw", Canvas.class))
                        .intercept(
                                chain -> {
                                    boolean[] suppress = {false};
                                    target.runSafely(
                                            () ->
                                                    suppress[0] =
                                                            target.shouldSuppressNativeDraw(
                                                                    (TextView)
                                                                            chain.getThisObject()));
                                    if (!suppress[0]) return chain.proceed();
                                    Canvas canvas = (Canvas) chain.getArg(0);
                                    int saved = canvas.save();
                                    try {
                                        canvas.clipRect(0, 0, 0, 0);
                                        return chain.proceed();
                                    } finally {
                                        canvas.restoreToCount(saved);
                                    }
                                }));
    }

    private void installViewStructureHooks(NativeRuntime target) throws NoSuchMethodException {
        handles.add(
                hook(View.class.getDeclaredMethod("setVisibility", int.class))
                        .intercept(
                                chain -> {
                                    View view = (View) chain.getThisObject();
                                    int before = view.getVisibility();
                                    Object result = chain.proceed();
                                    target.runSafely(
                                            () -> {
                                                if (before != view.getVisibility())
                                                    target.onStructureChanged(view);
                                            });
                                    return result;
                                }));
        handles.add(
                hook(ViewGroup.class.getDeclaredMethod(
                                "addView", View.class, int.class, ViewGroup.LayoutParams.class))
                        .intercept(
                                chain -> {
                                    Object result = chain.proceed();
                                    target.runSafely(
                                            () ->
                                                    target.onStructureChanged(
                                                            (View) chain.getArg(0)));
                                    return result;
                                }));
    }

    private void installWebDrawHook(NativeRuntime target) throws NoSuchMethodException {
        handles.add(
                hook(ViewGroup.class.getDeclaredMethod("dispatchDraw", Canvas.class))
                        .intercept(
                                chain -> {
                                    boolean[] suppress = {false};
                                    target.runSafely(
                                            () ->
                                                    suppress[0] =
                                                            target.shouldSuppressWebDraw(
                                                                    (View) chain.getThisObject()));
                                    if (!suppress[0]) return chain.proceed();
                                    Canvas canvas = (Canvas) chain.getArg(0);
                                    View view = (View) chain.getThisObject();
                                    // 保留子树的真实draw调度，透明图层只阻止本次结果进入屏幕。
                                    int saved =
                                            canvas.saveLayerAlpha(
                                                    0f, 0f, view.getWidth(), view.getHeight(), 0);
                                    try {
                                        return chain.proceed();
                                    } finally {
                                        canvas.restoreToCount(saved);
                                    }
                                }));
    }
}
