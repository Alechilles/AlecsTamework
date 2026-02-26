package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedBelow;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkNeedBelow.
 */
public final class BuilderSensorTameworkNeedBelow extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedBelow";

    private final StringHolder need = new StringHolder();
    private final DoubleHolder ratioBelow = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkNeedBelow(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when Hunger/Thirst ratio is at or below a threshold.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks the Tamework needs component and matches when the selected need ratio is below the configured threshold.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkNeedBelow readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "Need",
                this.need,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Need type to evaluate: Hunger or Thirst.",
                null
        );
        this.getDouble(
                data,
                "RatioBelow",
                this.ratioBelow,
                0.85,
                null,
                BuilderDescriptorState.Stable,
                "Threshold in normalized ratio space [0..1].",
                null
        );
        return this;
    }

    @Nonnull
    public String getNeed(@Nonnull BuilderSupport support) {
        return this.need.get(support.getExecutionContext());
    }

    @Nonnull
    public double getRatioBelow(@Nonnull BuilderSupport support) {
        return this.ratioBelow.get(support.getExecutionContext());
    }
}
