package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceConsumeSucceeded;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkNeedsResourceConsumeSucceeded.
 */
public final class BuilderSensorTameworkNeedsResourceConsumeSucceeded extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceConsumeSucceeded";

    private final StringHolder resourceType = new StringHolder();
    private final DoubleHolder maxAgeSeconds = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkNeedsResourceConsumeSucceeded(this, support);
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkNeedsResourceConsumeSucceeded readConfig(@Nonnull JsonElement data) {
        this.getString(
                data,
                "ResourceType",
                this.resourceType,
                "Auto",
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Resource consume kind to check: Water or FoodContainer.",
                null
        );
        this.getDouble(
                data,
                "MaxAgeSeconds",
                this.maxAgeSeconds,
                5.0,
                null,
                BuilderDescriptorState.Stable,
                "Maximum age of the successful consume attempt accepted by this sensor.",
                null
        );
        return this;
    }

    @Nonnull
    public String getResourceType(@Nonnull BuilderSupport support) {
        String value = resourceType.get(support.getExecutionContext());
        return value == null || value.isBlank() ? "Auto" : value;
    }

    public double getMaxAgeSeconds(@Nonnull BuilderSupport support) {
        return maxAgeSeconds.get(support.getExecutionContext());
    }

    @Nonnull
    public String getShortDescription() {
        return "Checks whether the last needs resource consume attempt succeeded.";
    }

    @Nonnull
    public String getLongDescription() {
        return "Matches briefly after a successful needs resource consume attempt so role assets can repeat paced consumption.";
    }
}
