package com.alechilles.alecstamework.npc.movement.builders;

import com.alechilles.alecstamework.npc.movement.BodyMotionTameworkMaintainDistance;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleRangeValidator;
import com.hypixel.hytale.server.npc.corecomponents.movement.builders.BuilderBodyMotionMaintainDistance;
import javax.annotation.Nonnull;

/** Builds target-relative distance steering with a static positioning angle. */
public final class BuilderBodyMotionTameworkMaintainDistance extends BuilderBodyMotionMaintainDistance {
    public static final String BUILDER_ID = "TameworkMaintainDistance";

    private final DoubleHolder positioningAngle = new DoubleHolder();

    @Nonnull
    @Override
    public BuilderBodyMotionTameworkMaintainDistance readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getDouble(
                data,
                "PositioningAngle",
                positioningAngle,
                180.0,
                DoubleRangeValidator.between(-180.0, 180.0),
                BuilderDescriptorState.WorkInProgress,
                "Position in degrees relative to the target's facing direction; 180 stays in front.",
                null
        );
        return this;
    }

    @Nonnull
    @Override
    public BodyMotionTameworkMaintainDistance build(@Nonnull BuilderSupport builderSupport) {
        return new BodyMotionTameworkMaintainDistance(this, builderSupport);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Maintain distance at a configured angle relative to a target.";
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

    public double getPositioningAngle(@Nonnull BuilderSupport support) {
        return positioningAngle.get(support.getExecutionContext());
    }
}
