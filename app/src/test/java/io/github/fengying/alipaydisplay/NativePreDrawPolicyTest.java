package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativePreDrawPolicyTest {
    @Test
    public void currentConfigDigitsNeedOwnershipTrustedSourceAndSettledPrivacy() {
        assertTrue(NativeDisplaySession.PreDrawPolicy.safeField(true, true, true, true, false));
        assertFalse(NativeDisplaySession.PreDrawPolicy.safeField(true, true, false, true, false));
        assertFalse(NativeDisplaySession.PreDrawPolicy.safeField(true, true, true, false, false));
        assertFalse(NativeDisplaySession.PreDrawPolicy.safeField(true, true, true, true, true));
        assertFalse(NativeDisplaySession.PreDrawPolicy.safeField(true, false, true, true, false));
    }

    @Test
    public void maskedOrEmptyFieldDoesNotRequireReplayingSource() {
        assertTrue(NativeDisplaySession.PreDrawPolicy.safeField(false, false, false, false, true));
        assertTrue(NativeDisplaySession.PreDrawPolicy.safeField(false, false, true, false, false));
    }

    @Test
    public void missingLayoutIsNeverSafeEvenWhenCurrentTextMatches() {
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        false, false, false, true, true, false));
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        false, true, true, true, true, false));
    }

    @Test
    public void staleLayoutDigitsAreUnsafeDespiteCorrectCurrentText() {
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        true, true, false, true, true, false));
        assertTrue(
                NativeDisplaySession.PreDrawPolicy.safeLayout(true, true, true, true, true, false));
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        true, true, true, true, false, false));
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(true, true, true, true, true, true));
    }

    @Test
    public void safeLayoutCannotExcuseUnsafeCurrentText() {
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        true, false, false, false, true, false));
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        true, true, true, false, true, false));
        assertTrue(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        true, false, false, true, false, true));
    }

    @Test
    public void pageDeferralEndsAfterTwoFramesAndDrawFenceRemainsIndependent() {
        assertTrue(NativeDisplaySession.PreDrawPolicy.deferLayout(true, 0));
        assertTrue(NativeDisplaySession.PreDrawPolicy.deferLayout(true, 1));
        assertFalse(NativeDisplaySession.PreDrawPolicy.deferLayout(true, 2));
        assertFalse(NativeDisplaySession.PreDrawPolicy.deferLayout(true, 100));
        assertFalse(NativeDisplaySession.PreDrawPolicy.deferLayout(false, 0));
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeLayout(
                        true, true, false, true, true, false));
    }

    @Test
    public void hiddenEpochCannotReuseTrustedDigitsAfterTemporaryMask() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", null, true, false);
        assertTrue(state.canRender());
        state.hostPrivacy(true);
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeField(
                        true, true, true, state.canRender(), false));
        state.hostPrivacy(false);
        assertFalse(state.canRender());
        assertFalse(
                NativeDisplaySession.PreDrawPolicy.safeField(
                        true, true, true, state.canRender(), false));
        state.hostText("13.00", true);
        assertTrue(
                NativeDisplaySession.PreDrawPolicy.safeField(
                        true, true, true, state.canRender(), false));
    }

    @Test
    public void temporaryMaskOnlyRetainsAnExistingOwnedTrustedSource() {
        assertTrue(NativeDisplaySession.PreDrawPolicy.retainTrustedMask(true, true, true, true));
        assertFalse(NativeDisplaySession.PreDrawPolicy.retainTrustedMask(false, true, true, true));
        assertFalse(NativeDisplaySession.PreDrawPolicy.retainTrustedMask(true, false, true, true));
        assertFalse(NativeDisplaySession.PreDrawPolicy.retainTrustedMask(true, true, false, true));
        assertFalse(NativeDisplaySession.PreDrawPolicy.retainTrustedMask(true, true, true, false));
    }
}
