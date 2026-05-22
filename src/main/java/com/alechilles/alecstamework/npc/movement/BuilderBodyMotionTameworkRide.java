package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import javax.annotation.Nonnull;

/**
 * Builds the mounted-ride body motion shell that turns rider snapshots into NPC steering.
 */
public final class BuilderBodyMotionTameworkRide extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkRide";

    @Nonnull
    @Override
    public BodyMotionTameworkRide build(BuilderSupport builderSupport) {
        return new BodyMotionTameworkRide(this);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Drive NPC movement from a Tamework ride input snapshot.";
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
}
