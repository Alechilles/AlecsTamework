package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import javax.annotation.Nonnull;

/**
 * Builds body motion that turns mounted glide rider snapshots into steering.
 */
public final class BuilderBodyMotionTameworkMountedGlide extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkMountedGlide";

    @Nonnull
    @Override
    public BodyMotionTameworkMountedGlide build(BuilderSupport builderSupport) {
        return new BodyMotionTameworkMountedGlide(this);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Drive mounted glide steering from rider snapshots.";
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
