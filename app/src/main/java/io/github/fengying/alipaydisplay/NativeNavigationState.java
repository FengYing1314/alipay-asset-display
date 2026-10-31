package io.github.fengying.alipaydisplay;

/** 先以正向内容归属建立身份，再判断离开；未知导航事实不使页面失效。 */
final class NativeNavigationState {

    private Object trustedHost;
    private Object trustedPage;
    private boolean suspended;

    Outcome observe(
            Object host,
            Object page,
            boolean known,
            boolean currentWealth,
            boolean wealthShown,
            boolean busy) {
        if (host == null || page == null || !known) return Outcome.UNKNOWN;
        if (trustedHost == null) {
            if (!currentWealth || !wealthShown) return Outcome.UNKNOWN;
            trustedHost = host;
            trustedPage = page;
        } else if (trustedHost != host) {
            return Outcome.UNKNOWN;
        } else if (trustedPage != page) {
            if (!currentWealth || !wealthShown) return Outcome.UNKNOWN;
            trustedPage = page;
        }
        if (busy) return Outcome.DEFERRED;
        if (currentWealth && wealthShown) {
            if (!suspended) return Outcome.CURRENT;
            suspended = false;
            return Outcome.ENTERED;
        }
        if (!currentWealth && !wealthShown) {
            if (suspended) return Outcome.AWAY;
            suspended = true;
            return Outcome.LEFT;
        }
        return Outcome.UNKNOWN;
    }

    boolean isSuspended() {
        return suspended;
    }

    boolean trusts(Object host, Object page) {
        return trustedHost != null && trustedHost == host && trustedPage == page;
    }

    enum Outcome {
        UNKNOWN,
        CURRENT,
        DEFERRED,
        LEFT,
        AWAY,
        ENTERED
    }
}
