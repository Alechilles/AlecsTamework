package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;

public abstract class TameworkActionBuilderBase extends BuilderActionBase {
    public abstract String getBuilderId();

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Override
    public TameworkActionBuilderBase readConfig(JsonElement element) {
        return this;
    }
}
