package io.github.fengying.alipaydisplay;

import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 只保存用户输入的显示金额，不读取账户资产。 */
public final class DisplayConfig {
    public static final String GROUP = "display";
    public static final int SCHEMA = 1;
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,2})?");

    public final boolean enabled;
    public final boolean diagnostics;
    public final long revision;
    private final String[] amounts;

    private DisplayConfig(boolean enabled, boolean diagnostics, long revision, String[] amounts) {
        this.enabled = enabled;
        this.diagnostics = diagnostics;
        this.revision = revision;
        this.amounts = amounts.clone();
    }

    public static DisplayConfig disabled() {
        return new DisplayConfig(false, false, 0L, new String[] {"0.00", "0.00", "0.00", "0.00"});
    }

    public String amount(Kind kind) {
        return amounts[kind.ordinal()];
    }

    public String formatted(Kind kind) {
        DecimalFormat formatter =
                new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        formatter.setRoundingMode(RoundingMode.UNNECESSARY);
        return formatter.format(new BigDecimal(amount(kind)));
    }

    /** 接受普通十进制输入，禁止隐式舍入、正负号、逗号和科学计数法。 */
    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("请输入金额");
        }
        String text = input.trim();
        if (!AMOUNT.matcher(text).matches()) {
            throw new IllegalArgumentException("金额须为 0～999999999.99，最多两位小数，不含逗号");
        }
        return new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** 一次读取完整快照；任何配置缺失、类型不符或非法金额都会禁用显示。 */
    public static DisplayConfig read(SharedPreferences preferences) {
        if (preferences == null) {
            return disabled();
        }
        try {
            Map<String, ?> values = preferences.getAll();
            if (values == null
                    || !(values.get("schema") instanceof Integer)
                    || ((Integer) values.get("schema")) != SCHEMA
                    || !(values.get("enabled") instanceof Boolean)
                    || !(values.get("revision") instanceof Long)
                    || ((Long) values.get("revision")) < 0L) {
                return disabled();
            }
            String[] amounts = new String[Kind.values().length];
            for (Kind kind : Kind.values()) {
                Object value = values.get(kind.key());
                if (!(value instanceof String)) {
                    return disabled();
                }
                amounts[kind.ordinal()] = normalize((String) value);
            }
            // schema 1 的旧配置没有此项；未知类型不允许开启诊断。
            boolean diagnostics = Boolean.TRUE.equals(values.get("diagnostics"));
            return new DisplayConfig(
                    (Boolean) values.get("enabled"),
                    diagnostics,
                    (Long) values.get("revision"),
                    amounts);
        } catch (RuntimeException ignored) {
            return disabled();
        }
    }

    public enum Kind {
        BALANCE("balance"),
        YUEBAO("yuebao"),
        FUND("fund"),
        TOTAL("total");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
