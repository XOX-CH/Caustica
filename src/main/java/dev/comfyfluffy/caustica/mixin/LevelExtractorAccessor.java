package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Accessor("lastViewDistance")
    void caustica$setLastViewDistance(int lastViewDistance);

    @Accessor("prevCamRotX")
    void caustica$setPrevCamRotX(double value);

    @Accessor("prevCamRotY")
    void caustica$setPrevCamRotY(double value);
}