package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.StringJoiner;

/**
 * Manages saving and loading of water detail + wave detail preset snapshots.
 * Each preset stores 26 values covering all settings in the "水体细节" and "水波细分" menus.
 */
public final class WaterPresetManager {
    private WaterPresetManager() {
    }

    /** Index of the preset currently loaded into the UI, or -1 if none. */
    private static int activePreset = -1;

    public static int activePreset() {
        return activePreset;
    }

    public static void clearActivePreset() {
        activePreset = -1;
    }

    /** Returns true if the given preset index has saved data. */
    public static boolean hasPreset(int index) {
        return !configFor(index).get().isEmpty();
    }

    /** Saves all current water detail + wave detail setting values into the given preset. */
    public static void savePreset(int index) {
        StringJoiner sj = new StringJoiner(";");
        // Wave detail settings (7)
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_STRENGTH.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_HEIGHT.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_SPEED.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_PRESET.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.CUSTOM_MEANDER.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_DISPERSION.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_COUNT.value()));
        // Per-band amplitude multipliers (11)
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_0.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_1.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_2.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_3.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_4.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_5.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_6.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_7.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_8.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_9.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WAVE_BAND_10.value()));
        // Water detail settings (8)
        sj.add(String.valueOf(CausticaConfig.Rt.Water.CAUSTIC_BRIGHTNESS.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_DENSITY.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_OPACITY.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_COLOR_R.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_COLOR_G.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_COLOR_B.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_COLOR_BLEND.value()));
        sj.add(String.valueOf(CausticaConfig.Rt.Water.WATER_SHADOW_TINT.value()));
        configFor(index).set(sj.toString());
        CausticaConfig.save();
        activePreset = index;
    }

    /** Loads saved values from the given preset into all water detail + wave detail settings. */
    public static void loadPreset(int index) {
        String data = configFor(index).get();
        if (data.isEmpty()) return;
        String[] parts = data.split(";");
        // Accept 26 (current) or 18 (legacy — wave-only preset)
        if (parts.length != 26 && parts.length != 18) return;
        int i = 0;
        // Wave detail settings (7)
        CausticaConfig.Rt.Water.WAVE_STRENGTH.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_HEIGHT.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_SPEED.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_PRESET.set(Integer.parseInt(parts[i++]));
        CausticaConfig.Rt.Water.CUSTOM_MEANDER.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WATER_DISPERSION.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_COUNT.set(Integer.parseInt(parts[i++]));
        // Per-band amplitude multipliers (11)
        CausticaConfig.Rt.Water.WAVE_BAND_0.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_1.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_2.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_3.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_4.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_5.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_6.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_7.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_8.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_9.set(Float.parseFloat(parts[i++]));
        CausticaConfig.Rt.Water.WAVE_BAND_10.set(Float.parseFloat(parts[i++]));
        // Water detail settings (8) — only present in current (26-value) format
        if (parts.length >= 26) {
            CausticaConfig.Rt.Water.CAUSTIC_BRIGHTNESS.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_DENSITY.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_OPACITY.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_COLOR_R.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_COLOR_G.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_COLOR_B.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_COLOR_BLEND.set(Float.parseFloat(parts[i++]));
            CausticaConfig.Rt.Water.WATER_SHADOW_TINT.set(Float.parseFloat(parts[i++]));
        }
        CausticaConfig.save();
        activePreset = index;
    }

    /** Clears the saved data from the given preset. */
    public static void clearPreset(int index) {
        configFor(index).set("");
        CausticaConfig.save();
        if (activePreset == index) {
            activePreset = -1;
        }
    }

    private static CausticaConfig.StringSetting configFor(int index) {
        return switch (index) {
            case 0 -> CausticaConfig.Rt.Water.PRESET_1;
            case 1 -> CausticaConfig.Rt.Water.PRESET_2;
            case 2 -> CausticaConfig.Rt.Water.PRESET_3;
            case 3 -> CausticaConfig.Rt.Water.PRESET_4;
            case 4 -> CausticaConfig.Rt.Water.PRESET_5;
            case 5 -> CausticaConfig.Rt.Water.PRESET_6;
            case 6 -> CausticaConfig.Rt.Water.PRESET_7;
            case 7 -> CausticaConfig.Rt.Water.PRESET_8;
            case 8 -> CausticaConfig.Rt.Water.PRESET_9;
            case 9 -> CausticaConfig.Rt.Water.PRESET_10;
            case 10 -> CausticaConfig.Rt.Water.PRESET_11;
            case 11 -> CausticaConfig.Rt.Water.PRESET_12;
            case 12 -> CausticaConfig.Rt.Water.PRESET_13;
            case 13 -> CausticaConfig.Rt.Water.PRESET_14;
            default -> throw new IllegalArgumentException("Invalid preset index: " + index);
        };
    }
}