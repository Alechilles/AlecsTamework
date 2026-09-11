package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumHolder;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerFly;
import javax.annotation.Nonnull;

/**
 * Fly controller configuration which leaves room for formation steering to slow down.
 */
public final class BuilderMotionControllerTameworkFormationFly extends BuilderMotionControllerFly implements Cloneable {
    public static final String BUILDER_ID = "TameworkFormationFly";

    private final EnumHolder<BuilderBodyMotionTameworkFlightFormation.Formation> formation = new EnumHolder<>();
    private boolean resolvedFormationEnabled;

    @Nonnull
    @Override
    public BuilderMotionControllerTameworkFormationFly readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getEnum(
                data,
                "Formation",
                formation,
                BuilderBodyMotionTameworkFlightFormation.Formation.class,
                BuilderBodyMotionTameworkFlightFormation.Formation.NONE,
                BuilderDescriptorState.WorkInProgress,
                "Flight formation type; non-None forms reserve air speed for formation steering.",
                null
        );
        return this;
    }

    @Override
    public double getMinAirSpeed() {
        return resolvedFormationEnabled ? Math.min(super.getMinAirSpeed(), 0.1) : super.getMinAirSpeed();
    }

    @Nonnull
    @Override
    public MotionControllerTameworkFormationFly build(@Nonnull BuilderSupport builderSupport) {
        return new MotionControllerTameworkFormationFly(
                builderSupport,
                resolvedFor(builderSupport)
        );
    }

    @Nonnull
    @Override
    public String getType() {
        return MotionControllerFly.TYPE;
    }

    @Nonnull
    private BuilderMotionControllerTameworkFormationFly resolvedFor(@Nonnull BuilderSupport builderSupport) {
        try {
            // Native MotionControllerFly captures the final minimum air speed in its constructor. Resolve the
            // computable flag into a per-build view instead of mutating the shared parsed builder.
            BuilderMotionControllerTameworkFormationFly copy =
                    (BuilderMotionControllerTameworkFormationFly) super.clone();
            copy.resolvedFormationEnabled = isFormationEnabled(builderSupport);
            return copy;
        } catch (CloneNotSupportedException exception) {
            throw new AssertionError(exception);
        }
    }

    boolean isFormationEnabled(@Nonnull BuilderSupport builderSupport) {
        return formation.get(builderSupport.getExecutionContext())
                != BuilderBodyMotionTameworkFlightFormation.Formation.NONE;
    }
}
