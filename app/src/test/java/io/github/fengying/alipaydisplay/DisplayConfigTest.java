package io.github.fengying.alipaydisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class DisplayConfigTest {
    @Test
    public void preservesDecimalPrecisionAndBounds() {
        assertEquals("0.00", DisplayConfig.normalize("0"));
        assertEquals("0.01", DisplayConfig.normalize("0.01"));
        assertEquals("12.30", DisplayConfig.normalize("12.3"));
        assertEquals("1.20", DisplayConfig.normalize("0001.20"));
        assertEquals("999999999.99", DisplayConfig.normalize("999999999.99"));
    }

    @Test
    public void rejectsAmbiguousOrOutOfRangeInputWithoutRounding() {
        String[] invalid = {
            "",
            " ",
            "-1",
            "+1",
            "1e2",
            "1E2",
            "1,000",
            "1.001",
            "1.000",
            ".5",
            "1.",
            "NaN",
            "Infinity",
            "1000000000",
            "１.２",
            "1 2"
        };
        for (String value : invalid) {
            assertThrows(
                    value, IllegalArgumentException.class, () -> DisplayConfig.normalize(value));
        }
        assertThrows(IllegalArgumentException.class, () -> DisplayConfig.normalize(null));
    }

    @Test
    public void readsIndependentAmountsAndFormatsGrouping() {
        Map<String, Object> values = validValues();
        DisplayConfig config = DisplayConfig.read(preferences(values));
        assertTrue(config.enabled);
        assertFalse(config.diagnostics);
        assertEquals(7L, config.revision);
        assertEquals("1234.50", config.amount(DisplayConfig.Kind.BALANCE));
        assertEquals("1,234.50", config.formatted(DisplayConfig.Kind.BALANCE));
        assertEquals("0.01", config.formatted(DisplayConfig.Kind.YUEBAO));
        assertEquals("999,999,999.99", config.formatted(DisplayConfig.Kind.FUND));
        assertEquals("42.00", config.amount(DisplayConfig.Kind.TOTAL));
    }

    @Test
    public void invalidSchemaTypeRevisionOrOneAmountDisablesEntireSnapshot() {
        assertFalse(DisplayConfig.read(null).enabled);
        assertFalse(DisplayConfig.read(preferences(new HashMap<>())).enabled);
        assertInvalid("schema", 2);
        assertInvalid("schema", "1");
        assertInvalid("enabled", "true");
        assertInvalid("revision", -1L);
        assertInvalid("revision", 7);
        assertInvalid("balance", "1,234.50");
        assertInvalid("fund", "1.001");
        assertInvalid("total", null);
    }

    @Test
    public void disabledValidConfigRetainsEditableAmountsAndFailedReadsDisable() {
        Map<String, Object> values = validValues();
        values.put("enabled", false);
        DisplayConfig config = DisplayConfig.read(preferences(values));
        assertFalse(config.enabled);
        assertEquals("1234.50", config.amount(DisplayConfig.Kind.BALANCE));
        SharedPreferences failed =
                (SharedPreferences)
                        Proxy.newProxyInstance(
                                SharedPreferences.class.getClassLoader(),
                                new Class<?>[] {SharedPreferences.class},
                                (proxy, method, args) -> {
                                    throw new IllegalStateException("unavailable");
                                });
        assertFalse(DisplayConfig.read(failed).enabled);
    }

    @Test
    public void diagnosticsRequiresExplicitBooleanAndDoesNotDependOnDisplaySwitch() {
        Map<String, Object> values = validValues();
        values.put("enabled", false);
        values.put("diagnostics", true);
        DisplayConfig config = DisplayConfig.read(preferences(values));
        assertTrue(config.diagnostics);
        assertFalse(config.enabled);
        for (Object invalid : new Object[] {"true", 1, null, false}) {
            values.put("diagnostics", invalid);
            assertFalse(DisplayConfig.read(preferences(values)).diagnostics);
        }
        values.put("diagnostics", true);
        values.put("schema", 2);
        assertFalse(DisplayConfig.read(preferences(values)).diagnostics);
    }

    @Test
    public void amountSnapshotDoesNotFollowLaterPreferenceMapChanges() {
        Map<String, Object> values = validValues();
        DisplayConfig config = DisplayConfig.read(preferences(values));
        values.put("balance", "999");
        values.put("total", "888");
        assertEquals("1234.50", config.amount(DisplayConfig.Kind.BALANCE));
        assertEquals("42.00", config.amount(DisplayConfig.Kind.TOTAL));
    }

    private void assertInvalid(String key, Object value) {
        Map<String, Object> values = validValues();
        values.put(key, value);
        DisplayConfig config = DisplayConfig.read(preferences(values));
        assertFalse(key, config.enabled);
        assertEquals("0.00", config.amount(DisplayConfig.Kind.BALANCE));
    }

    private Map<String, Object> validValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("schema", 1);
        values.put("enabled", true);
        values.put("revision", 7L);
        values.put("balance", "1234.5");
        values.put("yuebao", "0.01");
        values.put("fund", "999999999.99");
        values.put("total", "42");
        return values;
    }

    private SharedPreferences preferences(Map<String, Object> values) {
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        SharedPreferences.class.getClassLoader(),
                        new Class<?>[] {SharedPreferences.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getAll")) {
                                return new HashMap<>(values);
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
