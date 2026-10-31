package io.github.fengying.alipaydisplay;

/** 由当前版本实机取证锁定的总资产页；路由摘要不含查询参数或片段。 */
final class TotalAssetsProfile {
    static final String APP_ID = "68688004";
    static final String VERSION_FINGERPRINT =
            "ef87325bd6ca7a879e935555adc09b4a3644943de456a2c6f900d2de294f173b";
    static final String ROUTE_FINGERPRINT =
            "f102ae1536ea79d13fb2929e0ea6bfe897378bafe5c98d0f8648bfb8adeb384a";

    private TotalAssetsProfile() {}

    static boolean accepts(String appId, String version, String route) {
        return APP_ID.equals(appId)
                && !"unknown".equals(DiagnosticPolicy.version(version))
                && VERSION_FINGERPRINT.equals(DiagnosticPolicy.versionFingerprint(version))
                && ROUTE_FINGERPRINT.equals(
                        DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(route)));
    }

    static boolean acceptsDocument(String route) {
        return ROUTE_FINGERPRINT.equals(
                DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(route)));
    }
}
