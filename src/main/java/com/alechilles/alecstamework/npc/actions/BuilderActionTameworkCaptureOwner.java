package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

public final class BuilderActionTameworkCaptureOwner extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkCaptureOwner";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkCaptureOwner build(BuilderSupport support) {
        return new ActionTameworkCaptureOwner(this, support);
    }

    public String getShortDescription() {
        return "Captures a tamed NPC if the interactor is the owner.";
    }

    public String getLongDescription() {
        return "Custom action that captures a tamed NPC into a spawner item when the owner interacts.";
    }
}
