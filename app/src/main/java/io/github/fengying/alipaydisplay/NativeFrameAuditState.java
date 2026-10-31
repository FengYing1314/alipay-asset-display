package io.github.fengying.alipaydisplay;

/** 只累积布尔事实和计数；配置未就绪、无样本均不代表通过。 */
final class NativeFrameAuditState {

    static final int ACTIVE_VISIBLE_LIMIT = 90;
    static final int PAUSED_VISIBLE_LIMIT = 30;
    static final int ACTIVE_ATTEMPT_LIMIT = 360;
    static final int PAUSED_ATTEMPT_LIMIT = 120;

    private final Budget active = new Budget();
    private final Budget paused = new Budget();

    boolean begin(Phase phase) {
        Budget value = budget(phase);
        if (value.attempts >= attemptLimit(phase) || value.visibleDraws >= visibleLimit(phase))
            return false;
        value.attempts++;
        return true;
    }

    void observed(
            Phase phase,
            boolean configReady,
            boolean enabled,
            boolean complete,
            int rootCount,
            int visibleTargets) {
        Budget value = budget(phase);
        if (!configReady) value.configPendingDraws++;
        else if (!enabled) value.disabledDraws++;
        if (!complete) value.incompleteDiscoveryDraws++;
        else if (rootCount == 0) value.rootMissingDraws++;
        else if (rootCount > 1) value.rootAmbiguousDraws++;
        if (visibleTargets == 0) value.noVisibleTargetDraws++;
        else value.visibleDraws++;
    }

    boolean exhausted(Phase phase) {
        Budget value = budget(phase);
        return value.attempts >= attemptLimit(phase) || value.visibleDraws >= visibleLimit(phase);
    }

    Budget budget(Phase phase) {
        return phase == Phase.PAUSED ? paused : active;
    }

    private static int visibleLimit(Phase phase) {
        return phase == Phase.PAUSED ? PAUSED_VISIBLE_LIMIT : ACTIVE_VISIBLE_LIMIT;
    }

    private static int attemptLimit(Phase phase) {
        return phase == Phase.PAUSED ? PAUSED_ATTEMPT_LIMIT : ACTIVE_ATTEMPT_LIMIT;
    }

    enum Phase {
        ACTIVE,
        PAUSED
    }

    static final class Budget {
        int attempts;
        int visibleDraws;
        int configPendingDraws;
        int disabledDraws;
        int rootMissingDraws;
        int rootAmbiguousDraws;
        int incompleteDiscoveryDraws;
        int noVisibleTargetDraws;
    }

    /** 不接受字符串，避免审计状态意外保存原文。 */
    static final class Sample {
        boolean configReady;
        boolean enabled;
        boolean textMatches;
        boolean textMasked;
        boolean textNumeric;
        boolean textDigits;
        boolean layoutPresent;
        boolean layoutMatches;
        boolean layoutMasked;
        boolean layoutNumeric;
        boolean layoutDigits;
        boolean descriptionPresent;
        boolean descriptionMatches;
        boolean descriptionMasked;
        boolean descriptionDigits;
        boolean bound;
        boolean textOwned;
        boolean descriptionOwned;
        boolean privacyKnown;
        boolean privacyHidden;
    }

    static final class Counts {
        int samples;
        int configPending;
        int disabled;
        int checked;
        int textMatches;
        int textMasked;
        int unexpectedTextNumeric;
        int layoutMissing;
        int layoutMatches;
        int layoutMasked;
        int unexpectedLayoutNumeric;
        int descriptionMissing;
        int descriptionMatches;
        int descriptionMasked;
        int unexpectedDescriptionDigits;
        int unbound;
        int textOwned;
        int descriptionOwned;
        int privacyUnknown;
        int privacyHidden;
        int hiddenDigits;
        Sample first;

        void record(Sample sample) {
            if (first == null) first = sample;
            samples++;
            if (!sample.configReady) {
                configPending++;
                return;
            }
            if (!sample.enabled) {
                disabled++;
                return;
            }
            checked++;
            if (sample.textMatches) textMatches++;
            if (sample.textMasked) textMasked++;
            if (sample.textNumeric && !sample.textMatches) unexpectedTextNumeric++;
            if (!sample.layoutPresent) layoutMissing++;
            if (sample.layoutMatches) layoutMatches++;
            if (sample.layoutMasked) layoutMasked++;
            if (sample.layoutNumeric && !sample.layoutMatches) unexpectedLayoutNumeric++;
            if (!sample.descriptionPresent) descriptionMissing++;
            if (sample.descriptionMatches) descriptionMatches++;
            if (sample.descriptionMasked) descriptionMasked++;
            if (sample.descriptionDigits && !sample.descriptionMatches)
                unexpectedDescriptionDigits++;
            if (!sample.bound) unbound++;
            if (sample.textOwned) textOwned++;
            if (sample.descriptionOwned) descriptionOwned++;
            if (!sample.privacyKnown) privacyUnknown++;
            if (sample.privacyKnown && sample.privacyHidden) {
                privacyHidden++;
                if (sample.textDigits || sample.layoutDigits || sample.descriptionDigits)
                    hiddenDigits++;
            }
        }
    }
}
