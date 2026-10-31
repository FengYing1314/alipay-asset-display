package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class BalanceCanvasSessionPolicyTest {
    @Test
    public void completeTypeSequenceHashIsOrderSensitiveAndContainsNoValues() {
        String[] types = new String[57];
        java.util.Arrays.fill(types, "com.antfin.cube.cubecore.draw.CRViewDrawCmd");
        types[27] = "com.antfin.cube.cubecore.draw.CRTextViewDrawCmdInline";
        String original = BalanceCanvasSession.Policy.classSequenceFingerprint(types);
        assertTrue(original.matches("[0-9a-f]{64}"));
        String[] changed = types.clone();
        changed[28] = changed[27];
        changed[27] = changed[0];
        assertNotEquals(original, BalanceCanvasSession.Policy.classSequenceFingerprint(changed));
        assertEquals(
                "unavailable",
                BalanceCanvasSession.Policy.classSequenceFingerprint(new String[56]));
        changed[27] = "https://example.invalid/private";
        assertEquals("unavailable", BalanceCanvasSession.Policy.classSequenceFingerprint(changed));
        assertFalse(original.contains("CRViewDrawCmd"));
    }

    @Test
    public void onlyObservedCompleteUniqueSemanticShapeCanQualify() {
        assertTrue(BalanceCanvasSession.Policy.shape(57, 1, 1, 11, 27, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(82, 1, 0, 11, -1, false, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(57, 2, 1, 11, 27, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(57, 1, 2, 11, 27, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(57, 1, 1, 11, 27, true, 1));
        assertFalse(BalanceCanvasSession.Policy.shape(57, 1, 1, 11, 26, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(57, 1, 1, 11, 27, false, 0));
    }

    @Test
    public void exactObservedMainSequenceIsPinnedAndSkeletonOrNewSequenceIsRejected() {
        assertTrue(
                BalanceCanvasSession.Policy.pinnedSequence(
                        "f1c7cf5b167cc3d2ad3fa98840f90380460cfae3830a0e1b16c64f374ee30588"));
        assertFalse(
                BalanceCanvasSession.Policy.pinnedSequence(
                        "210928e7c7d28f06ee8d6790bd57ee6c10077e49f17d7234342a6b50f6df563f"));
        assertFalse(BalanceCanvasSession.Policy.pinnedSequence("unavailable"));
        assertFalse(BalanceCanvasSession.Policy.pinnedSequence(null));
    }

    @Test
    public void expanded111VariantNeedsItsOwnWholeSequenceAndAllEightInlinePositions() {
        String expanded = "4583e698c6aa1a0f2412a4ced8a370ef45f2c578113f849eda688023e8456100";
        String original = "f1c7cf5b167cc3d2ad3fa98840f90380460cfae3830a0e1b16c64f374ee30588";
        assertTrue(BalanceCanvasSession.Policy.pinnedSequence(111, expanded));
        assertFalse(BalanceCanvasSession.Policy.pinnedSequence(57, expanded));
        assertFalse(BalanceCanvasSession.Policy.pinnedSequence(111, original));
        assertFalse(BalanceCanvasSession.Policy.pinnedSequence(110, expanded));
        int[] order = {11, 27, 45, 51, 68, 77, 82, 86};
        assertTrue(BalanceCanvasSession.Policy.textOrderKnown(111, order, 8));
        assertTrue(BalanceCanvasSession.Policy.textOrderKnown(57, order, 4));
        assertFalse(BalanceCanvasSession.Policy.textOrderKnown(111, order, 4));
        assertFalse(BalanceCanvasSession.Policy.textOrderKnown(57, order, 8));
        order[7] = 87;
        assertFalse(BalanceCanvasSession.Policy.textOrderKnown(111, order, 8));
        String[] types = new String[111];
        java.util.Arrays.fill(types, "com.antfin.cube.cubecore.draw.CRViewDrawCmd");
        String fingerprint = BalanceCanvasSession.Policy.classSequenceFingerprint(types);
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        types[110] = "com.antfin.cube.cubecore.draw.CRTextViewDrawCmdInline";
        assertNotEquals(fingerprint, BalanceCanvasSession.Policy.classSequenceFingerprint(types));
        assertEquals(
                "unavailable",
                BalanceCanvasSession.Policy.classSequenceFingerprint(new String[112]));
    }

    @Test
    public void expanded111StillSelectsOnlyUniqueMain27AndPreservesEveryOtherCommand() {
        assertTrue(BalanceCanvasSession.Policy.shape(111, 1, 1, 11, 27, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(111, 1, 2, 11, 27, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(111, 1, 1, 11, 77, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(111, 2, 1, 11, 27, true, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(111, 1, 1, 11, 27, false, 0));
        assertFalse(BalanceCanvasSession.Policy.shape(111, 1, 1, 11, 27, true, 1));
        assertFalse(BalanceCanvasSession.Policy.shape(110, 1, 1, 11, 27, true, 0));
        CubeTextPolicy.Classification other = CubeTextPolicy.classifyText("提示 1", "1234.56");
        assertTrue(other.hasNumber);
        assertFalse(other.pureAmount);
        Object[] source = new Object[111];
        for (int index = 0; index < source.length; index++) source[index] = new Object();
        Object[] own = source.clone();
        own[27] = new Object();
        assertTrue(BalanceCanvasSession.Policy.onlyTargetChanged(source, own, 27));
        for (int index = 0; index < source.length; index++)
            if (index != 27) assertSame(source[index], own[index]);
        own[77] = new Object();
        assertFalse(BalanceCanvasSession.Policy.onlyTargetChanged(source, own, 27));
    }

    @Test
    public void finiteMetricsAndFullConfiguredWidthMustFit() {
        assertTrue(BalanceCanvasSession.Policy.metrics(32, 300, 0, 80, 80));
        assertFalse(BalanceCanvasSession.Policy.metrics(Float.NaN, 300, 0, 80, 80));
        assertFalse(BalanceCanvasSession.Policy.metrics(32, 0, 0, 80, 80));
        assertTrue(BalanceCanvasSession.Policy.fits(20, 280, 300));
        assertFalse(BalanceCanvasSession.Policy.fits(20, 281, 300));
        assertFalse(BalanceCanvasSession.Policy.fits(-1, 80, 300));
        assertFalse(BalanceCanvasSession.Policy.fits(0, Float.POSITIVE_INFINITY, 300));
    }

    @Test
    public void sourceLineAndRunMatchRemainIndependentFiniteChecks() {
        assertTrue(BalanceCanvasSession.Policy.widthMatches(80, 81.5f));
        assertFalse(BalanceCanvasSession.Policy.widthMatches(80, 81.501f));
        assertFalse(BalanceCanvasSession.Policy.widthMatches(Float.NaN, 80));
        assertFalse(BalanceCanvasSession.Policy.widthMatches(80, Float.POSITIVE_INFINITY));
        assertFalse(BalanceCanvasSession.Policy.widthMatches(0, 0));
        assertTrue(BalanceCanvasSession.Policy.measuredWidths(80, 100));
        assertFalse(BalanceCanvasSession.Policy.measuredWidths(80, -1));
    }

    @Test
    public void blankProtectionNeedsEveryOtherGateAndRetriesAreBounded() {
        assertTrue(BalanceCanvasSession.Policy.metricsPending(true, false));
        assertFalse(BalanceCanvasSession.Policy.metricsPending(false, false));
        assertFalse(BalanceCanvasSession.Policy.metricsPending(true, true));
        assertTrue(BalanceCanvasSession.Policy.retryAllowed(0, 0));
        assertTrue(BalanceCanvasSession.Policy.retryAllowed(1999, 39));
        assertFalse(BalanceCanvasSession.Policy.retryAllowed(2000, 39));
        assertFalse(BalanceCanvasSession.Policy.retryAllowed(100, 40));
        assertFalse(BalanceCanvasSession.Policy.retryAllowed(-1, 0));
    }

    @Test
    public void delayedExpiryAndAttemptExhaustionStayTerminalForTheSameBudget() {
        BalanceCanvasSession.RetryBudget delayed = new BalanceCanvasSession.RetryBudget(100);
        assertTrue(delayed.claim(100));
        assertTrue(delayed.expired(2101));
        assertFalse(delayed.claim(2102));
        assertFalse(delayed.claim(10000));
        BalanceCanvasSession.RetryBudget counted = new BalanceCanvasSession.RetryBudget(100);
        for (int index = 0; index < 40; index++) assertTrue(counted.claim(100 + index));
        assertFalse(counted.claim(200));
        assertFalse(counted.claim(201));
        BalanceCanvasSession.RetryBudget nextRevision = new BalanceCanvasSession.RetryBudget(10000);
        assertTrue(nextRevision.claim(10000));
        nextRevision.stop();
        assertFalse(nextRevision.claim(10001));
    }

    @Test
    public void configPendingProtectsOnlyTheExactAlreadyAcceptedShape() {
        assertTrue(BalanceCanvasSession.Policy.protectPending(true, true));
        assertFalse(BalanceCanvasSession.Policy.protectPending(true, false));
        assertFalse(BalanceCanvasSession.Policy.protectPending(false, true));
    }

    @Test
    public void knownSafeMainBlanksWhenFontOrFitCannotSupportConfiguredText() {
        assertTrue(BalanceCanvasSession.Policy.shouldBlank(true, false, false));
        assertTrue(BalanceCanvasSession.Policy.shouldBlank(true, true, true));
        assertFalse(BalanceCanvasSession.Policy.shouldBlank(true, true, false));
        assertFalse(BalanceCanvasSession.Policy.shouldBlank(false, false, true));
        assertEquals(
                "font",
                BalanceCanvasSession.Policy.blankReason(false, true, false, false, false, false));
        assertEquals(
                "fit",
                BalanceCanvasSession.Policy.blankReason(false, true, true, true, true, false));
        assertEquals(
                "config",
                BalanceCanvasSession.Policy.blankReason(true, true, false, false, false, false));
        assertEquals(
                "metrics",
                BalanceCanvasSession.Policy.blankReason(false, true, true, true, true, true));
        assertEquals(
                "none",
                BalanceCanvasSession.Policy.blankReason(false, false, true, true, true, true));
    }

    @Test
    public void fontAndMetricRetryNeverExtendsToKnownFitFailureOrUnknownMain() {
        assertTrue(
                BalanceCanvasSession.Policy.retryForBlank(true, false, false, false, false, false));
        assertTrue(BalanceCanvasSession.Policy.retryForBlank(true, true, true, true, true, true));
        assertFalse(
                BalanceCanvasSession.Policy.retryForBlank(true, true, true, true, false, false));
        assertFalse(
                BalanceCanvasSession.Policy.retryForBlank(true, false, false, true, false, true));
        assertFalse(
                BalanceCanvasSession.Policy.retryForBlank(false, false, false, false, false, true));
    }

    @Test
    public void fallbackFenceRequiresCurrentKnownHostSlotAndNeverSuppressesOwnReentry() {
        assertTrue(BalanceCanvasSession.Policy.suppressKnown(true, false, true, true, true, false));
        assertTrue(BalanceCanvasSession.Policy.suppressKnown(false, true, true, true, true, false));
        assertFalse(
                BalanceCanvasSession.Policy.suppressKnown(false, false, true, true, true, false));
        assertFalse(
                BalanceCanvasSession.Policy.suppressKnown(true, false, false, true, true, false));
        assertFalse(
                BalanceCanvasSession.Policy.suppressKnown(true, false, true, false, true, false));
        assertFalse(
                BalanceCanvasSession.Policy.suppressKnown(true, false, true, true, false, false));
        assertFalse(BalanceCanvasSession.Policy.suppressKnown(true, false, true, true, true, true));
    }

    @Test
    public void fallbackFenceRejectsPictureBitmapDemotionAndNonCommandPaths() {
        assertTrue(BalanceCanvasSession.Policy.fenceShape(true, true, true, true, true));
        assertFalse(BalanceCanvasSession.Policy.fenceShape(false, true, true, true, true));
        assertFalse(BalanceCanvasSession.Policy.fenceShape(true, false, true, true, true));
        assertFalse(BalanceCanvasSession.Policy.fenceShape(true, true, false, true, true));
        assertFalse(BalanceCanvasSession.Policy.fenceShape(true, true, true, false, true));
        assertFalse(BalanceCanvasSession.Policy.fenceShape(true, true, true, true, false));
    }

    @Test
    public void pendingBlankCanProtectAnOldDisabledSnapshotButExpiresWhenReadingEnds() {
        assertTrue(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, true, true, false));
        assertTrue(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, true, true, true));
        assertFalse(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, true, false, true));
        assertFalse(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, true, false, false));
        assertFalse(BalanceCanvasSession.Policy.drawProofCurrent(4, 5, true, true, true));
    }

    @Test
    public void normalProofSurvivesPendingStartButExpiresOnPublishOrDisable() {
        assertTrue(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, false, false, true));
        assertTrue(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, false, true, true));
        assertFalse(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, false, true, false));
        assertFalse(BalanceCanvasSession.Policy.drawProofCurrent(4, 4, false, false, false));
        assertFalse(BalanceCanvasSession.Policy.drawProofCurrent(4, 5, false, false, true));
    }

    @Test
    public void pendingStartBetweenCloneEntryAndLeaseDoesNotFallBackToHostCanvas() {
        Object host = new Object(), provenPrivate = new Object();
        Object slot = host;
        boolean capturedPending = false, enabled = true;
        long capturedRevision = 4, publishedRevision = 4;
        assertTrue(
                BalanceCanvasSession.Policy.pendingProofCurrent(capturedPending, false, enabled));
        // IO线程请求新读取，但尚未发布；入口与最终lease核验均继续接受同代副本。
        boolean nowPending = true;
        assertTrue(
                BalanceCanvasSession.Policy.pendingProofCurrent(
                        capturedPending, nowPending, enabled));
        if (BalanceCanvasSession.Policy.drawProofCurrent(
                        capturedRevision, publishedRevision, capturedPending, nowPending, enabled)
                && slot == host) slot = provenPrivate;
        assertSame(provenPrivate, slot);
        assertFalse(
                BalanceCanvasSession.Policy.drawProofCurrent(
                        capturedRevision,
                        ++publishedRevision,
                        capturedPending,
                        nowPending,
                        enabled));
        assertFalse(
                BalanceCanvasSession.Policy.drawProofCurrent(
                        capturedRevision, capturedRevision, capturedPending, nowPending, false));
    }

    @Test
    public void decorationNoneUsesVerifiedValueOneAndOtherBitsStayRejected() {
        assertEquals("none", BalanceCanvasSession.Policy.decoration(1));
        for (int value : new int[] {0, 2, 3, 4, 8, 15, -1})
            assertNotEquals("none", BalanceCanvasSession.Policy.decoration(value));
        assertEquals("underline", BalanceCanvasSession.Policy.decoration(2));
    }

    @Test
    public void privateListChangesExactlyOneTargetAndNeverChangesSourceReferences() {
        Object a = new Object(), b = new Object(), c = new Object(), own = new Object();
        Object[] source = {a, b, c}, copy = source.clone();
        copy[1] = own;
        assertTrue(BalanceCanvasSession.Policy.onlyTargetChanged(source, copy, 1));
        assertTrue(BalanceCanvasSession.Policy.sameEntries(new Object[] {a, b, c}, source));
        assertFalse(BalanceCanvasSession.Policy.onlyTargetChanged(source, source, 1));
        copy[2] = new Object();
        assertFalse(BalanceCanvasSession.Policy.onlyTargetChanged(source, copy, 1));
        assertFalse(BalanceCanvasSession.Policy.onlyTargetChanged(source, copy, -1));
        assertFalse(BalanceCanvasSession.Policy.canvasSize(Double.NaN, 200));
        assertFalse(BalanceCanvasSession.Policy.canvasSize(100, 0));
        assertTrue(BalanceCanvasSession.Policy.canvasSize(1080, 2400));
    }

    @Test
    public void unrelatedStartupCanvasesNeverConsumeMainAmountAuditBudget() {
        BalanceCanvasSession.AuditBudget budget = new BalanceCanvasSession.AuditBudget();
        for (int count = 0; count < 1000; count++) {
            budget.accept(82, 0, false);
            budget.accept(63, 4, false);
        }
        for (int count = 0; count < 96; count++) assertTrue(budget.accept(57, 4, false));
        assertFalse(budget.accept(57, 4, false));
        for (int count = 0; count < 96; count++) assertTrue(budget.accept(111, 8, false));
        assertFalse(budget.accept(111, 8, false));
        assertFalse(budget.accept(82, 0, false));
        assertFalse(budget.accept(63, 4, false));
    }

    @Test
    public void ownedReentrySkipsOnlyOwnCurrentSlotAndHostReplacementGetsAnotherLease() {
        Object firstHost = new Object(),
                firstOwn = new Object(),
                secondHost = new Object(),
                secondOwn = new Object();
        assertFalse(BalanceCanvasSession.Policy.ownedReentry(firstHost, null));
        assertTrue(BalanceCanvasSession.Policy.ownedReentry(firstOwn, firstOwn));
        assertFalse(BalanceCanvasSession.Policy.ownedReentry(secondHost, firstOwn));
        assertTrue(BalanceCanvasSession.Policy.ownedReentry(secondOwn, secondOwn));
        // 内层归还新host后，外层旧own不能覆盖它。
        assertFalse(BalanceCanvasSession.Policy.ownedReentry(secondHost, firstOwn));
    }
}
