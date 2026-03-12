package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkEffectActive;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkEffectActive.
 */
public final class BuilderSensorTameworkEffectActive extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkEffectActive";

    private final StringHolder effectId = new StringHolder();
    private final DoubleHolder minRemainingSeconds = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkEffectActive(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when the configured EntityEffect is active on the NPC.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks this NPC's active EntityEffects and optionally requires a minimum remaining duration.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkEffectActive readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "EffectId",
                this.effectId,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "EntityEffect asset id to check.",
                null
        );
        this.getDouble(
                data,
                "MinRemainingSeconds",
                this.minRemainingSeconds,
                0.0,
                null,
                BuilderDescriptorState.Stable,
                "Optional minimum remaining duration in seconds.",
                null
        );
        return this;
    }

    @Nonnull
    public String getEffectId(@Nonnull BuilderSupport support) {
        return this.effectId.get(support.getExecutionContext());
    }

    public double getMinRemainingSeconds(@Nonnull BuilderSupport support) {
        return this.minRemainingSeconds.get(support.getExecutionContext());
    }
}
