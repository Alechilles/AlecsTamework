package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;

/**
 * Base builder for Tamework actions without custom config payloads.
 */
public abstract class TameworkActionBuilderBase extends BuilderActionBase {
    public abstract String getBuilderId();

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Override
    // No config is consumed; return the builder as-is.
    public TameworkActionBuilderBase readConfig(JsonElement element) {
        return this;
    }
}
