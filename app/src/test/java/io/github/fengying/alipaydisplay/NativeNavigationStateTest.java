package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeNavigationStateTest {
    @Test
    public void falseNavigationBeforePositiveEvidenceNeverSuspends() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object();
        assertEquals(
                NativeNavigationState.Outcome.UNKNOWN,
                state.observe(host, page, true, false, false, false));
        assertFalse(state.isSuspended());
        assertFalse(state.trusts(host, page));
    }

    @Test
    public void wholePageTemporarilyHiddenWhileCurrentDoesNotLeave() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object();
        state.observe(host, page, true, true, true, false);
        assertEquals(
                NativeNavigationState.Outcome.UNKNOWN,
                state.observe(host, page, true, true, false, false));
        assertFalse(state.isSuspended());
        assertEquals(
                NativeNavigationState.Outcome.CURRENT,
                state.observe(host, page, true, true, true, false));
    }

    @Test
    public void confirmedLeaveAndReturnEachTransitionOnce() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object();
        state.observe(host, page, true, true, true, false);
        assertEquals(
                NativeNavigationState.Outcome.LEFT,
                state.observe(host, page, true, false, false, false));
        assertTrue(state.isSuspended());
        assertEquals(
                NativeNavigationState.Outcome.AWAY,
                state.observe(host, page, true, false, false, false));
        assertEquals(
                NativeNavigationState.Outcome.ENTERED,
                state.observe(host, page, true, true, true, false));
        assertFalse(state.isSuspended());
        assertEquals(
                NativeNavigationState.Outcome.CURRENT,
                state.observe(host, page, true, true, true, false));
    }

    @Test
    public void bindingOrRenderingDefersLeaveUntilOutsideBatch() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object();
        state.observe(host, page, true, true, true, false);
        assertEquals(
                NativeNavigationState.Outcome.DEFERRED,
                state.observe(host, page, true, false, false, true));
        assertFalse(state.isSuspended());
        assertEquals(
                NativeNavigationState.Outcome.LEFT,
                state.observe(host, page, true, false, false, false));
    }

    @Test
    public void unknownGetterOrChangedHostCannotInvalidateTrustedPage() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object();
        state.observe(host, page, true, true, true, false);
        assertEquals(
                NativeNavigationState.Outcome.UNKNOWN,
                state.observe(host, page, false, false, false, false));
        assertEquals(
                NativeNavigationState.Outcome.UNKNOWN,
                state.observe(new Object(), page, true, false, false, false));
        assertFalse(state.isSuspended());
    }

    @Test
    public void replacementPageRequiresItsOwnPositiveEvidence() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object(), replacement = new Object();
        state.observe(host, page, true, true, true, false);
        assertEquals(
                NativeNavigationState.Outcome.UNKNOWN,
                state.observe(host, replacement, true, false, false, false));
        assertFalse(state.isSuspended());
        state.observe(host, replacement, true, true, true, false);
        assertTrue(state.trusts(host, replacement));
        assertEquals(
                NativeNavigationState.Outcome.LEFT,
                state.observe(host, replacement, true, false, false, false));
    }

    @Test
    public void suspendedPageStaysSuspendedUntilPositiveReturn() {
        NativeNavigationState state = new NativeNavigationState();
        Object host = new Object(), page = new Object();
        state.observe(host, page, true, true, true, false);
        state.observe(host, page, true, false, false, false);
        state.observe(host, page, false, true, true, false);
        assertTrue(state.isSuspended());
        assertEquals(
                NativeNavigationState.Outcome.DEFERRED,
                state.observe(host, page, true, true, true, true));
        assertTrue(state.isSuspended());
        assertEquals(
                NativeNavigationState.Outcome.ENTERED,
                state.observe(host, page, true, true, true, false));
    }
}
