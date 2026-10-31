package io.github.fengying.alipaydisplay;

/** 每个字段只记最后一次已确认自有输出；同值去标记回声不能成为宿主原文。 */
final class LastOutputLease {
    private String output;
    private boolean blocked;

    void confirmOwned(String value) {
        output = value;
        blocked = false;
    }

    boolean acceptHost(String value, boolean explicitBindingProof) {
        if (!explicitBindingProof && rejectsUnowned(value)) return false;
        clear();
        return true;
    }

    boolean rejectsUnowned(String value) {
        if (output == null || !output.equals(value)) return false;
        blocked = true;
        return true;
    }

    void clear() {
        output = null;
        blocked = false;
    }

    boolean isBlocked() {
        return blocked;
    }
}
