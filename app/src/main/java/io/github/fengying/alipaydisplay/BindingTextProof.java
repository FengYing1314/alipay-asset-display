package io.github.fengying.alipaydisplay;

/** 明确显示绑定内的文本变化凭据；单独出现数字不能建立新来源。 */
final class BindingTextProof {
    private final Object beforeText;
    private final boolean beforeAmount;
    private final boolean beforeModuleOwned;
    private final long privacyEpoch;
    private final long hostTextRevision;

    BindingTextProof(
            Object beforeText,
            boolean beforeAmount,
            boolean beforeModuleOwned,
            long privacyEpoch,
            long hostTextRevision) {
        this.beforeText = beforeText;
        this.beforeAmount = beforeAmount;
        this.beforeModuleOwned = beforeModuleOwned;
        this.privacyEpoch = privacyEpoch;
        this.hostTextRevision = hostTextRevision;
    }

    boolean changedToHostAmount(
            Object currentText,
            boolean currentAmount,
            boolean currentModuleOwned,
            long currentPrivacyEpoch,
            long currentHostTextRevision) {
        return currentPrivacyEpoch == privacyEpoch
                && currentHostTextRevision == hostTextRevision
                && currentAmount
                && !currentModuleOwned
                && (currentText != beforeText || !beforeAmount || beforeModuleOwned);
    }
}
