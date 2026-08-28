package com.extremecraftingtable;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for Extreme Crafting Table.
 * <p>
 * Controls energy behavior: noEnergy mode for creative/testing,
 * energy capacity and receive rate for the workbench.
 */
public class Config {
    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;

    public final ModConfigSpec.BooleanValue noEnergy;
    public final ModConfigSpec.IntValue workbenchCapacity;
    public final ModConfigSpec.IntValue workbenchMaxReceive;

    static {
        var pair = new ModConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private Config(ModConfigSpec.Builder builder) {
        builder.push("energy");
        noEnergy = builder
            .comment("If true, the workbench disables the energy system entirely: recipes craft without draining the energy buffer, and the buffer is not charged (the energy bar stays empty). Useful for creative/test mode.")
            .define("noEnergy", false);
        workbenchCapacity = builder
            .comment("Maximum FE energy storage for the workbench.")
            .defineInRange("workbenchCapacity", 100_000, 1_000, 10_000_000);
        workbenchMaxReceive = builder
            .comment("Maximum FE per tick the workbench can receive.")
            .defineInRange("workbenchMaxReceive", 10_000, 100, 1_000_000);
        builder.pop();
    }
}