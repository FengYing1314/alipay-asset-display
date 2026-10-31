package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import io.github.libxposed.service.XposedService;

import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 管理框架连接、草稿与配置保存；页面呈现由 SettingsView 负责。 */
public final class SettingsActivity extends Activity implements ModuleApp.Listener {
    private static final String DRAFT = "display_draft";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ModuleApp app;
    private SettingsView settings;
    private XposedService service;
    private boolean supported;
    private boolean saving;
    private boolean rendering;
    private boolean edited;
    private boolean started;
    private boolean destroyed;
    private long revision;
    private long connectionGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (ModuleApp) getApplication();
        setTitle(R.string.app_name);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        settings = new SettingsView(this);
        settings.bindActions(this::markDraftEdited, this::saveConfiguration);
        setContentView(settings);
        restoreLocalDraft();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        app.addListener(this);
    }

    @Override
    protected void onStop() {
        started = false;
        ++connectionGeneration;
        app.removeListener(this);
        persistLocalDraft();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onServiceChanged(XposedService value) {
        service = value;
        supported = false;
        long generation = ++connectionGeneration;
        updateSaveAvailability();
        if (value == null) {
            settings.setFrameworkStatus(getString(R.string.settings_framework_disconnected), false);
            return;
        }
        settings.setFrameworkStatus(getString(R.string.settings_framework_checking), false);
        io.execute(
                () -> {
                    try {
                        int api = value.getApiVersion();
                        boolean remote =
                                (value.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE)
                                        != 0;
                        if (api != 102 || !remote) {
                            applyConnectionResult(
                                    generation,
                                    false,
                                    getString(R.string.settings_framework_unsupported),
                                    null);
                            return;
                        }
                        DisplayConfig config =
                                DisplayConfig.read(value.getRemotePreferences(DisplayConfig.GROUP));
                        String status =
                                getString(
                                        R.string.settings_framework_connected,
                                        value.getFrameworkName(),
                                        value.getFrameworkVersion(),
                                        api);
                        applyConnectionResult(generation, true, status, config);
                    } catch (RuntimeException error) {
                        applyConnectionResult(
                                generation,
                                false,
                                getString(R.string.settings_framework_failed),
                                null);
                    }
                });
    }

    // 框架结果与配置呈现

    private void applyConnectionResult(
            long generation, boolean available, String status, DisplayConfig config) {
        runOnUiThread(
                () -> {
                    if (destroyed || !started || generation != connectionGeneration) {
                        return;
                    }
                    supported = available;
                    settings.setFrameworkStatus(status, available);
                    if (config != null) {
                        revision = config.revision;
                        if (!saving && (!edited || matchesCurrentInputs(config))) {
                            edited = false;
                            renderConfiguration(config);
                            persistLocalDraft();
                        }
                    }
                    updateSaveAvailability();
                });
    }

    private void renderConfiguration(DisplayConfig config) {
        rendering = true;
        settings.setDisplayEnabled(config.enabled);
        settings.setDiagnosticsEnabled(config.diagnostics);
        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
            settings.setAmountText(kind, config.amount(kind));
        }
        rendering = false;
        settings.setSaveStatus(
                config.enabled
                        ? R.string.settings_config_enabled
                        : R.string.settings_config_disabled);
    }

    private boolean matchesCurrentInputs(DisplayConfig config) {
        if (settings.isDisplayEnabled() != config.enabled
                || settings.isDiagnosticsEnabled() != config.diagnostics) {
            return false;
        }
        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
            try {
                if (!DisplayConfig.normalize(settings.getAmountText(kind))
                        .equals(config.amount(kind))) {
                    return false;
                }
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return true;
    }

    // 配置保存与结果确认

    private void saveConfiguration() {
        XposedService target = service;
        if (saving || !supported || target == null || app.getService() != target) {
            settings.setSaveStatus(R.string.settings_save_unavailable);
            return;
        }
        EnumMap<DisplayConfig.Kind, String> amounts = new EnumMap<>(DisplayConfig.Kind.class);
        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
            try {
                amounts.put(kind, DisplayConfig.normalize(settings.getAmountText(kind)));
                settings.setAmountError(kind, null);
            } catch (IllegalArgumentException error) {
                settings.setAmountError(kind, getString(R.string.settings_invalid_amount));
                settings.setSaveStatus(R.string.settings_save_invalid);
                return;
            }
        }
        boolean desiredEnabled = settings.isDisplayEnabled();
        boolean desiredDiagnostics = settings.isDiagnosticsEnabled();
        long previousRevision = revision;
        saving = true;
        settings.setInputsEnabled(false);
        updateSaveAvailability();
        settings.setSaveStatus(R.string.settings_saving);
        io.execute(
                () -> {
                    boolean success = false;
                    long nextRevision = previousRevision;
                    try {
                        SharedPreferences preferences =
                                target.getRemotePreferences(DisplayConfig.GROUP);
                        long remoteRevision = DisplayConfig.read(preferences).revision;
                        nextRevision =
                                Math.max(
                                        System.currentTimeMillis(),
                                        Math.addExact(
                                                Math.max(remoteRevision, previousRevision), 1L));
                        SharedPreferences.Editor editor =
                                preferences
                                        .edit()
                                        .putInt("schema", DisplayConfig.SCHEMA)
                                        .putBoolean("enabled", desiredEnabled)
                                        .putBoolean("diagnostics", desiredDiagnostics)
                                        .putLong("revision", nextRevision);
                        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
                            editor.putString(kind.key(), amounts.get(kind));
                        }
                        success =
                                app.getService() == target
                                        && editor.commit()
                                        && app.getService() == target;
                    } catch (RuntimeException ignored) {
                        // 不输出输入金额或框架内部异常中的数据。
                    }
                    completeConfigurationSave(success, nextRevision, desiredEnabled, amounts);
                });
    }

    private void completeConfigurationSave(
            boolean success,
            long savedRevision,
            boolean desiredEnabled,
            EnumMap<DisplayConfig.Kind, String> amounts) {
        runOnUiThread(
                () -> {
                    if (destroyed) {
                        return;
                    }
                    saving = false;
                    settings.setInputsEnabled(true);
                    if (success) {
                        revision = savedRevision;
                        edited = false;
                        rendering = true;
                        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
                            settings.setAmountText(kind, amounts.get(kind));
                        }
                        rendering = false;
                        persistLocalDraft();
                        settings.setSaveStatus(
                                desiredEnabled
                                        ? R.string.settings_saved_enabled
                                        : R.string.settings_saved_disabled);
                    } else {
                        settings.setSaveStatus(R.string.settings_save_unconfirmed);
                        persistLocalDraft();
                    }
                    updateSaveAvailability();
                });
    }

    private void updateSaveAvailability() {
        settings.renderSaveAction(supported && service != null && !saving, saving);
    }

    // 本机草稿

    private void markDraftEdited() {
        if (!rendering && settings != null) {
            edited = true;
            settings.setSaveStatus(R.string.settings_save_edited);
        }
    }

    private void restoreLocalDraft() {
        SharedPreferences draft = getSharedPreferences(DRAFT, MODE_PRIVATE);
        rendering = true;
        settings.setDisplayEnabled(draft.getBoolean("enabled", false));
        settings.setDiagnosticsEnabled(draft.getBoolean("diagnostics", false));
        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
            settings.setAmountText(kind, draft.getString(kind.key(), "0.00"));
        }
        rendering = false;
        edited = draft.getBoolean("pending", false);
        if (edited) {
            settings.setSaveStatus(R.string.settings_draft_restored);
        }
    }

    private void persistLocalDraft() {
        SharedPreferences.Editor editor =
                getSharedPreferences(DRAFT, MODE_PRIVATE)
                        .edit()
                        .putBoolean("enabled", settings.isDisplayEnabled())
                        .putBoolean("diagnostics", settings.isDiagnosticsEnabled())
                        .putBoolean("pending", edited);
        for (DisplayConfig.Kind kind : DisplayConfig.Kind.values()) {
            editor.putString(kind.key(), settings.getAmountText(kind));
        }
        editor.apply();
    }
}
