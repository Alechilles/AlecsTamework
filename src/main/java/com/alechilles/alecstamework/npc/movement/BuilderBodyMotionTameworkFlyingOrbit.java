package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleRangeValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import javax.annotation.Nonnull;

/** Builds target-relative orbit and approach steering for flying NPCs. */
public final class BuilderBodyMotionTameworkFlyingOrbit extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkFlyingOrbit";

    private final DoubleHolder orbitRadius = new DoubleHolder();
    private final DoubleHolder orbitRadiusTolerance = new DoubleHolder();
    private final DoubleHolder orbitDurationMin = new DoubleHolder();
    private final DoubleHolder orbitDurationMax = new DoubleHolder();
    private final DoubleHolder approachDurationMin = new DoubleHolder();
    private final DoubleHolder approachDurationMax = new DoubleHolder();
    private final DoubleHolder approachStopDistance = new DoubleHolder();
    private final DoubleHolder approachSlowDownDistance = new DoubleHolder();
    private final DoubleHolder relativeSpeed = new DoubleHolder();

    @Nonnull
    @Override
    public BuilderBodyMotionTameworkFlyingOrbit readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getDouble(data, "OrbitRadius", orbitRadius, 18.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Preferred horizontal orbit radius around the target.", null);
        getDouble(data, "OrbitRadiusTolerance", orbitRadiusTolerance, 4.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Radius error allowed before radial correction reaches full strength.", null);
        getDouble(data, "OrbitDurationMin", orbitDurationMin, 8.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Minimum orbit phase duration in seconds.", null);
        getDouble(data, "OrbitDurationMax", orbitDurationMax, 12.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Maximum orbit phase duration in seconds.", null);
        getDouble(data, "ApproachDurationMin", approachDurationMin, 3.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Minimum approach phase duration in seconds.", null);
        getDouble(data, "ApproachDurationMax", approachDurationMax, 5.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Maximum approach phase duration in seconds.", null);
        getDouble(data, "ApproachStopDistance", approachStopDistance, 6.0, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.WorkInProgress, "Horizontal distance at which an approach stops.", null);
        getDouble(data, "ApproachSlowDownDistance", approachSlowDownDistance, 14.0,
                DoubleSingleValidator.greater0(), BuilderDescriptorState.WorkInProgress,
                "Horizontal distance at which an approach starts slowing down.", null);
        getDouble(data, "RelativeSpeed", relativeSpeed, 0.5,
                DoubleRangeValidator.fromExclToIncl(0, 2), BuilderDescriptorState.WorkInProgress,
                "Relative speed used by orbit and approach steering.", null);
        return this;
    }

    @Nonnull
    @Override
    public BodyMotionTameworkFlyingOrbit build(@Nonnull BuilderSupport builderSupport) {
        return new BodyMotionTameworkFlyingOrbit(this, builderSupport);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Orbit a target in flight and periodically approach it.";
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

    double getOrbitRadius(BuilderSupport support) {
        return orbitRadius.get(support.getExecutionContext());
    }

    double getOrbitRadiusTolerance(BuilderSupport support) {
        return orbitRadiusTolerance.get(support.getExecutionContext());
    }

    double getOrbitDurationMin(BuilderSupport support) {
        return orbitDurationMin.get(support.getExecutionContext());
    }

    double getOrbitDurationMax(BuilderSupport support) {
        return orbitDurationMax.get(support.getExecutionContext());
    }

    double getApproachDurationMin(BuilderSupport support) {
        return approachDurationMin.get(support.getExecutionContext());
    }

    double getApproachDurationMax(BuilderSupport support) {
        return approachDurationMax.get(support.getExecutionContext());
    }

    double getApproachStopDistance(BuilderSupport support) {
        return approachStopDistance.get(support.getExecutionContext());
    }

    double getApproachSlowDownDistance(BuilderSupport support) {
        return approachSlowDownDistance.get(support.getExecutionContext());
    }

    double getRelativeSpeed(BuilderSupport support) {
        return relativeSpeed.get(support.getExecutionContext());
    }
}
