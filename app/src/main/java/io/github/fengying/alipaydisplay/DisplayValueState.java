package io.github.fengying.alipaydisplay;

/** 金额和朗读原文各自记录新鲜度；真实隐藏后不复用旧数字。 */
final class DisplayValueState<T> {
    private T text;
    private T description;
    private boolean descriptionFresh;
    private boolean descriptionHidden;
    private boolean privacyHidden;

    DisplayValueState(T text, T description, boolean visibleAmount, boolean hiddenDescription) {
        this.descriptionHidden = hiddenDescription;
        this.text = visibleAmount && !hiddenDescription ? text : null;
        this.description = description;
        this.descriptionFresh = true;
    }

    void hostText(T value, boolean visibleAmount) {
        // 宿主新金额不能搭配上一次金额的朗读描述。
        description = null;
        descriptionFresh = false;
        text = visibleAmount && !descriptionHidden && !privacyHidden ? value : null;
    }

    void hostDescription(T value, boolean hidden) {
        descriptionHidden = hidden;
        description = privacyHidden ? null : value;
        descriptionFresh = !privacyHidden;
        if (hidden) text = null;
    }

    /** 只接收已核实的宿主隐私标志；恢复显示不重新启用隐藏前缓存。 */
    void hostPrivacy(boolean hidden) {
        privacyHidden = hidden;
        if (hidden) invalidate();
    }

    void invalidate() {
        text = null;
        description = null;
        descriptionFresh = false;
    }

    boolean canRender() {
        return text != null && !descriptionHidden && !privacyHidden;
    }

    boolean isPrivacyHidden() {
        return privacyHidden;
    }

    boolean isDescriptionHidden() {
        return descriptionHidden;
    }

    T hostText() {
        return text;
    }

    T hostDescription() {
        return descriptionFresh ? description : null;
    }
}
