package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedLowest;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkNeedLowest.
 */
public final class BuilderSensorTameworkNeedLowest extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedLowest";

    private final StringHolder need = new StringHolder();
    private final StringHolder otherNeed = new StringHolder();
    private final DoubleHolder allowedHigherBy = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkNeedLowest(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when one Hunger/Thirst ratio is the lower priority ratio.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks two Tamework needs and matches when the selected need's normalized ratio is lower than, tied with, or within the configured near-tie band of the other need.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkNeedLowest readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "Need",
                this.need,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Selected need type to compare: Hunger or Thirst.",
                null
        );
        this.requireString(
                data,
                "OtherNeed",
                this.otherNeed,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Other need type to compare against: Hunger or Thirst.",
                null
        );
        this.getDouble(
                data,
                "AllowedHigherBy",
                this.allowedHigherBy,
                0.0,
                null,
                BuilderDescriptorState.Stable,
                "Optional normalized ratio band where the selected need may be slightly higher than the other need and still match.",
                null
        );
        return this;
    }

    @Nonnull
    public String getNeed(@Nonnull BuilderSupport support) {
        return this.need.get(support.getExecutionContext());
    }

    @Nonnull
    public String getOtherNeed(@Nonnull BuilderSupport support) {
        return this.otherNeed.get(support.getExecutionContext());
    }

    public double getAllowedHigherBy(@Nonnull BuilderSupport support) {
        return this.allowedHigherBy.get(support.getExecutionContext());
    }
}
