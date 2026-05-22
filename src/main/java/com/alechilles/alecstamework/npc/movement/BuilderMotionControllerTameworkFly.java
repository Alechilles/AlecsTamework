package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerFly;
import javax.annotation.Nonnull;

/**
 * Builder for Tamework flight using the vanilla fly plumbing with hover-friendly steering.
 */
public final class BuilderMotionControllerTameworkFly extends BuilderMotionControllerFly {
    public static final String BUILDER_ID = "TameworkFly";
    static final double DEFAULT_MOUNTED_SPRINT_MULTIPLIER = 1.35;

    private final DoubleHolder mountedMaxHorizontalSpeed = new DoubleHolder();
    private final DoubleHolder mountedMaxClimbSpeed = new DoubleHolder();
    private final DoubleHolder mountedMaxSinkSpeed = new DoubleHolder();
    private final DoubleHolder mountedAcceleration = new DoubleHolder();
    private final DoubleHolder mountedDeceleration = new DoubleHolder();
    private final DoubleHolder mountedSprintMultiplier = new DoubleHolder();

    @Nonnull
    @Override
    public BuilderMotionControllerTameworkFly readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getDouble(
                data,
                "MountedMaxHorizontalSpeed",
                mountedMaxHorizontalSpeed,
                -1.0,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Mounted horizontal flight speed. Values <= 0 fall back to MaxHorizontalSpeed.",
                null
        );
        getDouble(
                data,
                "MountedMaxClimbSpeed",
                mountedMaxClimbSpeed,
                -1.0,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Mounted upward flight speed. Values <= 0 fall back to MaxClimbSpeed.",
                null
        );
        getDouble(
                data,
                "MountedMaxSinkSpeed",
                mountedMaxSinkSpeed,
                -1.0,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Mounted downward flight speed. Values <= 0 fall back to MaxSinkSpeed.",
                null
        );
        getDouble(
                data,
                "MountedAcceleration",
                mountedAcceleration,
                -1.0,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Mounted flight acceleration. Values <= 0 fall back to Acceleration.",
                null
        );
        getDouble(
                data,
                "MountedDeceleration",
                mountedDeceleration,
                -1.0,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Mounted flight deceleration. Values <= 0 fall back to Deceleration.",
                null
        );
        getDouble(
                data,
                "MountedSprintMultiplier",
                mountedSprintMultiplier,
                DEFAULT_MOUNTED_SPRINT_MULTIPLIER,
                null,
                BuilderDescriptorState.WorkInProgress,
                "Multiplier applied to mounted horizontal flight speed while the rider is sprinting.",
                null
        );
        return this;
    }

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

    public double getMountedMaxHorizontalSpeed(@Nonnull BuilderSupport support, double fallback) {
        return positiveOrFallback(mountedMaxHorizontalSpeed.get(support.getExecutionContext()), fallback);
    }

    public double getMountedMaxClimbSpeed(@Nonnull BuilderSupport support, double fallback) {
        return positiveOrFallback(mountedMaxClimbSpeed.get(support.getExecutionContext()), fallback);
    }

    public double getMountedMaxSinkSpeed(@Nonnull BuilderSupport support, double fallback) {
        return positiveOrFallback(mountedMaxSinkSpeed.get(support.getExecutionContext()), fallback);
    }

    public double getMountedAcceleration(@Nonnull BuilderSupport support, double fallback) {
        return positiveOrFallback(mountedAcceleration.get(support.getExecutionContext()), fallback);
    }

    public double getMountedDeceleration(@Nonnull BuilderSupport support, double fallback) {
        return positiveOrFallback(mountedDeceleration.get(support.getExecutionContext()), fallback);
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
