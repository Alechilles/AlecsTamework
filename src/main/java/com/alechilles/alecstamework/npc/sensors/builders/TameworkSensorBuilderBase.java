package com.alechilles.alecstamework.npc.sensors.builders;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;

/**
 * Base builder for Tamework sensors without custom config payloads.
 */
public abstract class TameworkSensorBuilderBase extends BuilderSensorBase {
    public abstract String getBuilderId();

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Override
    // No config is consumed; return the builder as-is.
    public TameworkSensorBuilderBase readConfig(JsonElement element) {
        return this;
    }
}
