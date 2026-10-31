package io.github.fengying.alipaydisplay;

import java.util.concurrent.atomic.AtomicLong;

/** 配置读取的纯代次门控：请求可来自不同线程，快照发布由主线程串行完成。 每个请求都推进代次，合并读取也不会把未发布的新请求误判为已完成。 */
final class ConfigReadEpoch {
    private final AtomicLong requested = new AtomicLong();
    private volatile long published;

    long request() {
        return requested.incrementAndGet();
    }

    long current() {
        return requested.get();
    }

    boolean pending() {
        return requested.get() != published;
    }

    boolean publish(long epoch) {
        if (epoch != requested.get()) return false;
        published = epoch;
        return epoch == requested.get();
    }
}
