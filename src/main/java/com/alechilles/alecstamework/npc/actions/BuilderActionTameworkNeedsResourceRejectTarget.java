package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkNeedsResourceRejectTarget.
 */
public final class BuilderActionTameworkNeedsResourceRejectTarget extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceRejectTarget";

    private final StringHolder resourceType = new StringHolder();
    private final DoubleHolder suppressSeconds = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkNeedsResourceRejectTarget readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getString(
                element,
                "ResourceType",
                resourceType,
                "Auto",
                null,
                BuilderDescriptorState.Stable,
                "Resource target kind to suppress: Water or FoodContainer.",
                null
        );
        getDouble(
                element,
                "SuppressSeconds",
                suppressSeconds,
                30.0,
                null,
                BuilderDescriptorState.Stable,
                "How long this NPC should skip the failed needs seek target.",
                null
        );
        return this;
    }

    public String getResourceType(BuilderSupport support) {
        return resourceType.get(support.getExecutionContext());
    }

    public double getSuppressSeconds(BuilderSupport support) {
        return suppressSeconds.get(support.getExecutionContext());
    }

    public ActionTameworkNeedsResourceRejectTarget build(BuilderSupport support) {
        return new ActionTameworkNeedsResourceRejectTarget(this, support);
    }

    public String getShortDescription() {
        return "Suppresses a failed needs resource target for this NPC.";
    }

    public String getLongDescription() {
        return "Records the current needs seek target as temporarily rejected so later scans can skip it.";
    }
}
