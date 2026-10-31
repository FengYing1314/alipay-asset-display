package io.github.fengying.alipaydisplay;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** 在应用级持有框架监听器，避免 Activity 重建时重复注册。 */
public final class ModuleApp extends Application {

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile XposedService service;
    private Handler mainHandler;

    private final XposedServiceHelper.OnServiceListener serviceListener =
            new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService value) {
                    synchronized (ModuleApp.this) {
                        service = value;
                    }
                    notifyServiceListeners(value);
                }

                @Override
                public void onServiceDied(XposedService value) {
                    synchronized (ModuleApp.this) {
                        if (service != value) return;
                        service = null;
                    }
                    notifyServiceListeners(null);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        XposedServiceHelper.registerListener(serviceListener);
    }

    public XposedService getService() {
        return service;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        dispatchServiceChange(listener, service);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyServiceListeners(XposedService value) {
        for (Listener listener : listeners) {
            dispatchServiceChange(listener, value);
        }
    }

    private void dispatchServiceChange(Listener listener, XposedService value) {
        mainHandler.post(
                () -> {
                    if (listeners.contains(listener) && service == value) {
                        listener.onServiceChanged(value);
                    }
                });
    }

    public interface Listener {
        void onServiceChanged(XposedService service);
    }
}
