package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the vanilla Quality section honest while the path tracer is active. The runtime-tunable RT
 * settings themselves now live in the Caustica-DLSS window (opened by the mod's keybinding, see
 * {@code CausticaOptionsScreen}); this mixin no longer injects them into the Video Settings screen.
 *
 * <p>When RT is enabled, the Quality section drops the vanilla options the path tracer supersedes
 * (Ambient Occlusion and Entity Shadows are computed by RT global illumination / RT shadows). When RT is
 * disabled the screen is left exactly as vanilla built it.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Shadow
    private static OptionInstance<?>[] qualityOptions(Options options) {
        throw new AssertionError("mixin stub");
    }

    @Redirect(
        method = "addOptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
    private OptionInstance<?>[] caustica$filterQualityOptions(Options options) {
        OptionInstance<?>[] base = qualityOptions(options);
        if (!CausticaConfig.Rt.ENABLED.value()) {
            return base;
        }
        List<OptionInstance<?>> kept = new ArrayList<>(base.length);
        for (OptionInstance<?> option : base) {
            // Path-traced GI + RT shadows make these vanilla raster controls inert under RT.
            if (option == options.ambientOcclusion() || option == options.entityShadows()) {
                continue;
            }
            kept.add(option);
        }
        return kept.toArray(OptionInstance<?>[]::new);
    }
}