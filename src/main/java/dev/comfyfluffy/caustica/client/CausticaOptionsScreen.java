package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The Caustica-DLSS options window, opened by the mod's keybinding (default K). The main menu shows
 * the core RT controls (exposure, sampling, toggles, DLSS) each with its own reset button, and a
 * "细节调整" (Detail Tuning) button at the bottom that opens the {@link CausticaDetailOptionsScreen}
 * submenu for the artistic tuning knobs.
 *
 * <p>The backdrop is fully transparent: {@link #extractBackground} skips the vanilla blur and
 * dark-menu-background passes so the game view shows straight through the window. This is deliberate —
 * the window is a thin in-game overlay, not a modal pause screen.
 *
 * <p>Values are bound straight to {@link CausticaConfig} via {@code OptionInstance} value-update
 * listeners, so each change is applied on the next frame. Closing the screen persists the current
 * values to {@code config/caustica.toml}.
 */
public class CausticaOptionsScreen extends OptionsSubScreen {
    /** Width of the per-option reset button; deliberately narrower than a vanilla control. */
    private static final int RESET_WIDTH = 50;

    private final Screen parentScreen;

    public CausticaOptionsScreen(Screen lastScreen, Options options) {
        this(lastScreen, options, Component.translatable("caustica.options.title"));
    }

    protected CausticaOptionsScreen(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
        this.parentScreen = lastScreen;
    }

    public Screen getParentScreen() {
        return parentScreen;
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.rt.header"));
        this.list.addBig(Button.builder(
                Component.translatable("caustica.options.detail.open"),
                button -> this.minecraft.gui.setScreen(new CausticaDetailOptionsScreen(this, this.options)))
                .build());
        this.list.addBig(Button.builder(
                Component.translatable("caustica.options.water.open"),
                button -> this.minecraft.gui.setScreen(new CausticaWaterOptionsScreen(this, this.options)))
                .build());
        boolean ptEnabled = CausticaConfig.Rt.ENABLED.value();
        for (ResetableOption row : RtVideoOptions.mainOptions()) {
            boolean disabled = !ptEnabled && RtVideoOptions.isRrToggle(row.option());
            addOptionRow(row, disabled);
        }
    }

    /**
     * Appends one option as a row of {@code [option widget][compact reset button]}. The reset button
     * writes the option's factory default back (which triggers the config listener) and then re-syncs
     * the widget via {@code OptionsList#resetOption}, so the change applies on the next frame without
     * closing the menu.
     */
    protected void addOptionRow(ResetableOption row) {
        addOptionRow(row, false);
    }

    /**
     * Appends one option as a row of {@code [option widget][compact reset button]}, with the option
     * widget and reset button disabled (grayed out, non-interactive) when {@code disabled} is true.
     */
    protected void addOptionRow(ResetableOption row, boolean disabled) {
        AbstractWidget control = row.option().createButton(this.options);
        control.active = !disabled;
        Button reset = Button.builder(
                Component.translatable("caustica.options.reset"),
                button -> {
                    row.applyFactoryDefault();
                    this.list.resetOption(row.option());
                })
                .width(RESET_WIDTH)
                .build();
        reset.active = !disabled;
        this.list.addSmall(control, row.option(), reset);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int x, int y, float tickDelta) {
        // Fully transparent backdrop: supersede the vanilla background pass (panorama, blur, and the
        // dark menu-background texture) so the in-game view shows directly through the window. Only the
        // deferred-subtitle extraction a menu would normally contribute is kept.
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void removed() {
        // OptionsSubScreen.removed() saves vanilla Options; Caustica settings live in their own
        // TOML file, so persist those here on the same close.
        try {
            super.removed();
        } finally {
            CausticaConfig.save();
        }
    }
}