package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class DiagnosticPolicyTest {
    @Test
    public void arbitraryHostStringsNeverPassOutputPolicy() {
        for (String input :
                new String[] {
                    "12345.67",
                    "customer-account",
                    "基金自定义名字",
                    "asset-card private",
                    "https://example.invalid/?account=private",
                    "总资产 12345.67",
                    "0.1.2?account=private"
                }) {
            assertEquals("", DiagnosticPolicy.token(input));
            assertEquals("", DiagnosticPolicy.label(input));
            assertEquals("OTHER", DiagnosticPolicy.tag(input));
            assertEquals("", DiagnosticPolicy.role(input));
            assertEquals("unknown", DiagnosticPolicy.version(input));
        }
        assertEquals("asset-card", DiagnosticPolicy.token("asset-card"));
        assertEquals("总资产", DiagnosticPolicy.label("总资产"));
        assertEquals("0.1.769", DiagnosticPolicy.version("0.1.769"));
        assertEquals("unknown", DiagnosticPolicy.page("unrecognized-app"));
        assertEquals("total-assets", DiagnosticPolicy.page("20000243"));
        assertEquals("total-assets", DiagnosticPolicy.page("68688004"));
        assertEquals("yuebao", DiagnosticPolicy.page("2060090000375934"));
    }

    @Test
    public void staleCallbacksCannotCrossRequestsDocumentsOrLifecycle() {
        DiagnosticPolicy.Epoch epoch = new DiagnosticPolicy.Epoch();
        epoch.resume();
        long page = epoch.page();
        long request = epoch.request();
        assertTrue(epoch.accepts(page, request));
        long newer = epoch.request();
        assertFalse(epoch.accepts(page, request));
        assertTrue(epoch.accepts(page, newer));
        epoch.invalidate();
        assertFalse(epoch.accepts(page, newer));
        page = epoch.page();
        request = epoch.request();
        epoch.pause();
        assertFalse(epoch.accepts(page, request));
        epoch.resume();
        assertFalse(epoch.accepts(page, request));
        assertTrue(epoch.accepts(epoch.page(), epoch.request()));
    }

    @Test
    public void routeComparisonDiscardsQueryAndFragmentButDetectsPageChanges() {
        byte[] original =
                DiagnosticPolicy.routeKey(
                        "https://example.invalid/pages/assets?private=one#secret");
        assertTrue(
                DiagnosticPolicy.sameRoute(
                        original,
                        DiagnosticPolicy.routeKey(
                                "https://example.invalid/pages/assets?private=two#other")));
        assertFalse(
                DiagnosticPolicy.sameRoute(
                        original,
                        DiagnosticPolicy.routeKey(
                                "https://example.invalid/pages/detail?private=one")));
        assertFalse(DiagnosticPolicy.sameRoute(original, DiagnosticPolicy.routeKey(null)));
        assertNull(DiagnosticPolicy.routeKey("not a URI with spaces"));
        assertNull(DiagnosticPolicy.routeKey("opaque:private"));
    }

    @Test
    public void fixedApiMetadataRetainsOnlyLegalAppIdsAndVersionClasses() {
        assertEquals("20000243", DiagnosticPolicy.appId("20000243"));
        assertEquals("2021001192699414", DiagnosticPolicy.appId("2021001192699414"));
        for (Object value :
                new Object[] {
                    null,
                    20000243L,
                    "12345.67",
                    "2000024",
                    "200002430",
                    "２００００２４３",
                    "20000243?private=one",
                    " 20000243"
                }) {
            assertEquals("unknown", DiagnosticPolicy.appId(value));
        }
        assertEquals("missing", DiagnosticPolicy.appIdState(null));
        assertEquals("empty", DiagnosticPolicy.appIdState(" "));
        assertEquals("valid", DiagnosticPolicy.appIdState("20000243"));
        assertEquals("missing", DiagnosticPolicy.versionState(null));
        assertEquals("empty", DiagnosticPolicy.versionState(" "));
        assertEquals("non-string", DiagnosticPolicy.versionState(123));
        assertEquals("dotted-version", DiagnosticPolicy.versionState("6.4.431"));
        assertEquals("two-part-number", DiagnosticPolicy.versionState("12345.67"));
        assertEquals("digits-only", DiagnosticPolicy.versionState("12345"));
        assertEquals("other-format", DiagnosticPolicy.versionState("6.4.431?private=one"));
        assertEquals("unknown", DiagnosticPolicy.version("12345.67"));
    }

    @Test
    public void routeLogsUseFixedCategoriesAndOnlyDigestBytes() {
        String uri = "https://example.invalid/pages/assets?private=one#secret";
        String fingerprint = DiagnosticPolicy.routeFingerprint(DiagnosticPolicy.routeKey(uri));
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertFalse(fingerprint.contains("private"));
        assertEquals(
                fingerprint,
                DiagnosticPolicy.routeFingerprint(
                        DiagnosticPolicy.routeKey(
                                "https://example.invalid/pages/assets?private=two#other")));
        assertEquals("https", DiagnosticPolicy.routeCategory(uri));
        assertEquals("relative", DiagnosticPolicy.routeCategory("/pages/assets?private=one"));
        assertEquals("other-hierarchical", DiagnosticPolicy.routeCategory("custom://host/page"));
        assertEquals("invalid", DiagnosticPolicy.routeCategory("opaque:private"));
        assertNull(DiagnosticPolicy.routeKey("https://username:password@example.invalid/path"));
        assertEquals("unknown", DiagnosticPolicy.routeFingerprint(new byte[3]));
    }

    @Test
    public void monetaryShapeAndSemanticsDoNotConfuseLabelsOrProfit() {
        for (String value : new String[] {"0", "0.00", "1,234.56", "1,\u200b234.56", "1 234.56"}) {
            assertTrue(value, DiagnosticPolicy.numericOnly(value));
        }
        for (String value :
                new String[] {
                    "", "总资产123.45", "123.45元", "3.50%", "1,23.45", "--", "****", "1.234"
                }) {
            assertFalse(value, DiagnosticPolicy.numericOnly(value));
        }
        assertEquals("yuebao", DiagnosticPolicy.semanticKind("余额宝，金额已隐藏"));
        assertEquals("balance", DiagnosticPolicy.semanticKind("余额，123.45元"));
        assertEquals("total-assets", DiagnosticPolicy.semanticKind("总资产(元)，123.45"));
        assertEquals("fund", DiagnosticPolicy.semanticKind("基金"));
        assertEquals("none", DiagnosticPolicy.semanticKind("昨日收益123.45元"));
        assertEquals("none", DiagnosticPolicy.semanticKind("余额宝与余额"));
        assertEquals("none", DiagnosticPolicy.semanticKind("总资产余额宝基金"));
        assertEquals("none", DiagnosticPolicy.semantic("余额宝与私人名称"));
    }

    @Test
    public void pathsStayWithinDepthAndNodeBudgetAndExposeOnlyStructuralAncestors() {
        String deepest = "0.1.2.3.4.5.6.7.199";
        assertEquals(deepest, DiagnosticPolicy.nodePath(deepest));
        assertEquals("0.1.2.3.4.5.6.7", DiagnosticPolicy.parentPath(deepest));
        assertEquals("0/0.1/0.1.2", DiagnosticPolicy.ancestorPaths("0.1.2.3"));
        assertEquals("root", DiagnosticPolicy.parentPath("0"));
        assertEquals("24.0", DiagnosticPolicy.nodePath("24.0"));
        assertEquals("33.0", DiagnosticPolicy.nodePath("33.0"));
        for (String value :
                new String[] {"34.0", "0.200", "0.1.2.3.4.5.6.7.8.9", "0.secret", "0\nprivate"}) {
            assertEquals("", DiagnosticPolicy.nodePath(value));
            assertEquals("", DiagnosticPolicy.ancestorPaths(value));
        }
    }

    @Test
    public void versionSentinelsAndReleaseTailsNeverExposeUnapprovedRawStrings() {
        assertEquals("wildcard", DiagnosticPolicy.versionState("*"));
        assertEquals("online", DiagnosticPolicy.versionState("ONLINE"));
        assertEquals("timestamp-tail", DiagnosticPolicy.versionState("0.1.769.16983224119101452"));
        assertEquals(
                "0.1.769.16983224119101452", DiagnosticPolicy.version("0.1.769.16983224119101452"));
        assertEquals("0.1.2209022010.11", DiagnosticPolicy.version("0.1.2209022010.11"));
        assertEquals("dotted-version", DiagnosticPolicy.versionState("0.1.2209022010.11"));
        assertEquals(
                "0.1.12345678901234567890", DiagnosticPolicy.version("0.1.12345678901234567890"));
        assertEquals("unknown", DiagnosticPolicy.version("0.1.123456789012345678901"));
        assertEquals("dotted-with-suffix", DiagnosticPolicy.versionState("6.4.431-beta_20261031"));
        assertEquals("6.4.431", DiagnosticPolicy.version("6.4.431-beta_20261031"));
        assertEquals("unknown", DiagnosticPolicy.version("*"));
        assertEquals("unknown", DiagnosticPolicy.version("6.4.431/private"));
        assertEquals("unknown", DiagnosticPolicy.version("12345.67"));
        String first = DiagnosticPolicy.versionFingerprint("0.1.769.16983224119101452");
        String second = DiagnosticPolicy.versionFingerprint("0.1.769.16983224119101453");
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
        assertNotEquals(
                DiagnosticPolicy.versionFingerprint("sentinel-one"),
                DiagnosticPolicy.versionFingerprint("sentinel-two"));
        assertEquals("unknown", DiagnosticPolicy.versionFingerprint(null));
        String oversized = new String(new char[257]).replace('\0', 'x');
        assertEquals("over-limit", DiagnosticPolicy.versionState(oversized));
        assertEquals("unknown", DiagnosticPolicy.versionFingerprint(oversized));
    }

    @Test
    public void semanticProbeAnchorsCannotBeMistakenForDomClassTokens() {
        for (String anchor :
                new String[] {"semantic-balance", "semantic-yuebao", "semantic-fund"}) {
            assertEquals(anchor, DiagnosticPolicy.rootToken(anchor));
            assertEquals("", DiagnosticPolicy.token(anchor));
        }
        assertEquals("asset-card", DiagnosticPolicy.rootToken("asset-card"));
        assertEquals("", DiagnosticPolicy.rootToken("semantic-private-account"));
    }

    @Test
    public void ownerMetadataComparesOnlyTypedIdsAndTokensWithoutCoercion() {
        assertTrue(DiagnosticPolicy.sameAppId("68688004", "68688004"));
        assertFalse(DiagnosticPolicy.sameAppId("unknown", "unknown"));
        assertFalse(DiagnosticPolicy.sameAppId("68688004", "20000243"));
        assertFalse(DiagnosticPolicy.sameAppId("68688004", 68688004L));
        assertTrue(DiagnosticPolicy.sameStartToken(7L, 7L));
        assertFalse(DiagnosticPolicy.sameStartToken(7L, 8L));
        assertFalse(DiagnosticPolicy.sameStartToken(7L, "7"));
        assertFalse(DiagnosticPolicy.sameStartToken(null, null));
    }

    @Test
    public void totalAmountSemanticAndAncestorAnchorsStayDistinctFromDomSelectors() {
        assertEquals("total-amount", DiagnosticPolicy.semanticKind("总金额（元）"));
        assertEquals("total-amount", DiagnosticPolicy.semanticKind("总金额，金额已隐藏"));
        assertEquals("none", DiagnosticPolicy.semanticKind("可用金额"));
        assertEquals("none", DiagnosticPolicy.semanticKind("总金额与总资产"));
        assertEquals("total-amount", DiagnosticPolicy.semantic("total-amount"));
        assertEquals("none", DiagnosticPolicy.semantic("total-amount-private"));
        for (int level = 1; level <= 3; level++) {
            String token = "amount-parent-" + level;
            assertEquals(token, DiagnosticPolicy.rootToken(token));
            assertEquals("ancestor-probe", DiagnosticPolicy.rootSource(token));
            assertEquals("", DiagnosticPolicy.token(token));
        }
        assertEquals("", DiagnosticPolicy.rootToken("amount-parent-4"));
        assertEquals("semantic-probe", DiagnosticPolicy.rootSource("semantic-fund"));
        assertEquals("dom", DiagnosticPolicy.rootSource("amountView"));
    }

    @Test
    public void fundSplitRolesExposeOnlyTheFixedStructuralEnum() {
        for (String value : new String[] {"INTEGER", "COMMA", "POINT", "FRACTION", "OTHER"}) {
            assertEquals(value, DiagnosticPolicy.splitTextRole(value));
        }
        for (String value :
                new String[] {null, "12345", "123.45", "private-fund-name", "INTEGER private"}) {
            assertEquals("OTHER", DiagnosticPolicy.splitTextRole(value));
        }
    }
}
