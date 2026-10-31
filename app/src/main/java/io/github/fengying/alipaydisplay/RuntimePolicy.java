package io.github.fengying.alipaydisplay;

/** 只允许已取证的包、主进程和版本进入展示会话。 */
final class RuntimePolicy {
    static final String PACKAGE = "com.eg.android.AlipayGphone";
    static final long VERSION_CODE = 212370L;
    static final String WEALTH_ACTIVITY = PACKAGE + ".AlipayLogin";

    private RuntimePolicy() {}

    static boolean acceptsProcess(
            String process,
            String packageName,
            boolean firstPackage,
            int api,
            boolean remotePreferences) {
        return PACKAGE.equals(process)
                && PACKAGE.equals(packageName)
                && firstPackage
                && api == 102
                && remotePreferences;
    }

    static boolean acceptsVersion(String packageName, long version) {
        return PACKAGE.equals(packageName) && version == VERSION_CODE;
    }

    static boolean isWealthActivity(String activityClass) {
        return WEALTH_ACTIVITY.equals(activityClass);
    }

    static boolean isBindingClass(String className) {
        return switch (className) {
            case "com.alipay.android.render.engine.asset.AssetHorizonItemV4View",
                    "com.alipay.android.render.engine.asset.AssetsHeaderV4View",
                    "com.alipay.android.render.engine.asset.AssetsHeaderPagerView",
                    "com.alipay.android.render.engine.asset.AssetsHeaderOldView",
                    "com.alipay.android.render.engine.asset.AssetsHeaderBankView" ->
                    true;
            default -> false;
        };
    }
}
