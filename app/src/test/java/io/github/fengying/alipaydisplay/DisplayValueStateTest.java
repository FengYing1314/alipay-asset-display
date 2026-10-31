package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class DisplayValueStateTest {
    @Test
    public void hostTextInvalidatesPreviousSpokenAmount() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", "金额12.00", true, false);
        state.hostText("25.00", true);
        assertTrue(state.canRender());
        assertEquals("25.00", state.hostText());
        assertNull(state.hostDescription());
        state.hostDescription("金额25.00", false);
        assertEquals("金额25.00", state.hostDescription());
    }

    @Test
    public void hiddenDescriptionDiscardsCachedNumberUntilFreshHostText() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", null, true, false);
        state.hostDescription("金额已隐藏", true);
        assertFalse(state.canRender());
        assertNull(state.hostText());
        state.hostDescription("金额", false);
        assertFalse(state.canRender());
        state.hostText("18.00", true);
        assertTrue(state.canRender());
        assertEquals("18.00", state.hostText());
    }

    @Test
    public void maskedHostTextAndRebindingNeverResurrectCachedNumber() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", null, true, false);
        state.hostText("****", false);
        assertFalse(state.canRender());
        assertNull(state.hostText());
        state.hostDescription("金额", false);
        assertFalse(state.canRender());
        state.hostText("0.00", true);
        assertTrue(state.canRender());
        state.invalidate();
        assertFalse(state.canRender());
        assertNull(state.hostText());
        assertNull(state.hostDescription());
    }

    @Test
    public void staleVisibleTextCannotOverrideHiddenDescription() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", "隐藏", true, true);
        assertFalse(state.canRender());
        state.hostText("24.00", true);
        assertNull(state.hostText());
        state.hostDescription(null, false);
        assertFalse(state.canRender());
    }

    @Test
    public void visibleRebindingPreservesCurrentTrustedSource() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", "金额12.00", true, false);
        state.hostPrivacy(false);
        state.hostPrivacy(false);
        assertTrue(state.canRender());
        assertEquals("12.00", state.hostText());
        assertEquals("金额12.00", state.hostDescription());
        state.hostText("25.00", true);
        state.hostPrivacy(false);
        assertEquals("25.00", state.hostText());
        assertNull(state.hostDescription());
    }

    @Test
    public void privacyHiddenDropsSourceAndRejectsIntermediateNumericWrites() {
        DisplayValueState<String> state = new DisplayValueState<>("12.00", "金额12.00", true, false);
        state.hostPrivacy(true);
        assertFalse(state.canRender());
        assertNull(state.hostText());
        assertNull(state.hostDescription());
        state.hostText("24.00", true);
        assertNull(state.hostText());
        state.hostDescription("金额24.00", false);
        assertNull(state.hostDescription());
        state.hostPrivacy(false);
        assertFalse(state.canRender());
        state.hostDescription("金额", false);
        assertFalse(state.canRender());
        state.hostText("25.00", true);
        assertTrue(state.canRender());
        assertEquals("25.00", state.hostText());
    }
}
