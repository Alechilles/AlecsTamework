package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

public final class BuilderActionTameworkSetOwner extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkSetOwner";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkSetOwner build(BuilderSupport support) {
        return new ActionTameworkSetOwner(this, support);
    }

    public String getShortDescription() {
        return "Sets the NPC owner to the interacting player.";
    }

    public String getLongDescription() {
        return "Custom action that sets Tamework owner data based on the player that interacted.";
    }
}
