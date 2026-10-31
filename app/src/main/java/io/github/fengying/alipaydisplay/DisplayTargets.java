package io.github.fengying.alipaydisplay;

/** 已在目标版本核实的展示节点；名称相近的收益和负债字段不匹配。 */
public final class DisplayTargets {
    public static final String WEALTH = "com.alipay.android.widget.fortunehome:id/";
    public static final String WEALTH_ROOT = WEALTH + "activity_fh_root";
    static final String TOTAL_AMOUNT = WEALTH + "fh_tv_assets_amount_num";
    static final String CARD_AMOUNT = WEALTH + "asset_horizon_content";
    static final String TOTAL_TITLE = WEALTH + "fh_tv_assets_amount_title";
    static final String CARD_TITLE = WEALTH + "asset_horizon_title";

    private DisplayTargets() {}

    public static DisplayConfig.Kind wealthKind(String resource, String title) {
        if (TOTAL_AMOUNT.equals(resource) && "总资产(元)".equals(title)) {
            return DisplayConfig.Kind.TOTAL;
        }
        if (CARD_AMOUNT.equals(resource)) {
            if ("余额宝".equals(title)) return DisplayConfig.Kind.YUEBAO;
            if ("基金".equals(title)) return DisplayConfig.Kind.FUND;
        }
        return null;
    }

    public static boolean isVisibleAmount(String text) {
        return text != null
                && text.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?");
    }

    /** 纯数字模块产物也不能成为宿主原文，归属标记优先于字符串格式。 */
    static boolean isHostAmount(String text, boolean moduleOwned) {
        return isHostAmount(text, moduleOwned, false);
    }

    static boolean isHostAmount(String text, boolean moduleOwned, boolean pendingModuleEcho) {
        return !moduleOwned && !pendingModuleEcho && isVisibleAmount(text);
    }

    static boolean isAmountResource(String name) {
        return TOTAL_AMOUNT.equals(name) || CARD_AMOUNT.equals(name);
    }

    static boolean isTitleResource(String name) {
        return TOTAL_TITLE.equals(name) || CARD_TITLE.equals(name);
    }

    /** 只用于金额节点自身的描述，不能用于推断眼睛按钮的动作方向。 */
    static boolean isHiddenDescription(CharSequence value) {
        if (value == null) return false;
        String text = value.toString();
        return text.contains("隐藏")
                || text.contains("掩码")
                || text.contains("*")
                || text.contains("＊")
                || text.contains("•")
                || text.contains("●");
    }
}
