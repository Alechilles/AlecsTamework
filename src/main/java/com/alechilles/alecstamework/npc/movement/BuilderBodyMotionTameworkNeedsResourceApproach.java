package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import javax.annotation.Nonnull;

/**
 * Builds the needs-resource approach motion used to steer toward already preflighted food and water targets.
 */
public final class BuilderBodyMotionTameworkNeedsResourceApproach extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceApproach";

    private final DoubleHolder stopDistance = new DoubleHolder();
    private final DoubleHolder relativeSpeed = new DoubleHolder();

    @Nonnull
    @Override
    public BuilderBodyMotionTameworkNeedsResourceApproach readConfig(@Nonnull JsonElement data) {
        super.readCommonConfig(data);
        getDouble(
                data,
                "StopDistance",
                stopDistance,
                BodyMotionTameworkNeedsResourceApproach.DEFAULT_STOP_DISTANCE,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Distance from the resource target where approach steering should stop.",
                null
        );
        getDouble(
                data,
                "RelativeSpeed",
                relativeSpeed,
                BodyMotionTameworkNeedsResourceApproach.DEFAULT_RELATIVE_SPEED,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Relative movement speed used while approaching the resource target.",
                null
        );
        return this;
    }

    @Nonnull
    @Override
    public BodyMotionTameworkNeedsResourceApproach build(@Nonnull BuilderSupport builderSupport) {
        return new BodyMotionTameworkNeedsResourceApproach(this, builderSupport);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Directly steers toward a preflighted Tamework needs resource target.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return getShortDescription();
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.WorkInProgress;
    }

    double getStopDistance(@Nonnull BuilderSupport support) {
        return BodyMotionTameworkNeedsResourceApproach.sanitizeStopDistance(
                stopDistance.get(support.getExecutionContext())
        );
    }

    double getRelativeSpeed(@Nonnull BuilderSupport support) {
        return BodyMotionTameworkNeedsResourceApproach.sanitizeRelativeSpeed(
                relativeSpeed.get(support.getExecutionContext())
        );
    }
}
