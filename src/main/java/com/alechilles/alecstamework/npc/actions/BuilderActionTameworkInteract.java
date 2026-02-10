package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

/**
 * Builder for ActionTameworkInteract.
 */
public final class BuilderActionTameworkInteract extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkInteract";

    private String configId;

    @Override
    public BuilderActionTameworkInteract readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getString(
                element,
                "ConfigId",
                value -> this.configId = value,
                null,
                null,
                BuilderDescriptorState.Stable,
                "Optional interaction config id override.",
                "If set, this config id is used instead of RoleIds matching."
        );
        return this;
    }

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public String getConfigId() {
        return configId;
    }

    public ActionTameworkInteract build(BuilderSupport support) {
        return new ActionTameworkInteract(this, support);
    }

    public String getShortDescription() {
        return "Runs the optimized Tamework interaction pipeline.";
    }

    public String getLongDescription() {
        return "Custom action that resolves a TwInteractionConfig and executes the first valid interaction.";
    }
}
