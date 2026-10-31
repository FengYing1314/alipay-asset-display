package io.github.fengying.alipaydisplay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 诊断输出只允许固定的结构词，不向日志传递宿主自由文本。 */
final class DiagnosticPolicy {
    private static final String NUMERIC_VERSION = "[0-9]{1,20}(?:\\.[0-9]{1,20}){2,5}";
    private static final Pattern VERSION_PREFIX =
            Pattern.compile("([0-9]{1,20}\\.[0-9]{1,20}\\.[0-9]{1,20})(.*)");
    private static final Pattern VERSION_SUFFIX =
            Pattern.compile("(" + NUMERIC_VERSION + ")([-_][A-Za-z0-9][A-Za-z0-9._-]{0,63})");
    static final Set<String> TOKENS =
            immutableWords(
                    "overview-card",
                    "asset-card",
                    "amountView",
                    "total-text-container",
                    "total-text-container-big",
                    "assets-all",
                    "total-asset",
                    "wu-number",
                    "wu-alisans-number",
                    "asset-text");
    static final Set<String> LABELS =
            immutableWords(
                    "总资产", "余额", "余额宝", "基金", "总金额", "总金额(元)", "总金额（元）", "总资产(元)", "总资产（元）", "显示",
                    "隐藏");
    static final Set<String> TAGS =
            immutableWords(
                    "DIV", "SPAN", "BUTTON", "P", "SECTION", "MAIN", "A", "IMG", "TEXT", "VIEW",
                    "LABEL", "OTHER");
    static final Set<String> ROLES =
            immutableWords("button", "text", "group", "heading", "img", "none");

    private DiagnosticPolicy() {}

    static String token(String value) {
        return value != null && TOKENS.contains(value) ? value : "";
    }

    static String label(String value) {
        return value != null && LABELS.contains(value) ? value : "";
    }

    static String tag(String value) {
        return value != null && TAGS.contains(value) ? value : "OTHER";
    }

    static String role(String value) {
        return value != null && ROLES.contains(value) ? value : "";
    }

    static String splitTextRole(String value) {
        return "INTEGER".equals(value)
                        || "COMMA".equals(value)
                        || "POINT".equals(value)
                        || "FRACTION".equals(value)
                ? value
                : "OTHER";
    }

    static String rootToken(String value) {
        if ("semantic-balance".equals(value)
                || "semantic-yuebao".equals(value)
                || "semantic-fund".equals(value)
                || "amount-parent-1".equals(value)
                || "amount-parent-2".equals(value)
                || "amount-parent-3".equals(value)) return value;
        return token(value);
    }

    static String rootSource(String value) {
        String token = rootToken(value);
        if (token.startsWith("semantic-")) return "semantic-probe";
        if (token.startsWith("amount-parent-")) return "ancestor-probe";
        return "dom";
    }

    /** 仅供固定的App.getAppId API返回值使用，禁止用于DOM或其他账户字段。 */
    static String appId(Object value) {
        return value instanceof String text && text.matches("(?:[0-9]{8}|[0-9]{16})")
                ? text
                : "unknown";
    }

    static String appIdState(Object value) {
        if (value == null) return "missing";
        if (!(value instanceof String text)) return "non-string";
        if (text.trim().isEmpty()) return "empty";
        return "unknown".equals(appId(value)) ? "invalid-format" : "valid";
    }

    static boolean sameAppId(Object first, Object second) {
        String id = appId(first);
        return !"unknown".equals(id) && id.equals(appId(second));
    }

    static boolean sameStartToken(Object first, Object second) {
        return first instanceof Long firstToken
                && second instanceof Long secondToken
                && firstToken.longValue() == secondToken.longValue();
    }

    static String page(String appId) {
        if ("20000243".equals(appId) || "68688004".equals(appId)) return "total-assets";
        if ("2021001192699414".equals(appId) || "2060090000375934".equals(appId)) return "yuebao";
        if ("20000793".equals(appId)) return "fund";
        if ("77700300".equals(appId)) return "balance";
        return "unknown";
    }

    static String version(String version) {
        if (version == null || version.length() > 256) return "unknown";
        if (version.matches(NUMERIC_VERSION)) return version;
        Matcher match = VERSION_SUFFIX.matcher(version);
        if (match.matches()) return match.group(1);
        return "unknown";
    }

    static String versionState(Object value) {
        if (value == null) return "missing";
        if (!(value instanceof String text)) return "non-string";
        if (text.trim().isEmpty()) return "empty";
        if (text.length() > 256) return "over-limit";
        if ("*".equals(text)) return "wildcard";
        if ("online".equalsIgnoreCase(text)) return "online";
        Matcher match = VERSION_PREFIX.matcher(text);
        if (match.matches() && match.group(2).matches("\\.[0-9]{13,17}")) return "timestamp-tail";
        if (VERSION_SUFFIX.matcher(text).matches()) return "dotted-with-suffix";
        if (!"unknown".equals(version(text))) return "dotted-version";
        if (text.matches("[0-9]+\\.[0-9]+")) return "two-part-number";
        if (text.matches("[0-9]+")) return "digits-only";
        return "other-format";
    }

