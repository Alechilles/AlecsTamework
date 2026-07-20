package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.Feature;

/** Builder for removing the sensor target from an NPC's hostile target memory. */
public final class BuilderActionTameworkForgetHostileTarget extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkForgetHostileTarget";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkForgetHostileTarget readConfig(JsonElement element) {
        requireFeature(Feature.LiveEntity);
        return this;
    }

    @Override
    public ActionTameworkForgetHostileTarget build(BuilderSupport support) {
        return new ActionTameworkForgetHostileTarget(this);
    }

    @Override
    public String getShortDescription() {
        return "Removes the sensor target from hostile target memory.";
    }

    @Override
    public String getLongDescription() {
        return "Forgets the current entity target so it cannot be reacquired from hostile target memory.";
    }
}
