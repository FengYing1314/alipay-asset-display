package io.github.fengying.alipaydisplay;

/** 同一文档的显示门控状态机，由显示会话在主线程推进。 页面代次、nonce及SDK回调归属由调用方核验；就绪状态不代表物理屏幕逐帧证明。 */
final class WebDrawGate {
    static final long DEADLINE_MS = 5000L;

    private long startedAt;
    private boolean waitingShape;
    boolean timedOut;
    long visualRequestedAt = -1L, firstReleasedDrawAt = -1L;
    boolean visualInvokeReturned;
    int releasedDraws, callbackReports;
    State state = State.WAIT_APPLY;
    Failure failure = Failure.NONE;

    WebDrawGate(long now) {
        startedAt = now;
    }

    boolean suppress(long now) {
        if (state == State.READY || state == State.FAILED) return false;
        if (now < startedAt || now - startedAt >= DEADLINE_MS) {
            timedOut = true;
            fail(waitingShape ? Failure.UNKNOWN_SHAPE : Failure.TIMEOUT);
            return false;
        }
        return true;
    }

    boolean reply(
            int expected,
            int targets,
            int applied,
            int hidden,
            int waiting,
            int unknown,
            long now) {
        if (!suppress(now)) return false;
        if ((expected != 1 && expected != 4)
                || targets < 0
                || targets > expected
                || applied < 0
                || hidden < 0
                || waiting < 0
                || unknown < 0
                || applied > expected
                || hidden > expected
                || waiting > expected
                || targets != applied + hidden + waiting
                || unknown != expected - targets) {
            fail(Failure.INVALID_REPLY);
            return false;
        }
        waitingShape = unknown != 0;
        if (unknown != 0) {
            state = State.WAIT_APPLY;
            return false;
        }
        // waiting没有“已遮蔽”的独立协议证明，不能据此放行。
        if (waiting != 0 || applied + hidden != expected) {
            state = State.WAIT_APPLY;
            return false;
        }
        if (state == State.WAIT_VISUAL) return false;
        state = State.WAIT_VISUAL;
        return true;
    }

    boolean visualReady(long now) {
        if (state != State.WAIT_VISUAL || !suppress(now)) return false;
        state = State.READY;
        return true;
    }

    void invalidateProof(long now) {
        if (state == State.FAILED) return;
        // 未完成握手的重试不能无限延长同一document的5秒门控。
        if (state == State.READY) {
            startedAt = now;
            waitingShape = false;
            timedOut = false;
        }
        state = State.WAIT_APPLY;
    }

    void fail(Failure reason) {
        if (state == State.FAILED) return;
        state = State.FAILED;
        failure = reason;
    }

    void drawReleased(long now) {
        if (firstReleasedDrawAt < 0L) firstReleasedDrawAt = now;
        releasedDraws = Math.min(10000, releasedDraws + 1);
    }

    boolean onTime(long now) {
        return now >= startedAt && now - startedAt < DEADLINE_MS;
    }

    long remaining(long now) {
        return Math.max(0L, DEADLINE_MS - (now - startedAt));
    }

    long elapsed(long now) {
        return Math.max(0L, Math.min(60000L, now - startedAt));
    }

    enum State {
        WAIT_APPLY,
        WAIT_VISUAL,
        READY,
        FAILED
    }

    enum Failure {
        NONE,
        TIMEOUT,
        UNKNOWN_SHAPE,
        INVALID_REPLY,
        VISUAL_UNAVAILABLE,
        IDENTITY_UNAVAILABLE,
        IDENTITY_CHANGED
    }
}
