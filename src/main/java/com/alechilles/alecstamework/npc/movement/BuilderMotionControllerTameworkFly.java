package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerFly;
import javax.annotation.Nonnull;

/**
 * Builder for Tamework flight using the vanilla fly plumbing with hover-friendly steering.
 */
public final class BuilderMotionControllerTameworkFly extends BuilderMotionControllerFly {
    public static final String BUILDER_ID = "TameworkFly";

    @Nonnull
    @Override
    public MotionControllerTameworkFly build(@Nonnull BuilderSupport builderSupport) {
        return new MotionControllerTameworkFly(builderSupport, this);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Tamework flight motion controller for autonomous and ridden NPCs.";
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
        return MotionControllerTameworkFly.class;
    }
}
