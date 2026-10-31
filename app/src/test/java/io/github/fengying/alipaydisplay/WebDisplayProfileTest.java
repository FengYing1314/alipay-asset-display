package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class WebDisplayProfileTest {
    @Test
    public void unknownOrIncompleteIdentitiesNeverChooseAProfile() {
        assertNull(WebDisplayProfile.match(null, null, null, null));
        assertNull(
                WebDisplayProfile.match(
                        "2060090000375934",
                        "0.2.2610131729.24",
                        "https://example.invalid/",
                        "https://example.invalid/"));
        assertNull(WebDisplayProfile.match("68688004", "0.1.2610262057.8", null, null));
        assertFalse(WebDisplayProfile.acceptsDocument(null));
        assertFalse(WebDisplayProfile.acceptsDocument("https://example.invalid/"));
    }
}
