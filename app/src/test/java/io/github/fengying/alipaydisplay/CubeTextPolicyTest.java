package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class CubeTextPolicyTest {
    @Test
    public void labelsAreFixedAndCannotLeakDescriptionsOrOtherProducts() {
        assertEquals("balance", CubeTextPolicy.label("余额(元)"));
        assertEquals("balance", CubeTextPolicy.label("余额：1,234.56元"));
        assertEquals("available-balance", CubeTextPolicy.label("可用余额 ￥1,234.56"));
        assertEquals("account-balance", CubeTextPolicy.label("账户余额"));
        assertEquals("total-amount", CubeTextPolicy.label("总金额****元"));
        assertEquals("balance", CubeTextPolicy.label("text；余额"));
        for (String privateText :
                new String[] {
                    "余额宝",
                    "余额 客户名称",
                    "基金1234.56",
                    "1234.56",
                    "余额1.00 收益2.00",
                    "https://example.invalid/private?account=one",
                    "任意账户信息",
                    "arbitrary；余额"
                }) {
            assertEquals("none", CubeTextPolicy.label(privateText));
            assertEquals("other", CubeTextPolicy.commandKind(privateText));
        }
    }

    @Test
    public void amountChecksOnlyReturnBooleansAndUseStrictFormatting() {
        assertTrue(CubeTextPolicy.hasLabeledAmount("余额：1,234.56元"));
        assertFalse(CubeTextPolicy.hasLabeledAmount("余额"));
        assertFalse(CubeTextPolicy.hasLabeledAmount("余额 收益1.00"));
        assertTrue(CubeTextPolicy.isAmount("￥1,234.56元"));
        assertTrue(CubeTextPolicy.sameAmount("1,234.5", "1234.50"));
        assertTrue(CubeTextPolicy.sameAmount("0", "0.00"));
        for (String value :
                new String[] {
                    "1,23.45", "01.00", "+1.00", "1e2", "收益1.00", "1234.567", "12.00%", "****"
                }) {
            assertFalse(CubeTextPolicy.isAmount(value));
            assertFalse(CubeTextPolicy.sameAmount(value, "1234.56"));
        }
        assertFalse(CubeTextPolicy.sameAmount("1234.56", "1234.57"));
    }

    @Test
    public void masksAndLabelsAreBoundedAndNeverReturnHostText() {
        assertTrue(CubeTextPolicy.isMaskedAmount("*"));
        assertTrue(CubeTextPolicy.isMaskedAmount("余额****元"));
        assertTrue(CubeTextPolicy.isMaskedAmount("余额\n****元"));
        assertFalse(CubeTextPolicy.isMaskedAmount("任意名字*"));
        assertEquals("none", CubeTextPolicy.label("余额" + "0".repeat(257)));
    }

    @Test
    public void controlledClassificationReturnsOnlyFixedLabelsAndFlags() {
        for (String[] pair :
                new String[][] {
                    {"余额", "balance"},
                    {"总金额(元)", "total-amount"},
                    {"账户余额", "account-balance"},
                    {"不可用余额", "unavailable-balance"},
                    {"冻结", "frozen"},
                    {"可用", "available"},
                    {"不可用", "unavailable"}
                }) {
            CubeTextPolicy.Classification result = CubeTextPolicy.classifyText(pair[0], "1234.56");
            assertEquals(pair[1], result.label);
            assertFalse(result.pureAmount);
            assertFalse(result.hasNumber);
        }
        CubeTextPolicy.Classification amount = CubeTextPolicy.classifyText("1,234.56", "1234.56");
        assertEquals("none", amount.label);
        assertTrue(amount.pureAmount);
        assertTrue(amount.hasNumber);
        assertTrue(amount.matchesConfig);
        CubeTextPolicy.Classification masked = CubeTextPolicy.classifyText("****", "1234.56");
        assertTrue(masked.mask);
        assertFalse(masked.hasNumber);
        assertFalse(masked.matchesConfig);
        CubeTextPolicy.Classification unknown = CubeTextPolicy.classifyText("客户任意文本123", "1234.56");
        assertEquals("none", unknown.label);
        assertTrue(unknown.hasNumber);
        assertFalse(unknown.pureAmount);
        Object hostile =
                new Object() {
                    @Override
                    public String toString() {
                        throw new AssertionError("must not call");
                    }
                };
        assertEquals("none", CubeTextPolicy.classifyText(hostile, "1234.56").label);
        assertFalse(CubeTextPolicy.classifyText("1".repeat(257), "1234.56").hasNumber);
    }
}
