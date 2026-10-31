package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class TotalAssetsProfileTest {
    @Test
    public void incompleteAndDifferentIdentitiesNeverEnableRenderer() {
        assertFalse(TotalAssetsProfile.accepts(null, null, null));
        assertFalse(TotalAssetsProfile.accepts("20000243", "6.4.431", "https://example.invalid/"));
        assertFalse(
                TotalAssetsProfile.accepts(
                        "68688004", "0.1.2209022010.11", "https://example.invalid/"));
        assertFalse(TotalAssetsProfile.accepts("68688004", "0.1.2209022010.11", null));
    }
}
