package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class RuntimePolicyTest {
    @Test
    public void onlyVerifiedMainProcessAndApiEnterRuntime() {
        String target = RuntimePolicy.PACKAGE;
        assertTrue(RuntimePolicy.acceptsProcess(target, target, true, 102, true));
        assertFalse(RuntimePolicy.acceptsProcess(target + ":push", target, true, 102, true));
        assertFalse(RuntimePolicy.acceptsProcess(target, "another.package", true, 102, true));
        assertFalse(RuntimePolicy.acceptsProcess(target, target, false, 102, true));
        assertFalse(RuntimePolicy.acceptsProcess(target, target, true, 101, true));
        assertFalse(RuntimePolicy.acceptsProcess(target, target, true, 103, true));
        assertFalse(RuntimePolicy.acceptsProcess(target, target, true, 102, false));
    }

    @Test
    public void unknownVersionAndActivityRemainUntouched() {
        assertTrue(RuntimePolicy.acceptsVersion(RuntimePolicy.PACKAGE, 212370));
        assertFalse(RuntimePolicy.acceptsVersion(RuntimePolicy.PACKAGE, 212371));
        assertFalse(RuntimePolicy.acceptsVersion("another.package", 212370));
        assertTrue(RuntimePolicy.isWealthActivity(RuntimePolicy.PACKAGE + ".AlipayLogin"));
        assertFalse(
                RuntimePolicy.isWealthActivity(
                        "com.alipay.mobile.nebulaappproxy.activity.XRiverActivity"));
        assertFalse(
                RuntimePolicy.isBindingClass(
                        "com.alipay.android.render.engine.asset.model.AssetsCardModel"));
    }
}