    /** 仅对固定版本API的有界返回值求摘要，不输出不合法版本原文。 */
    static String versionFingerprint(Object value) {
        if (!(value instanceof String text) || text.length() > 256) return "unknown";
        try {
            return routeFingerprint(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    static String semantic(String value) {
        if ("total-assets".equals(value)
                || "yuebao".equals(value)
                || "fund".equals(value)
                || "balance".equals(value)
                || "total-amount".equals(value)) return value;
        return "none";
    }

    static String semanticKind(String text) {
        if (text == null) return "none";
        boolean total = text.contains("总资产");
        boolean yuebao = text.contains("余额宝");
        boolean fund = text.contains("基金");
        boolean balance = text.replace("余额宝", "").contains("余额");
        boolean totalAmount = text.contains("总金额");
        if ((total ? 1 : 0)
                        + (yuebao ? 1 : 0)
                        + (fund ? 1 : 0)
                        + (balance ? 1 : 0)
                        + (totalAmount ? 1 : 0)
                != 1) return "none";
        return total
                ? "total-assets"
                : yuebao ? "yuebao" : fund ? "fund" : balance ? "balance" : "total-amount";
    }

    static boolean numericOnly(String value) {
        return value != null
                && value.replaceAll("[\\s\\u200B-\\u200D\\uFEFF]", "")
                        .matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?");
    }

    static String nodePath(String value) {
        if (value == null || !value.matches("[0-9]{1,2}(?:\\.[0-9]{1,3}){0,8}")) return "";
        String[] parts = value.split("\\.");
        if (Integer.parseInt(parts[0]) >= 34) return "";
        for (int i = 1; i < parts.length; i++) if (Integer.parseInt(parts[i]) >= 200) return "";
        return value;
    }

    static String parentPath(String value) {
        if (nodePath(value).isEmpty()) return "";
        int at = value.lastIndexOf('.');
        return at < 0 ? "root" : value.substring(0, at);
    }

    static String ancestorPaths(String value) {
        if (nodePath(value).isEmpty()) return "";
        StringBuilder ancestors = new StringBuilder();
        for (int at = value.indexOf('.'); at >= 0; at = value.indexOf('.', at + 1)) {
            if (ancestors.length() > 0) ancestors.append('/');
            ancestors.append(value, 0, at);
        }
        return ancestors.length() == 0 ? "root" : ancestors.toString();
    }

    /** 仅用摘要比较路由；查询参数、fragment及原始URL不保留也不输出。 */
    static byte[] routeKey(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192) return null;
        try {
            URI uri = new URI(value).normalize();
            if (uri.isOpaque() || uri.getRawPath() == null || uri.getRawUserInfo() != null)
                return null;
            String route =
                    String.valueOf(uri.getScheme())
                            + ":"
                            + String.valueOf(uri.getRawAuthority())
                            + String.valueOf(uri.getRawPath());
            return MessageDigest.getInstance("SHA-256")
                    .digest(route.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    static String routeCategory(String value) {
        if (value == null) return "missing";
        if (value.isEmpty()) return "empty";
        if (routeKey(value) == null) return "invalid";
        try {
            String scheme = new URI(value).getScheme();
            if (scheme == null) return "relative";
            if ("https".equalsIgnoreCase(scheme)) return "https";
            if ("http".equalsIgnoreCase(scheme)) return "http";
            if ("alipay".equalsIgnoreCase(scheme)) return "alipay";
            if ("file".equalsIgnoreCase(scheme)) return "file";
            return "other-hierarchical";
        } catch (Exception ignored) {
            return "invalid";
        }
    }

    static String routeFingerprint(byte[] key) {
        if (key == null || key.length != 32) return "unknown";
        char[] hex = "0123456789abcdef".toCharArray();
        char[] output = new char[64];
        for (int i = 0; i < key.length; i++) {
            int value = key[i] & 0xff;
            output[i * 2] = hex[value >>> 4];
            output[i * 2 + 1] = hex[value & 0xf];
        }
        return new String(output);
    }

    static boolean sameRoute(byte[] first, byte[] second) {
        return first != null && second != null && Arrays.equals(first, second);
    }

    private static Set<String> immutableWords(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    /** 每次请求独立编号；页面离开、文档切换及关闭均使旧回调失效。 */
    static final class Epoch {
        private long page;
        private long request;
        private boolean active;

        void resume() {
            active = true;
            page++;
            request++;
        }

        void invalidate() {
            page++;
            request++;
        }

        void pause() {
            active = false;
            invalidate();
        }

        long page() {
            return page;
        }

        long request() {
            return ++request;
        }

        boolean accepts(long expectedPage, long expectedRequest) {
            return active && page == expectedPage && request == expectedRequest;
        }
    }
}
