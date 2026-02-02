package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

public final class BuilderActionTameworkCaptureWild extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkCaptureWild";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkCaptureWild build(BuilderSupport support) {
        return new ActionTameworkCaptureWild(this, support);
    }

    public String getShortDescription() {
        return "Captures an untamed NPC into a spawner item.";
    }

    public String getLongDescription() {
        return "Custom action that captures a wild NPC when the player uses a valid spawner item.";
    }
}
