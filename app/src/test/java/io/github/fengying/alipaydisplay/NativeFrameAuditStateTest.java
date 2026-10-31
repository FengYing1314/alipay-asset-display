package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeFrameAuditStateTest {
    @Test
    public void activeVisibleBudgetCannotConsumePausedReserve() {
        NativeFrameAuditState state = new NativeFrameAuditState();
        for (int i = 0; i < NativeFrameAuditState.ACTIVE_VISIBLE_LIMIT; i++) {
            assertTrue(state.begin(NativeFrameAuditState.Phase.ACTIVE));
            state.observed(NativeFrameAuditState.Phase.ACTIVE, true, true, true, 1, 3);
        }
        assertFalse(state.begin(NativeFrameAuditState.Phase.ACTIVE));
        assertTrue(state.exhausted(NativeFrameAuditState.Phase.ACTIVE));
        assertTrue(state.begin(NativeFrameAuditState.Phase.PAUSED));
        assertEquals(0, state.budget(NativeFrameAuditState.Phase.PAUSED).visibleDraws);
    }

    @Test
    public void missingAndAmbiguousRootsAreBoundedWithoutVisibleSamples() {
        NativeFrameAuditState state = new NativeFrameAuditState();
        for (int i = 0; i < NativeFrameAuditState.ACTIVE_ATTEMPT_LIMIT; i++) {
            assertTrue(state.begin(NativeFrameAuditState.Phase.ACTIVE));
            state.observed(
                    NativeFrameAuditState.Phase.ACTIVE, true, true, true, i % 2 == 0 ? 0 : 2, 0);
        }
        NativeFrameAuditState.Budget budget = state.budget(NativeFrameAuditState.Phase.ACTIVE);
        assertFalse(state.begin(NativeFrameAuditState.Phase.ACTIVE));
        assertEquals(0, budget.visibleDraws);
        assertEquals(180, budget.rootMissingDraws);
        assertEquals(180, budget.rootAmbiguousDraws);
        assertEquals(360, budget.noVisibleTargetDraws);
    }

    @Test
    public void incompleteDiscoveryCannotBeReportedAsMissingRoot() {
        NativeFrameAuditState state = new NativeFrameAuditState();
        state.begin(NativeFrameAuditState.Phase.ACTIVE);
        state.observed(NativeFrameAuditState.Phase.ACTIVE, false, false, false, 0, 0);
        NativeFrameAuditState.Budget budget = state.budget(NativeFrameAuditState.Phase.ACTIVE);
        assertEquals(1, budget.incompleteDiscoveryDraws);
        assertEquals(0, budget.rootMissingDraws);
        assertEquals(1, budget.configPendingDraws);
        assertEquals(0, budget.disabledDraws);
    }

    @Test
    public void unboundFirstVisibleSourceIsCounted() {
        NativeFrameAuditState.Counts counts = new NativeFrameAuditState.Counts();
        NativeFrameAuditState.Sample sample = ready();
        sample.textNumeric = true;
        sample.layoutPresent = true;
        sample.layoutNumeric = true;
        sample.descriptionPresent = true;
        sample.descriptionDigits = true;
        counts.record(sample);
        assertSame(sample, counts.first);
        assertFalse(counts.first.bound);
        assertEquals(1, counts.unbound);
        assertEquals(1, counts.unexpectedTextNumeric);
        assertEquals(1, counts.unexpectedLayoutNumeric);
        assertEquals(1, counts.unexpectedDescriptionDigits);
    }

    @Test
    public void pendingAndDisabledSamplesNeverBecomeCheckedSamples() {
        NativeFrameAuditState.Counts counts = new NativeFrameAuditState.Counts();
        NativeFrameAuditState.Sample pending = ready();
        pending.configReady = false;
        pending.textMatches = true;
        counts.record(pending);
        NativeFrameAuditState.Sample disabled = ready();
        disabled.enabled = false;
        counts.record(disabled);
        assertEquals(2, counts.samples);
        assertEquals(1, counts.configPending);
        assertEquals(1, counts.disabled);
        assertEquals(0, counts.checked);
        assertEquals(0, counts.textMatches);
        assertFalse(counts.first.configReady);
    }

    @Test
    public void matchingFieldDoesNotHideMissingOrStaleDrawLayout() {
        NativeFrameAuditState.Counts counts = new NativeFrameAuditState.Counts();
        NativeFrameAuditState.Sample missing = ready();
        missing.textMatches = true;
        counts.record(missing);
        NativeFrameAuditState.Sample stale = ready();
        stale.textMatches = true;
        stale.layoutPresent = true;
        stale.layoutNumeric = true;
        counts.record(stale);
        assertEquals(2, counts.textMatches);
        assertEquals(1, counts.layoutMissing);
        assertEquals(1, counts.unexpectedLayoutNumeric);
        assertEquals(0, counts.layoutMatches);
    }

    @Test
    public void hiddenDigitsAreCountedAcrossTextLayoutAndDescription() {
        NativeFrameAuditState.Counts counts = new NativeFrameAuditState.Counts();
        NativeFrameAuditState.Sample sample = ready();
        sample.textMasked = true;
        sample.privacyKnown = true;
        sample.privacyHidden = true;
        sample.layoutDigits = true;
        counts.record(sample);
        assertEquals(1, counts.privacyHidden);
        assertEquals(1, counts.hiddenDigits);
        assertEquals(1, counts.textMasked);
    }

    @Test
    public void unknownPrivacyAndNoSamplesStayExplicitlyUnknown() {
        NativeFrameAuditState.Counts empty = new NativeFrameAuditState.Counts();
        assertNull(empty.first);
        assertEquals(0, empty.samples);
        NativeFrameAuditState.Sample sample = ready();
        sample.textMatches = true;
        sample.layoutMatches = true;
        sample.descriptionMatches = true;
        empty.record(sample);
        assertEquals(1, empty.privacyUnknown);
        assertEquals(0, empty.privacyHidden);
        assertEquals(0, empty.hiddenDigits);
    }

    @Test
    public void pausedVisibleBudgetIsAlsoFinite() {
        NativeFrameAuditState state = new NativeFrameAuditState();
        for (int i = 0; i < NativeFrameAuditState.PAUSED_VISIBLE_LIMIT; i++) {
            assertTrue(state.begin(NativeFrameAuditState.Phase.PAUSED));
            state.observed(NativeFrameAuditState.Phase.PAUSED, true, true, true, 1, 1);
        }
        assertFalse(state.begin(NativeFrameAuditState.Phase.PAUSED));
        assertEquals(30, state.budget(NativeFrameAuditState.Phase.PAUSED).visibleDraws);
    }

    private static NativeFrameAuditState.Sample ready() {
        NativeFrameAuditState.Sample sample = new NativeFrameAuditState.Sample();
        sample.configReady = true;
        sample.enabled = true;
        return sample;
    }
}
