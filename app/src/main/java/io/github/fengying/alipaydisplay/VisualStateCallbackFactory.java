package io.github.fengying.alipaydisplay;

import dalvik.system.InMemoryDexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 只在内存加载随模块构建的回调桥；不创建WebView、不写手机文件。 */
final class VisualStateCallbackFactory {
    private static final String BRIDGE_RESOURCE_PATH =
            "io/github/fengying/alipaydisplay/visual-state-bridge.dex";
    private static final String BRIDGE_CLASS_NAME =
            "io.github.fengying.alipaydisplay.bridge.VisualStateBridge";
    private static final WeakHashMap<ClassLoader, Constructor<?>> BRIDGE_CONSTRUCTORS =
            new WeakHashMap<>();
    private static String moduleApk;

    static synchronized void setModuleApk(String source) {
        moduleApk = source;
    }

    static synchronized Object create(ClassLoader hostLoader, LongConsumer completion) {
        if (hostLoader == null || completion == null) return null;
        try {
            Constructor<?> constructor = BRIDGE_CONSTRUCTORS.get(hostLoader);
            if (constructor == null) {
                byte[] dex = loadBridgeDex();
                if (dex == null) return null;
                ClassLoader bridgeLoader =
                        new InMemoryDexClassLoader(ByteBuffer.wrap(dex), hostLoader);
                Class<?> type = Class.forName(BRIDGE_CLASS_NAME, true, bridgeLoader);
                Class<?> api =
                        Class.forName(
                                "com.alipay.mywebview.sdk.WebView$VisualStateCallback",
                                false,
                                hostLoader);
                if (!api.isAssignableFrom(type)) return null;
                constructor = type.getConstructor(LongConsumer.class);
                BRIDGE_CONSTRUCTORS.put(hostLoader, constructor);
            }
            return constructor.newInstance(completion);
        } catch (ReflectiveOperationException
                | java.io.IOException
                | RuntimeException
                | LinkageError ignored) {
            return null;
        }
    }

    private static byte[] loadBridgeDex() throws java.io.IOException {
        InputStream resource =
                VisualStateCallbackFactory.class
                        .getClassLoader()
                        .getResourceAsStream(BRIDGE_RESOURCE_PATH);
        if (resource != null)
            try (InputStream stream = resource) {
                return readBoundedBridgeDex(stream);
            }
        if (moduleApk == null) return null;
        try (ZipFile archive = new ZipFile(moduleApk)) {
            ZipEntry entry = archive.getEntry(BRIDGE_RESOURCE_PATH);
            if (entry == null) return null;
            try (InputStream stream = archive.getInputStream(entry)) {
                return readBoundedBridgeDex(stream);
            }
        }
    }

    private static byte[] readBoundedBridgeDex(InputStream stream) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > 16384) return null;
            output.write(buffer, 0, count);
        }
        byte[] dex = output.toByteArray();
        return dex.length >= 8 && dex[0] == 'd' && dex[1] == 'e' && dex[2] == 'x' && dex[3] == '\n'
                ? dex
                : null;
    }
}
