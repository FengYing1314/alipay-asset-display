package io.github.fengying.alipaydisplay;

/** 仅允许实机已确认的业务页面、资源版本和文档路由组合。 */
enum WebDisplayProfile {
    TOTAL_ASSETS(
            "total-assets",
            4,
            TotalAssetsProfile.APP_ID,
            TotalAssetsProfile.VERSION_FINGERPRINT,
            TotalAssetsProfile.ROUTE_FINGERPRINT),
    YUEBAO(
            "yuebao",
            1,
            "2060090000375934",
            "d65390b71f0c54224b4418041feb53b61354c59ad41df7f394462ce57504f32c",
            "6a9168651f10d7a0250fdc28518a864e998285da3c21a510debe940027a1e87c"),
    FUND(
            "fund",
            1,
            "20000793",
            "1436b067b8ea9566ed20c75d74353f8565671013d8571a2e2e8047ce5696db7d",
            "28e71ca38fd84df8cbdd9a3f433e31850047146dca102228eac2708202e14239");

    final String key;
    final int targetCount;
    final String scriptPath;
    private final String appId;
    private final String versionFingerprint;
    private final String routeFingerprint;

    WebDisplayProfile(
            String key,
            int targetCount,
            String appId,
            String versionFingerprint,
            String routeFingerprint) {
        this.key = key;
        this.targetCount = targetCount;
        this.scriptPath =
                "io/github/fengying/alipaydisplay/"
                        + ("fund".equals(key) ? "fund-summary.js" : "total-assets.js");
        this.appId = appId;
        this.versionFingerprint = versionFingerprint;
        this.routeFingerprint = routeFingerprint;
    }

    static WebDisplayProfile match(
            String appId, String version, String activityRoute, String documentRoute) {
        if ("unknown".equals(DiagnosticPolicy.version(version))) return null;
        String versionProof = DiagnosticPolicy.versionFingerprint(version);
        String activityProof =
                DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(activityRoute));
        String documentProof =
                DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(documentRoute));
        for (WebDisplayProfile profile : values()) {
            if (profile.appId.equals(appId)
                    && profile.versionFingerprint.equals(versionProof)
                    && profile.routeFingerprint.equals(activityProof)
                    && profile.routeFingerprint.equals(documentProof)) return profile;
        }
        return null;
    }

    static boolean acceptsDocument(String route) {
        String proof = DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(route));
        for (WebDisplayProfile profile : values())
            if (profile.routeFingerprint.equals(proof)) return true;
        return false;
    }
}
