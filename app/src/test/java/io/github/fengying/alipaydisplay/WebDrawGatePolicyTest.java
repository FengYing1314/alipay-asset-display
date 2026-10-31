package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class WebDrawGatePolicyTest {
    @Test
    public void completeApplyStillWaitsForRealVisualCallback() {
        WebDrawGate gate = new WebDrawGate(0L);
        assertTrue(gate.suppress(0L));
        assertTrue(gate.reply(4, 4, 4, 0, 0, 0, 100L));
        assertEquals(WebDrawGate.State.WAIT_VISUAL, gate.state);
        assertTrue(gate.suppress(4499L));
        assertTrue(gate.visualReady(4500L));
        assertFalse(gate.suppress(4501L));
        assertEquals(WebDrawGate.State.READY, gate.state);
    }

    @Test
    public void hiddenTargetsAreAccountedButUnprovedWaitingNeverReleases() {
        WebDrawGate hidden = new WebDrawGate(0L);
        assertTrue(hidden.reply(4, 4, 1, 3, 0, 0, 10L));
        assertTrue(hidden.visualReady(11L));
        WebDrawGate waiting = new WebDrawGate(0L);
        assertFalse(waiting.reply(4, 4, 3, 0, 1, 0, 10L));
        assertFalse(waiting.visualReady(11L));
        assertTrue(waiting.suppress(12L));
        assertTrue(waiting.reply(4, 4, 3, 1, 0, 0, 13L));
        assertTrue(waiting.visualReady(14L));
    }

    @Test
    public void oneTargetProfilesUseTheirOwnCount() {
        WebDrawGate gate = new WebDrawGate(0L);
        assertTrue(gate.reply(1, 1, 1, 0, 0, 0, 1L));
        assertTrue(gate.visualReady(2L));
        WebDrawGate wrongCount = new WebDrawGate(0L);
        assertFalse(wrongCount.reply(1, 4, 4, 0, 0, 0, 1L));
        assertEquals(WebDrawGate.Failure.INVALID_REPLY, wrongCount.failure);
    }

    @Test
    public void skeletonWaitsWithinOriginalDeadlineThenCanBecomeComplete() {
        WebDrawGate unknown = new WebDrawGate(0L);
        assertFalse(unknown.reply(4, 0, 0, 0, 0, 4, 1L));
        assertTrue(unknown.suppress(2L));
        assertFalse(unknown.reply(4, 3, 3, 0, 0, 1, 1L));
        assertTrue(unknown.suppress(1000L));
        assertTrue(unknown.reply(4, 4, 4, 0, 0, 0, 1001L));
        assertTrue(unknown.visualReady(1002L));
        WebDrawGate invalid = new WebDrawGate(0L);
        assertFalse(invalid.reply(4, 4, 4, 1, 0, 0, 1L));
        assertEquals(WebDrawGate.Failure.INVALID_REPLY, invalid.failure);
    }

    @Test
    public void persistentUnknownShapeExpiresWithoutExtendingTheBudget() {
        WebDrawGate gate = new WebDrawGate(0L);
        assertFalse(gate.reply(4, 0, 0, 0, 0, 4, 100L));
        assertFalse(gate.reply(4, 3, 3, 0, 0, 1, 4900L));
        assertTrue(gate.suppress(4999L));
        assertFalse(gate.suppress(5000L));
        assertTrue(gate.timedOut);
        assertEquals(WebDrawGate.Failure.UNKNOWN_SHAPE, gate.failure);
        assertFalse(gate.visualReady(5001L));
    }

    @Test
    public void newUnknownReplyRevokesTheEarlierVisualEligibility() {
        WebDrawGate gate = new WebDrawGate(0L);
        assertTrue(gate.reply(4, 4, 4, 0, 0, 0, 1L));
        assertFalse(gate.reply(4, 3, 3, 0, 0, 1, 2L));
        assertFalse(gate.visualReady(3L));
        assertEquals(WebDrawGate.State.WAIT_APPLY, gate.state);
        assertTrue(gate.reply(4, 4, 3, 1, 0, 0, 4L));
        assertTrue(gate.visualReady(5L));
    }

    @Test
    public void timeoutCannotBeReclassifiedByLateVisualAck() {
        WebDrawGate gate = new WebDrawGate(100L);
        assertTrue(gate.reply(1, 1, 1, 0, 0, 0, 200L));
        assertEquals(5000L, WebDrawGate.DEADLINE_MS);
        assertEquals(1L, gate.remaining(5099L));
        assertTrue(gate.suppress(5099L));
        assertFalse(gate.suppress(5100L));
        assertFalse(gate.visualReady(5101L));
        assertEquals(WebDrawGate.Failure.TIMEOUT, gate.failure);
    }

    @Test
    public void retriesDoNotExtendAnIncompleteDocumentsDeadline() {
        WebDrawGate gate = new WebDrawGate(0L);
        assertTrue(gate.reply(4, 4, 4, 0, 0, 0, 100L));
        gate.invalidateProof(4900L);
        assertTrue(gate.suppress(4999L));
        assertFalse(gate.suppress(5000L));
        gate.invalidateProof(5001L);
        assertFalse(gate.suppress(5002L));
    }

    @Test
    public void changedNonceRequiresANewCompleteReplyAndVisualAck() {
        WebDrawGate gate = new WebDrawGate(0L);
        assertTrue(gate.reply(1, 1, 1, 0, 0, 0, 1L));
        assertTrue(gate.visualReady(2L));
        gate.invalidateProof(3000L);
        assertTrue(gate.suppress(3001L));
        assertFalse(gate.visualReady(3002L));
        assertTrue(gate.reply(1, 1, 0, 1, 0, 0, 3003L));
        assertTrue(gate.visualReady(3004L));
    }

    @Test
    public void unavailableVisualApiAndClockRollbackNeverBecomeReady() {
        WebDrawGate unavailable = new WebDrawGate(0L);
        unavailable.fail(WebDrawGate.Failure.VISUAL_UNAVAILABLE);
        assertFalse(unavailable.visualReady(1L));
        assertFalse(unavailable.suppress(1L));
        WebDrawGate rollback = new WebDrawGate(100L);
        assertFalse(rollback.suppress(99L));
        assertEquals(WebDrawGate.Failure.TIMEOUT, rollback.failure);
    }

    @Test
    public void callbackAfterFirstReleasedDrawRemainsLateAndUnproven() {
        WebDrawGate gate = new WebDrawGate(100L);
        assertTrue(gate.reply(4, 4, 4, 0, 0, 0, 200L));
        gate.visualRequestedAt = 200L;
        assertFalse(gate.suppress(5100L));
        gate.drawReleased(5110L);
        assertFalse(gate.onTime(5111L));
        assertEquals(5011L, gate.elapsed(5111L));
        assertFalse(gate.visualReady(5111L));
        assertEquals(WebDrawGate.State.FAILED, gate.state);
        gate.drawReleased(5120L);
        assertEquals(5110L, gate.firstReleasedDrawAt);
        assertEquals(2, gate.releasedDraws);
    }
}
