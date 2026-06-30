package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerFly;
import javax.annotation.Nonnull;

/**
 * Builder for the mounted glide motion controller.
 */
public final class BuilderMotionControllerTameworkMountedGlide extends BuilderMotionControllerFly {
    public static final String BUILDER_ID = "TameworkMountedGlide";

    @Nonnull
    @Override
    public MotionControllerTameworkMountedGlide build(@Nonnull BuilderSupport builderSupport) {
        return new MotionControllerTameworkMountedGlide(builderSupport, this);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Tamework mounted glide controller using pitch and flap physics.";
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

    @Nonnull
    @Override
    public Class<MotionController> category() {
        return MotionController.class;
    }

    @Nonnull
    @Override
    public String getType() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Class<? extends MotionController> getClassType() {
        return MotionControllerTameworkMountedGlide.class;
    }
}
