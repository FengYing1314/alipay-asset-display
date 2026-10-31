package io.github.fengying.alipaydisplay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Objects;

/** 设置页的布局与交互呈现，不持有框架服务或配置存储。 */
final class SettingsView extends FrameLayout {
    private final EnumMap<DisplayConfig.Kind, EditText> amounts =
            new EnumMap<>(DisplayConfig.Kind.class);
    private final LinearLayout content;
    private final Switch enabled;
    private final Switch diagnostics;
    private final TextView frameworkStatus;
    private final TextView saveStatus;
    private final Button saveButton;
    private final Button advancedToggle;
    private final View advancedContent;
    private Runnable onEdited = () -> {};
    private Runnable onSave = () -> {};
    private boolean advancedExpanded;

    public SettingsView(Context context) {
        super(context);
        inflate(context, R.layout.view_settings, this);
        setBackgroundColor(getContext().getColor(R.color.settings_background));
        content = findViewById(R.id.settings_content);
        enabled = findViewById(R.id.settings_enabled);
        diagnostics = findViewById(R.id.settings_diagnostics);
        frameworkStatus = findViewById(R.id.settings_framework_status);
        saveStatus = findViewById(R.id.settings_save_status);
        saveButton = findViewById(R.id.settings_save);
        advancedToggle = findViewById(R.id.settings_advanced_toggle);
        advancedContent = findViewById(R.id.settings_advanced_content);

        bindAmountInput(
                DisplayConfig.Kind.BALANCE,
                R.id.settings_balance_row,
                R.id.settings_amount_balance,
                R.string.settings_balance);
        bindAmountInput(
                DisplayConfig.Kind.YUEBAO,
                R.id.settings_yuebao_row,
                R.id.settings_amount_yuebao,
                R.string.settings_yuebao);
        bindAmountInput(
                DisplayConfig.Kind.FUND,
                R.id.settings_fund_row,
                R.id.settings_amount_fund,
                R.string.settings_fund);
        bindAmountInput(
                DisplayConfig.Kind.TOTAL,
                R.id.settings_total_row,
                R.id.settings_amount_total,
                R.string.settings_total);
        amounts.get(DisplayConfig.Kind.TOTAL).setImeOptions(EditorInfo.IME_ACTION_DONE);

        enabled.setOnCheckedChangeListener((button, checked) -> onEdited.run());
        diagnostics.setOnCheckedChangeListener(
                (button, checked) -> {
                    updateAdvancedToggleLabel();
                    onEdited.run();
                });
        advancedToggle.setOnClickListener(view -> setAdvancedExpanded(!advancedExpanded));
        saveButton.setOnClickListener(view -> onSave.run());
        saveButton.setBackgroundTintList(null);
        saveButton.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {
                            getContext().getColor(R.color.settings_on_disabled),
                            getContext().getColor(R.color.settings_on_primary)
                        }));
        applyWindowInsets();
    }

    void bindActions(Runnable onEdited, Runnable onSave) {
        this.onEdited = Objects.requireNonNull(onEdited);
        this.onSave = Objects.requireNonNull(onSave);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available =
                Math.max(
                        0,
                        MeasureSpec.getSize(widthMeasureSpec)
                                - getPaddingLeft()
                                - getPaddingRight()
                                - toPixels(40));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
        params.width = Math.min(toPixels(600), available);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    // 呈现接口

    boolean isDisplayEnabled() {
        return enabled.isChecked();
    }

    void setDisplayEnabled(boolean value) {
        enabled.setChecked(value);
    }

    boolean isDiagnosticsEnabled() {
        return diagnostics.isChecked();
    }

    void setDiagnosticsEnabled(boolean value) {
        diagnostics.setChecked(value);
        // 已开启的诊断保持可见；折叠只改变呈现，不改变开关或草稿。
        if (value) setAdvancedExpanded(true);
        updateAdvancedToggleLabel();
    }

    String getAmountText(DisplayConfig.Kind kind) {
        return amounts.get(kind).getText().toString();
    }

    void setAmountText(DisplayConfig.Kind kind, String value) {
        amounts.get(kind).setText(value);
    }

    void setAmountError(DisplayConfig.Kind kind, String message) {
        EditText input = amounts.get(kind);
        input.setError(message);
        if (message != null) input.requestFocus();
    }

    void setFrameworkStatus(CharSequence message, boolean connected) {
        frameworkStatus.setText(message);
        frameworkStatus.setTextColor(
                getContext()
                        .getColor(
                                connected
                                        ? R.color.settings_primary
                                        : R.color.settings_text_secondary));
    }

    void setSaveStatus(int messageId) {
        saveStatus.setText(messageId);
    }

    void setInputsEnabled(boolean value) {
        enabled.setEnabled(value);
        diagnostics.setEnabled(value);
        for (EditText input : amounts.values()) input.setEnabled(value);
    }

    void renderSaveAction(boolean available, boolean saving) {
        saveButton.setEnabled(available);
        saveButton.setText(saving ? R.string.settings_saving : R.string.settings_save);
    }

    // 控件绑定与布局辅助

    private void bindAmountInput(DisplayConfig.Kind kind, int rowId, int inputId, int labelId) {
        View row = findViewById(rowId);
        TextView label = row.findViewById(R.id.settings_amount_label);
        EditText input = row.findViewById(R.id.settings_amount_input);
        input.setId(inputId);
        input.setBackgroundTintList(null);
        label.setText(labelId);
        label.setLabelFor(inputId);
        input.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence value, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(
                            CharSequence value, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable value) {
                        onEdited.run();
                    }
                });
        amounts.put(kind, input);
    }

    private void setAdvancedExpanded(boolean expanded) {
        advancedExpanded = expanded;
        advancedContent.setVisibility(expanded ? VISIBLE : GONE);
        updateAdvancedToggleLabel();
    }

    private void updateAdvancedToggleLabel() {
        advancedToggle.setText(
                advancedExpanded
                        ? R.string.settings_advanced_collapse
                        : diagnostics.isChecked()
                                ? R.string.settings_advanced_active
                                : R.string.settings_advanced_expand);
    }

    private void applyWindowInsets() {
        if (Build.VERSION.SDK_INT < 30) return;
        // edge-to-edge 下统一处理系统栏和键盘，避免底部保存区被遮住或重复留白。
        setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    Insets safe =
                            insets.getInsets(
                                    WindowInsets.Type.systemBars()
                                            | WindowInsets.Type.displayCutout()
                                            | WindowInsets.Type.ime());
                    setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return WindowInsets.CONSUMED;
                });
        requestApplyInsets();
    }

    private int toPixels(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
