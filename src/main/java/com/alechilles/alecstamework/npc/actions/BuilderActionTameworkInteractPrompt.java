package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.google.gson.JsonElement;

/**
 * Builder for ActionTameworkInteractPrompt.
 */
public final class BuilderActionTameworkInteractPrompt extends BuilderActionTameworkInteract {
    public static final String BUILDER_ID = "TameworkInteractPrompt";

    @Override
    public BuilderActionTameworkInteractPrompt readConfig(JsonElement element) {
        super.readConfig(element);
        return this;
    }

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkInteractPrompt build(BuilderSupport support) {
        return new ActionTameworkInteractPrompt(this, support);
    }

    public String getShortDescription() {
        return "Sets interaction prompts based on the first matching Tamework interaction.";
    }

    public String getLongDescription() {
        return "Resolves the first eligible interaction entry and updates the NPC interaction prompt hint.";
    }
}
