package io.github.fengying.alipaydisplay;

/** 仅允许已核实会绕过 setData 的两个原生金额隐藏委托。 */
final class BindingHookPolicy {
    private BindingHookPolicy() {}

    static boolean isHeaderDelegate(
            String owner, String name, String returnType, String[] parameters) {
        boolean verifiedName =
                ("com.alipay.android.render.engine.asset.AssetsHeaderV4View".equals(owner)
                                && "M".equals(name))
                        || ("com.alipay.android.render.engine.asset.AssetsHeaderPagerView"
                                        .equals(owner)
                                && "O".equals(name));
        return verifiedName
                && "void".equals(returnType)
                && parameters != null
                && parameters.length == 3
                && "com.alipay.android.render.engine.asset.model.AssetsCardModel"
                        .equals(parameters[0])
                && "boolean".equals(parameters[1])
                && "boolean".equals(parameters[2]);
    }
}
