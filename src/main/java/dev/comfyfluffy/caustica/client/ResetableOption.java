package dev.comfyfluffy.caustica.client;

import net.minecraft.client.OptionInstance;

/**
 * Pairs an options-window {@link OptionInstance} with the factory-default value it resets to. The option
 * is constructed with that default as its {@code initialValue} (so {@code OptionsList.resetOption} can
 * restore it) and then immediately synced to the current runtime value; {@link #factoryDefault()} is the
 * value-domain default written back on reset.
 *
 * @param option         the option widget
 * @param factoryDefault the option's value-domain factory default
 */
public record ResetableOption(OptionInstance<?> option, Object factoryDefault) {
    /**
     * Writes the factory default back into the option. Implemented on the raw {@link OptionInstance} to
     * sidestep the {@code OptionInstance<?>} wildcard capture; the value is always the very type the
     * option was built with ({@code Integer}, {@code Boolean} or {@code String}), so the cast is safe.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void applyFactoryDefault() {
        ((OptionInstance) option).set(factoryDefault);
    }
}