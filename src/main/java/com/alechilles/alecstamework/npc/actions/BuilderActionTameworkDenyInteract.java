package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

/**
 * Builder for ActionTameworkDenyInteract.
 */
public final class BuilderActionTameworkDenyInteract extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkDenyInteract";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkDenyInteract build(BuilderSupport support) {
        return new ActionTameworkDenyInteract(this, support);
    }

    public String getShortDescription() {
        return "Sends a message to non-owners when they try to interact.";
    }

    public String getLongDescription() {
        return "Custom action that notifies non-owners they cannot interact with a pet.";
    }
}
