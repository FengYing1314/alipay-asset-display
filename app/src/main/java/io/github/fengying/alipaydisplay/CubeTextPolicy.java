package io.github.fengying.alipaydisplay;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 绘制与诊断共用的有界文本分类；只返回固定标签或布尔值，不依赖 Android 或日志。 */
final class CubeTextPolicy {
    private static final String NUMBER =
            "(?:0|[1-9][0-9]{0,19}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,6})(?:\\.[0-9]{1,2})?";

    private static final Pattern AMOUNT = Pattern.compile("^[¥￥]?\\s*(" + NUMBER + ")\\s*元?$");

    private static final Pattern LABEL =
            Pattern.compile(
                    "^(不可用余额|可用余额|账户余额|余额|总金额|不可用|冻结|可用)(?:[（(]元[）)])?(?:[\\s:：，,]*[¥￥]?\\s*(?:"
                            + NUMBER
                            + "|[*＊●•·]{1,16})\\s*元?)?$");

    private CubeTextPolicy() {}

    static Classification classifyText(Object value, String configured) {
        if (!(value instanceof String text) || text.length() > 256)
            return new Classification("none", false, false, false, false);
        return new Classification(
                label(text),
                isAmount(text),
                isMaskedAmount(text),
                Pattern.compile("[0-9]").matcher(text).find(),
                sameAmount(text, configured));
    }

    static String label(Object value) {
        if (!(value instanceof String text) || text.length() > 256) return "none";
        String fixed =
                text.trim().replaceFirst("^(?:text|button|group|header|heading|none)[；;]", "");
        Matcher match = LABEL.matcher(fixed);
        if (!match.matches()) return "none";
        return switch (match.group(1)) {
            case "可用余额" -> "available-balance";
            case "不可用余额" -> "unavailable-balance";
            case "账户余额" -> "account-balance";
            case "余额" -> "balance";
            case "总金额" -> "total-amount";
            case "不可用" -> "unavailable";
            case "冻结" -> "frozen";
            case "可用" -> "available";
            default -> "none";
        };
    }

    static boolean isAmount(Object value) {
        return canonicalAmount(value) != null;
    }

    static boolean hasLabeledAmount(Object value) {
        return !"none".equals(label(value))
                && value instanceof String text
                && Pattern.compile(NUMBER).matcher(text).find();
    }

    static boolean isMaskedAmount(Object value) {
        return value instanceof String text
                && text.length() <= 256
                && (text.trim().matches("[*＊●•·]{1,16}")
                        || !"none".equals(label(text)) && text.matches("(?s).*[*＊●•·].*"));
    }

    static boolean sameAmount(Object value, String configured) {
        String left = canonicalAmount(value), right = canonicalAmount(configured);
        return left != null && left.equals(right);
    }

    static String commandKind(String name) {
        if (name == null) return "other";
        return switch (name) {
            case "com.antfin.cube.cubecore.draw.CRTextViewDrawCmd" -> "text-layout";
            case "com.antfin.cube.cubecore.draw.CRTextViewDrawCmdInline" -> "text-inline";
            case "com.antfin.cube.cubecore.draw.CRRichTextViewDrawCmdInline" -> "rich-text-inline";
            case "com.antfin.cube.cubecore.draw.CRViewDrawCmd" -> "view";
            default -> "other";
        };
    }

    private static String canonicalAmount(Object value) {
        if (!(value instanceof String text) || text.length() > 256) return null;
        Matcher match = AMOUNT.matcher(text.trim());
        if (!match.matches()) return null;
        String[] pieces = match.group(1).replace(",", "").split("\\.", -1);
        return pieces[0] + "." + (pieces.length > 1 ? (pieces[1] + "0").substring(0, 2) : "00");
    }

    static final class Classification {
        final String label;
        final boolean pureAmount, mask, hasNumber, matchesConfig;

        Classification(
                String label,
                boolean pureAmount,
                boolean mask,
                boolean hasNumber,
                boolean matchesConfig) {
            this.label = label;
            this.pureAmount = pureAmount;
            this.mask = mask;
            this.hasNumber = hasNumber;
            this.matchesConfig = matchesConfig;
        }
    }
}
