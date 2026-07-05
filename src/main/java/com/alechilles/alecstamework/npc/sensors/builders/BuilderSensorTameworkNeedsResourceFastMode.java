package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceFastMode;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for the needs resource fast-mode policy sensor.
 */
public final class BuilderSensorTameworkNeedsResourceFastMode extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceFastMode";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkNeedsResourceFastMode(this);
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkNeedsResourceFastMode readConfig(@Nonnull JsonElement data) {
        return this;
    }

    @Nonnull
    public String getShortDescription() {
        return "Matches while needs fast consume mode is active.";
    }

    @Nonnull
    public String getLongDescription() {
        return "Exposes the /tw settings needs fast-consume policy to role templates.";
    }
}
