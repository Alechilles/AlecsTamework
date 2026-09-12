package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;

/** Cancels an ambient scene before an ordinary behavior or role takes over. */
public final class BuilderActionTameworkAmbientHerdCancel extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkAmbientHerdCancel";
    @Override public String getBuilderId() { return BUILDER_ID; }
    @Override public BuilderActionTameworkAmbientHerdCancel readConfig(JsonElement data) { return this; }
    @Override public ActionTameworkAmbientHerdCancel build(BuilderSupport support) {
        return new ActionTameworkAmbientHerdCancel(this);
    }
    @Override public String getShortDescription() { return "Releases this NPC's ambient herd activity."; }
    @Override public String getLongDescription() { return getShortDescription(); }
}
