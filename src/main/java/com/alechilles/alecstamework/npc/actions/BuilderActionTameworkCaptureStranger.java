package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

/**
 * Builder for ActionTameworkCaptureStranger.
 */
public final class BuilderActionTameworkCaptureStranger extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkCaptureStranger";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkCaptureStranger build(BuilderSupport support) {
        return new ActionTameworkCaptureStranger(this, support);
    }

    public String getShortDescription() {
        return "Blocks capture when a non-owner interacts.";
    }

    public String getLongDescription() {
        return "Custom action that prevents non-owners from capturing a tamed NPC.";
    }
}
