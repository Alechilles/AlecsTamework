package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkLifeStage;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkLifeStage.
 */
public final class BuilderSensorTameworkLifeStage extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkLifeStage";

    private final StringHolder stage = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkLifeStage(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when the companion lifecycle stage matches.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks the companion lifecycle stage (Baby/Adolescent/Adult) from Tamework life-stage data.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkLifeStage readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "Stage",
                this.stage,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Lifecycle stage to match: Baby, Adolescent, or Adult.",
                null
        );
        return this;
    }

    @Nonnull
    public String getStage(@Nonnull BuilderSupport support) {
        return this.stage.get(support.getExecutionContext());
    }
}
