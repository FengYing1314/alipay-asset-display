package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class BindingHookPolicyTest {
    private static final String V4 = "com.alipay.android.render.engine.asset.AssetsHeaderV4View";
    private static final String PAGER =
            "com.alipay.android.render.engine.asset.AssetsHeaderPagerView";
    private static final String MODEL =
            "com.alipay.android.render.engine.asset.model.AssetsCardModel";
    private static final String[] PARAMETERS = {MODEL, "boolean", "boolean"};

    @Test
    public void acceptsOnlyTwoVerifiedOwnerAndDelegatePairs() {
        assertTrue(BindingHookPolicy.isHeaderDelegate(V4, "M", "void", PARAMETERS));
        assertTrue(BindingHookPolicy.isHeaderDelegate(PAGER, "O", "void", PARAMETERS));
        assertFalse(BindingHookPolicy.isHeaderDelegate(V4, "O", "void", PARAMETERS));
        assertFalse(BindingHookPolicy.isHeaderDelegate(PAGER, "M", "void", PARAMETERS));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4.replace("V4", "Old"), "M", "void", PARAMETERS));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4.replace("V4", "Bank"), "M", "void", PARAMETERS));
        assertFalse(BindingHookPolicy.isHeaderDelegate(V4 + "$Child", "M", "void", PARAMETERS));
    }

    @Test
    public void rejectsDifferentReturnOrParameterContracts() {
        assertFalse(BindingHookPolicy.isHeaderDelegate(V4, "M", "java.lang.Void", PARAMETERS));
        assertFalse(BindingHookPolicy.isHeaderDelegate(V4, "M", "void", null));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4, "M", "void", new String[] {MODEL, "boolean"}));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4, "M", "void", new String[] {MODEL, "boolean", "boolean", "boolean"}));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4, "M", "void", new String[] {MODEL, "java.lang.Boolean", "boolean"}));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4, "M", "void", new String[] {MODEL, "boolean", "int"}));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        V4,
                        "M",
                        "void",
                        new String[] {
                            "com.alipay.android.render.engine.asset.model.AssetProfilesModel",
                            "boolean",
                            "boolean"
                        }));
        assertFalse(
                BindingHookPolicy.isHeaderDelegate(
                        PAGER,
                        "M",
                        "void",
                        new String[] {
                            "com.alipay.android.render.engine.asset.model.AssetProfilesModel",
                            "boolean",
                            "int",
                            "int"
                        }));
        assertFalse(BindingHookPolicy.isHeaderDelegate(V4, "setData", "void", PARAMETERS));
    }
}
