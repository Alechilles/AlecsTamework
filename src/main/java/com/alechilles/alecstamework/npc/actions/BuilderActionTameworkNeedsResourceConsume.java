package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringArrayNoEmptyStringsValidator;

/**
 * Builder for ActionTameworkNeedsResourceConsume.
 */
public final class BuilderActionTameworkNeedsResourceConsume extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceConsume";

    private final StringHolder resourceType = new StringHolder();
    private final StringArrayHolder foodItemIds = new StringArrayHolder();
    private final BooleanHolder releaseTarget = new BooleanHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkNeedsResourceConsume readConfig(JsonElement element) {
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
                "Resource consume mode: Auto, Water, or FoodContainer.",
                null
        );
        getStringArray(
                element,
                "FoodItemIDs",
                foodItemIds,
                null,
                0,
                Integer.MAX_VALUE,
                StringArrayNoEmptyStringsValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional allowed food item ids for container consumption.",
                "If empty, role FoodItemIDs and then TwNeedsConfig fallback list is used."
        );
        getBoolean(
                element,
                "ReleaseTarget",
                releaseTarget,
                true,
                BuilderDescriptorState.Stable,
                "Whether this consume attempt should release the active resource reservation.",
                "Set false when role assets will repeat consumption before final completion."
        );
        return this;
    }

    public String getResourceType(BuilderSupport support) {
        return resourceType.get(support.getExecutionContext());
    }

    public String[] getFoodItemIds(BuilderSupport support) {
        return foodItemIds.get(support.getExecutionContext());
    }

    public boolean getReleaseTarget(BuilderSupport support) {
        return releaseTarget.get(support.getExecutionContext());
    }

    public ActionTameworkNeedsResourceConsume build(BuilderSupport support) {
        return new ActionTameworkNeedsResourceConsume(this, support);
    }

    public String getShortDescription() {
        return "Consumes nearby needs resources (food and/or water).";
    }

    public String getLongDescription() {
        return "Custom action that attempts nearby container-food and water consume for needs refill.";
    }
}
