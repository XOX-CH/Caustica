package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the owning {@link OptionsSubScreen} of an {@link OptionsList} so the list-background mixin can
 * decide whether to suppress the backdrop for a {@code CausticaOptionsScreen}.
 */
@Mixin(OptionsList.class)
public interface OptionsListAccessor {
    @Accessor("screen")
    OptionsSubScreen caustica$getScreen();
}