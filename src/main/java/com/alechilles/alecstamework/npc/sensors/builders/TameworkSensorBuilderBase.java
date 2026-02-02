package com.alechilles.alecstamework.npc.sensors.builders;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;

public abstract class TameworkSensorBuilderBase extends BuilderSensorBase {
    public abstract String getBuilderId();

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Override
    public TameworkSensorBuilderBase readConfig(JsonElement element) {
        return this;
    }
}
