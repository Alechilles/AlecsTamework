package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

public final class BuilderActionTameworkSetTamed extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkSetTamed";
    private boolean value;

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkSetTamed readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getBoolean(
                element,
                "Value",
                b -> this.value = b,
                false,
                BuilderDescriptorState.Stable,
                "Whether to set the tamed flag to true or false.",
                null
        );
        // Legacy alias: allow "Tamed" as a fallback if used by older configs.
        getBoolean(
                element,
                "Tamed",
                b -> this.value = b,
                this.value,
                BuilderDescriptorState.Stable,
                "Legacy alias for Value.",
                null
        );
        return this;
    }

    public boolean getValue() {
        return value;
    }

    public ActionTameworkSetTamed build(BuilderSupport support) {
        return new ActionTameworkSetTamed(this, support);
    }

    public String getShortDescription() {
        return "Sets the NPC tamed flag to a configured value.";
    }

    public String getLongDescription() {
        return "Custom action that sets the Tamework tamed flag on an NPC (default false).";
    }
}
