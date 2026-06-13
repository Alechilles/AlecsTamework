package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceMovementStalled;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkNeedsResourceMovementStalled.
 */
public final class BuilderSensorTameworkNeedsResourceMovementStalled extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceMovementStalled";

    private final StringHolder resourceType = new StringHolder();
    private final DoubleHolder graceSeconds = new DoubleHolder();
    private final DoubleHolder minProgress = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkNeedsResourceMovementStalled(this, support);
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkNeedsResourceMovementStalled readConfig(@Nonnull JsonElement data) {
        this.getString(
                data,
                "ResourceType",
                this.resourceType,
                "Auto",
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Resource target kind being moved toward: Water or FoodContainer.",
                null
        );
        this.getDouble(
                data,
                "GraceSeconds",
                this.graceSeconds,
                8.0,
                null,
                BuilderDescriptorState.Stable,
                "Seconds after movement start before progress can be considered stalled.",
                null
        );
        this.getDouble(
                data,
                "MinProgress",
                this.minProgress,
                0.75,
                null,
                BuilderDescriptorState.Stable,
                "Minimum distance the NPC must close toward the target before the grace period ends.",
                null
        );
        return this;
    }

    @Nonnull
    public String getResourceType(@Nonnull BuilderSupport support) {
        String value = resourceType.get(support.getExecutionContext());
        return value == null || value.isBlank() ? "Auto" : value;
    }

    public double getGraceSeconds(@Nonnull BuilderSupport support) {
        return graceSeconds.get(support.getExecutionContext());
    }

    public double getMinProgress(@Nonnull BuilderSupport support) {
        return minProgress.get(support.getExecutionContext());
    }

    @Nonnull
    public String getShortDescription() {
        return "Detects needs seek movement that has not made enough progress.";
    }

    @Nonnull
    public String getLongDescription() {
        return "Checks the active needs-seek progress tracker so templates can reject stalled movement targets before timeout.";
    }
}
