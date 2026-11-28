package com.enderio.enderio.config.conduits.common;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ConduitsCommonConfig {
    public final ModConfigSpec.BooleanValue CAN_MIX_ENERGY_CONDUIT_TIERS;
    public final ModConfigSpec.BooleanValue CAN_MIX_FLUID_CONDUIT_TIERS;
    public final ModConfigSpec.BooleanValue CAN_MIX_ITEM_CONDUIT_TIERS;

    public final ModConfigSpec.IntValue ITEM_CONDUIT_MAX_SLEEP_TICKS;

    public ConduitsCommonConfig(ModConfigSpec.Builder builder) {
        builder.push("conduits");

        CAN_MIX_ENERGY_CONDUIT_TIERS = builder
            .comment("Whether players can mix and match energy conduits")
            .define("canMixEnergyConduitTiers", true);

        CAN_MIX_FLUID_CONDUIT_TIERS = builder
            .comment("Whether players can mix and match fluid conduits")
            .define("canMixFluidConduitTiers", true);

        CAN_MIX_ITEM_CONDUIT_TIERS = builder
            .comment("Whether players can mix and match item conduits")
            .define("canMixItemConduitTiers", true);

        ITEM_CONDUIT_MAX_SLEEP_TICKS = builder
            .comment("Item conduit extraction that keeps finding nothing to move is checked less often, waiting at most this many ticks.",
                "Set to 0 to disable.")
            .defineInRange("itemConduitMaxSleepTicks", 80, 0, 1200);

        builder.pop();
    }
}
