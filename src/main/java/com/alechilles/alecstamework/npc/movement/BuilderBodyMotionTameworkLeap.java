package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import javax.annotation.Nonnull;

/** Builds a finite, collision-checked leap to a position captured at takeoff. */
public final class BuilderBodyMotionTameworkLeap extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkLeap";
    private final DoubleHolder duration = new DoubleHolder();
    private final DoubleHolder height = new DoubleHolder();

    @Override
    public BuilderBodyMotionTameworkLeap readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getDouble(data, "Duration", duration, 1.2, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "Flight time in seconds, independent of target distance.", null);
        getDouble(data, "Height", height, 4.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "Arc height above the line joining takeoff and landing.", null);
        return this;
    }

    @Override
    public BodyMotionTameworkLeap build(@Nonnull BuilderSupport support) {
        return new BodyMotionTameworkLeap(this, duration.get(support.getExecutionContext()),
                height.get(support.getExecutionContext()));
    }

    @Override
    public String getShortDescription() {
        return "Leap to the sensor position captured at takeoff, stopping at solid terrain.";
    }

    @Override
    public String getLongDescription() {
        return getShortDescription();
    }

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }
}
