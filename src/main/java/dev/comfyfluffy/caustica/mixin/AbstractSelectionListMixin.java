package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CausticaOptionsScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.OptionsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the dark list-backdrop texture (and its top/bottom separators) on an {@link OptionsList}
 * when that list belongs to a {@link CausticaOptionsScreen}, giving the Caustica-DLSS window a fully
 * transparent background so the game view shows straight through. All other selection lists are left
 * untouched.
 */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {
    @Inject(
        method = "extractListBackground",
        at = @At("HEAD"),
        cancellable = true)
    private void caustica$skipListBackground(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        if (isCausticaOptionsList()) {
            ci.cancel();
        }
    }

    @Inject(
        method = "extractListSeparators",
        at = @At("HEAD"),
        cancellable = true)
    private void caustica$skipListSeparators(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        if (isCausticaOptionsList()) {
            ci.cancel();
        }
    }

    private boolean isCausticaOptionsList() {
        // Runtime check: only OptionsList (not other AbstractSelectionList subclasses) carries the owning
        // screen, and only when it belongs to a CausticaOptionsScreen should the backdrop be suppressed.
        return OptionsList.class.isInstance(this)
                && ((OptionsListAccessor) this).caustica$getScreen() instanceof CausticaOptionsScreen;
    }
}