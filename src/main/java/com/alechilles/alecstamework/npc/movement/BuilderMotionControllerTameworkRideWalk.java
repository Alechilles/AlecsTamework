package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerWalk;
import javax.annotation.Nonnull;

/**
 * Walk controller variant that can use a different max speed while controlled by a Tamework rider.
 */
public final class BuilderMotionControllerTameworkRideWalk extends BuilderMotionControllerWalk {
    public static final String BUILDER_ID = "TameworkRideWalk";
    static final double DEFAULT_MOUNTED_SPRINT_MULTIPLIER = 1.35;

    private final DoubleHolder mountedMaxWalkSpeed = new DoubleHolder();
    private final DoubleHolder mountedSprintMultiplier = new DoubleHolder();

    @Nonnull
    @Override
    public BuilderMotionControllerTameworkRideWalk readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getDouble(
                data,
                "MountedMaxWalkSpeed",
                mountedMaxWalkSpeed,
                -1.0,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Mounted walking speed. Values <= 0 fall back to MaxWalkSpeed.",
                null
        );
        getDouble(
                data,
                "MountedSprintMultiplier",
                mountedSprintMultiplier,
                DEFAULT_MOUNTED_SPRINT_MULTIPLIER,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Multiplier applied to mounted walk speed while the rider is sprinting.",
                null
        );
        return this;
    }

    @Nonnull
    @Override
    public MotionControllerTameworkRideWalk build(@Nonnull BuilderSupport builderSupport) {
        return new MotionControllerTameworkRideWalk(this, builderSupport);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Tamework ridden walk controller with mounted speed overrides.";
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
        return MotionControllerTameworkRideWalk.class;
    }

    public double getMountedMaxWalkSpeed(@Nonnull BuilderSupport support, double fallback) {
        return positiveOrFallback(mountedMaxWalkSpeed.get(support.getExecutionContext()), fallback);
    }

    public double getMountedSprintMultiplier(@Nonnull BuilderSupport support) {
        return positiveOrFallback(
                mountedSprintMultiplier.get(support.getExecutionContext()),
                DEFAULT_MOUNTED_SPRINT_MULTIPLIER
        );
    }

    private static double positiveOrFallback(double value, double fallback) {
        return value > 0.0 ? value : fallback;
    }
}
