package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerFly;
import javax.annotation.Nonnull;

/**
 * Builder for rider-controlled flight using the vanilla fly plumbing.
 */
public final class BuilderMotionControllerTameworkRideFly extends BuilderMotionControllerFly {
    public static final String BUILDER_ID = "TameworkRideFly";

    @Nonnull
    @Override
    public MotionControllerTameworkRideFly build(@Nonnull BuilderSupport builderSupport) {
        return new MotionControllerTameworkRideFly(builderSupport, this);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Rider-controlled Tamework flight motion controller.";
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
        return MotionControllerTameworkRideFly.class;
    }
}
