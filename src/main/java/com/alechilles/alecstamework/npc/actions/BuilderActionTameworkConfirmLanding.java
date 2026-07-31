package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.Feature;

/** Builder for confirming physical landing contact and activating the walk controller. */
public final class BuilderActionTameworkConfirmLanding extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkConfirmLanding";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkConfirmLanding readConfig(JsonElement element) {
        requireFeature(Feature.LiveEntity);
        return this;
    }

    @Override
    public ActionTameworkConfirmLanding build(BuilderSupport support) {
        return new ActionTameworkConfirmLanding(this);
    }

    @Override
    public String getShortDescription() {
        return "Activates Walk after a flying NPC makes ground contact.";
    }

    @Override
    public String getLongDescription() {
        return "Confirms physical touchdown from the Fly controller and safely activates the NPC's Walk controller.";
    }
}
