package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.Feature;

/** Builder for copying a sensor target NPC's home leash to the acting NPC. */
public final class BuilderActionTameworkSetLeashToTargetHome extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkSetLeashToTargetHome";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkSetLeashToTargetHome readConfig(JsonElement element) {
        requireFeature(Feature.AnyEntity);
        return this;
    }

    @Override
    public ActionTameworkSetLeashToTargetHome build(BuilderSupport support) {
        return new ActionTameworkSetLeashToTargetHome(this);
    }

    @Override
    public String getShortDescription() {
        return "Copies the target NPC's home leash point.";
    }

    @Override
    public String getLongDescription() {
        return "Uses the current entity sensor target to copy its persistent home point and leash orientation.";
    }
}
