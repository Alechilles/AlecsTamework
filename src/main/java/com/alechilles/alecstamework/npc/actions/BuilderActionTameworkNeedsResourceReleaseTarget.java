package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkNeedsResourceReleaseTarget.
 */
public final class BuilderActionTameworkNeedsResourceReleaseTarget extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceReleaseTarget";

    private final StringHolder resourceType = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkNeedsResourceReleaseTarget readConfig(JsonElement element) {
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
                "Resource target kind to release: Water or FoodContainer.",
                null
        );
        return this;
    }

    public String getResourceType(BuilderSupport support) {
        return resourceType.get(support.getExecutionContext());
    }

    public ActionTameworkNeedsResourceReleaseTarget build(BuilderSupport support) {
        return new ActionTameworkNeedsResourceReleaseTarget(this, support);
    }

    public String getShortDescription() {
        return "Releases a needs resource target reservation.";
    }

    public String getLongDescription() {
        return "Clears Tamework's internal needs resource target reservation without marking the target as failed.";
    }
}
