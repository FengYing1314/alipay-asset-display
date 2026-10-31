package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class DisplayTargetsTest {
    @Test
    public void selectsOnlyVerifiedAssetTitles() {
        String amount = DisplayTargets.WEALTH + "asset_horizon_content";
        assertEquals(DisplayConfig.Kind.YUEBAO, DisplayTargets.wealthKind(amount, "余额宝"));
        assertEquals(DisplayConfig.Kind.FUND, DisplayTargets.wealthKind(amount, "基金"));
        assertNull(DisplayTargets.wealthKind(amount, "花呗"));
        assertNull(DisplayTargets.wealthKind(amount, "保险"));
        assertNull(DisplayTargets.wealthKind(amount, "昨日收益"));
        assertNull(
                DisplayTargets.wealthKind(DisplayTargets.WEALTH + "asset_horizon_profile", "基金"));
        assertNull(
                DisplayTargets.wealthKind(
                        DisplayTargets.WEALTH + "fh_tv_assets_yesterday_num", "总资产(元)"));
    }

    @Test
    public void hiddenAndLoadingAmountsRemainUntouched() {
        assertTrue(DisplayTargets.isVisibleAmount("0.00"));
        assertTrue(DisplayTargets.isVisibleAmount("1,234.56"));
        assertFalse(DisplayTargets.isVisibleAmount("****"));
        assertFalse(DisplayTargets.isVisibleAmount("--"));
        assertFalse(DisplayTargets.isVisibleAmount("加载中"));
        assertFalse(DisplayTargets.isVisibleAmount("3.50%"));
        assertFalse(DisplayTargets.isVisibleAmount("1,23.00"));
        assertFalse(DisplayTargets.isVisibleAmount("演示 100.00"));
    }

    @Test
    public void onlyAmountNodeDescriptionSuppliesMaskSignal() {
        assertTrue(DisplayTargets.isHiddenDescription("总资产已隐藏"));
        assertTrue(DisplayTargets.isHiddenDescription("****"));
        assertTrue(DisplayTargets.isHiddenDescription("●●●●"));
        assertFalse(DisplayTargets.isHiddenDescription(null));
        assertFalse(DisplayTargets.isHiddenDescription("总资产100.00元"));
    }

    @Test
    public void moduleOwnedNumericOutputIsNeverAHostSource() {
        assertFalse(DisplayTargets.isHostAmount("9,999.99", true));
        assertFalse(DisplayTargets.isHostAmount("0.00", true));
        assertTrue(DisplayTargets.isHostAmount("9,999.99", false));
        assertFalse(DisplayTargets.isHostAmount("9,999.99", false, true));
        assertTrue(DisplayTargets.isHostAmount("9,999.99", false, false));
        assertFalse(DisplayTargets.isHostAmount("****", false));
        assertFalse(DisplayTargets.isHostAmount("--", false));
    }
}
