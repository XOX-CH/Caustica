package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CausticaWaterOptionsScreen extends CausticaOptionsScreen {
    private static final int PRESET_COUNT = 14;
    private final Button[] presetButtons = new Button[PRESET_COUNT];
    private Button saveButton;
    private Button deleteButton;
    private boolean saveMode;
    private boolean deleteMode;
    /** The ResetableOption instances actually bound to the UI widgets. */
    private ResetableOption[] waterOptionRows;

    public CausticaWaterOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.water.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.water.header"));

        // Save + Delete buttons side by side
        saveButton = Button.builder(
                Component.translatable("caustica.options.water.save"),
                btn -> {
                    saveMode = !saveMode;
                    if (saveMode) deleteMode = false;
                    updateSaveButton();
                    updateDeleteButton();
                    refreshPresetButtons();
                })
                .build();
        saveButton.active = true;
        saveButton.setAlpha(1.0f);

        deleteButton = Button.builder(
                Component.translatable("caustica.options.water.delete"),
                btn -> {
                    deleteMode = !deleteMode;
                    if (deleteMode) saveMode = false;
                    updateDeleteButton();
                    updateSaveButton();
                    refreshPresetButtons();
                })
                .build();
        deleteButton.active = true;
        deleteButton.setAlpha(1.0f);
        this.list.addSmall(saveButton, deleteButton);

        // Preset buttons: 7 rows of 2
        for (int i = 0; i < PRESET_COUNT; i++) {
            int idx = i;
            boolean saved = WaterPresetManager.hasPreset(idx);
            presetButtons[i] = Button.builder(
                    saved ? Component.literal("✓ " + (idx + 1))
                            : Component.literal("\u3000" + (idx + 1)),
                    btn -> handlePresetClick(idx))
                    .build();
            presetButtons[i].active = true;
            presetButtons[i].setAlpha(saved ? 1.0f : 0.5f);
        }
        for (int i = 0; i < PRESET_COUNT; i += 2) {
            this.list.addSmall(presetButtons[i], presetButtons[i + 1]);
        }

        // Wave detail submenu button
        this.list.addBig(Button.builder(
                Component.translatable("caustica.options.waveBand.open"),
                button -> this.minecraft.gui.setScreen(new CausticaWaveDetailScreen(this, this.options)))
                .build());

        // Water detail options — store the instances so pushWaterOptionsToWidgets
        // can update the same OptionInstance objects the UI widgets are bound to.
        waterOptionRows = RtWaterOptions.waterOptions();
        for (ResetableOption row : waterOptionRows) {
            addOptionRow(row);
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePresetClick(int index) {
        if (deleteMode) {
            // Delete mode: clear this preset
            WaterPresetManager.clearPreset(index);
            deleteMode = false;
            updateDeleteButton();
            refreshPresetButtons();
        } else if (saveMode) {
            // Save mode: save current values to this preset
            WaterPresetManager.savePreset(index);
            saveMode = false;
            updateSaveButton();
            refreshPresetButtons();
        } else {
            // Normal mode: load preset if saved
            if (WaterPresetManager.hasPreset(index)) {
                WaterPresetManager.loadPreset(index);
                // Push config values back into the visible option widgets so sliders update
                pushWaterOptionsToWidgets();
                refreshPresetButtons();
            }
        }
    }

    /** Reads the current config values and pushes them into the visible OptionInstance widgets. */
    @SuppressWarnings("unchecked")
    private void pushWaterOptionsToWidgets() {
        if (waterOptionRows == null) return;
        // Uses the stored waterOptionRows instances that are actually bound to UI widgets.
        pushFloat(waterOptionRows[0], CausticaConfig.Rt.Water.CAUSTIC_BRIGHTNESS, 10.0f);
        pushFloat(waterOptionRows[1], CausticaConfig.Rt.Water.WATER_DENSITY, 10.0f);
        pushFloat(waterOptionRows[2], CausticaConfig.Rt.Water.WATER_SHADOW_TINT, 10.0f);
        pushFloat(waterOptionRows[3], CausticaConfig.Rt.Water.WATER_OPACITY, 10.0f);
        pushFloat(waterOptionRows[4], CausticaConfig.Rt.Water.WATER_COLOR_R, 100.0f);
        pushFloat(waterOptionRows[5], CausticaConfig.Rt.Water.WATER_COLOR_G, 100.0f);
        pushFloat(waterOptionRows[6], CausticaConfig.Rt.Water.WATER_COLOR_B, 100.0f);
        pushFloat(waterOptionRows[7], CausticaConfig.Rt.Water.WATER_COLOR_BLEND, 100.0f);
        // Force every widget to re-read its OptionInstance value and re-render.
        for (ResetableOption row : waterOptionRows) {
            this.list.resetOption(row.option());
        }
    }

    /** Sets the OptionInstance value from the config FloatSetting, using the given multiplier. */
    @SuppressWarnings("unchecked")
    private void pushFloat(ResetableOption row, CausticaConfig.FloatSetting setting, float multiplier) {
        int optionValue = Math.round(setting.value() * multiplier);
        OptionInstance<Integer> opt = (OptionInstance<Integer>) row.option();
        opt.set(optionValue);
    }

    private void updateSaveButton() {
        if (saveMode) {
            saveButton.setMessage(Component.translatable("caustica.options.water.save.active"));
            saveButton.setAlpha(1.0f);
        } else {
            saveButton.setMessage(Component.translatable("caustica.options.water.save"));
            saveButton.setAlpha(1.0f);
        }
    }

    private void updateDeleteButton() {
        if (deleteMode) {
            deleteButton.setMessage(Component.translatable("caustica.options.water.delete.active"));
            deleteButton.setAlpha(1.0f);
        } else {
            deleteButton.setMessage(Component.translatable("caustica.options.water.delete"));
            deleteButton.setAlpha(1.0f);
        }
    }

    private void refreshPresetButtons() {
        for (int i = 0; i < PRESET_COUNT; i++) {
            boolean s = WaterPresetManager.hasPreset(i);
            presetButtons[i].setMessage(
                    s ? Component.literal("✓ " + (i + 1))
                            : Component.literal("\u3000" + (i + 1)));
            // Bright when saved, dimmed when empty
            presetButtons[i].setAlpha(s ? 1.0f : 0.5f);
        }
    }
}