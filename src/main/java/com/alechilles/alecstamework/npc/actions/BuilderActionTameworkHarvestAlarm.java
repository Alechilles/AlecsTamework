package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.instructions.Action;
import javax.annotation.Nonnull;

/**
 * Builder for {@link ActionTameworkHarvestAlarm}.
 */
public final class BuilderActionTameworkHarvestAlarm extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkHarvestAlarm";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport support) {
        return new ActionTameworkHarvestAlarm(this);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Set the harvest cooldown alarm with talent scaling.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Sets Harvest_Ready using the role's HarvestTimeout parameter, scaled by HarvestCooldownMultiplier.";
    }
}
