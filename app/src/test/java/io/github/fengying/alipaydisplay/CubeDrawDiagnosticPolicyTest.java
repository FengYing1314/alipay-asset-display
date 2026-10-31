package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class CubeDrawDiagnosticPolicyTest {
    @Test
    public void onlyExactObservedIdentityAndStagesAreAccepted() {
        assertTrue(CubeDrawDiagnostics.Policy.identity("77700300", 212370));
        assertFalse(CubeDrawDiagnostics.Policy.identity("77700300", 212371));
        assertFalse(CubeDrawDiagnostics.Policy.identity("77700301", 212370));
        assertFalse(CubeDrawDiagnostics.Policy.identity(null, 212370));
        for (String stage : new String[] {"flush", "bind", "update", "existing"})
            assertTrue(CubeDrawDiagnostics.Policy.stage(stage));
        assertFalse(CubeDrawDiagnostics.Policy.stage("submit arbitrary"));
    }

    @Test
    public void displayTextDiscoveryRequiresExactResourceAndRoute() {
        String route = "bf0155f27c322f06e77362dbb3c41988efdae4e371fd0cda6f04d89a729a9eef";
        assertTrue(
                CubeDrawDiagnostics.Policy.discovery(
                        "77700300", 212370, "0.1.2610191118.5", route));
        assertFalse(CubeDrawDiagnostics.Policy.discovery("77700300", 212370, null, null));
        assertFalse(
                CubeDrawDiagnostics.Policy.discovery(
                        "77700300", 212370, "0.1.2610191118.6", route));
        assertFalse(
                CubeDrawDiagnostics.Policy.discovery(
                        "77700300", 212370, "0.1.2610191118.5", route + "0"));
        assertFalse(
                CubeDrawDiagnostics.Policy.discovery(
                        "20000793", 212370, "0.1.2610191118.5", route));
        assertFalse(
                CubeDrawDiagnostics.Policy.discovery(
                        "77700300", 212371, "0.1.2610191118.5", route));
    }

    @Test
    public void everyReal57CommandCanvasFitsAndTruncationReasonsAreSeparate() {
        for (int count : new int[] {0, 1, 32, 57, 96}) {
            assertFalse(CubeDrawDiagnostics.Policy.commandTruncated(count));
            assertFalse(CubeDrawDiagnostics.Policy.budgetTruncated(count, count));
            assertTrue(CubeDrawDiagnostics.Policy.complete(count, count));
        }
        assertTrue(CubeDrawDiagnostics.Policy.commandTruncated(97));
        assertFalse(CubeDrawDiagnostics.Policy.budgetTruncated(97, 96));
        assertFalse(CubeDrawDiagnostics.Policy.complete(97, 96));
        assertFalse(CubeDrawDiagnostics.Policy.commandTruncated(57));
        assertTrue(CubeDrawDiagnostics.Policy.budgetTruncated(57, 32));
        assertFalse(CubeDrawDiagnostics.Policy.complete(57, 32));
    }

    @Test
    public void nodeHashesAreBoundedAndNeverReturnHostText() {
        String raw = "private-node-identifier";
        String hash = CubeDrawDiagnostics.Policy.hash(raw);
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertFalse(hash.contains(raw));
        assertEquals(hash, CubeDrawDiagnostics.Policy.hash(raw));
        assertEquals("unavailable", CubeDrawDiagnostics.Policy.hash(null));
        assertEquals("unavailable", CubeDrawDiagnostics.Policy.hash("x".repeat(257)));
    }

    @Test
    public void unrecognizedRolesCannotLeakDescriptions() {
        for (String privateText :
                new String[] {
                    "余额宝",
                    "余额 客户名称",
                    "基金1234.56",
                    "1234.56",
                    "余额1.00 收益2.00",
                    "https://example.invalid/private?account=one",
                    "任意账户信息",
                    "arbitrary；余额"
                }) {
            assertEquals("other", CubeDrawDiagnostics.Policy.role(privateText));
        }
    }
}
