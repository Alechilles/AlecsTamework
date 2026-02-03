package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringArrayNoEmptyStringsValidator;

public final class BuilderActionTameworkDenyCaptureUntamed extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkDenyCaptureUntamed";

    private final StringArrayHolder foodItemIds = new StringArrayHolder();

    @Override
    public BuilderActionTameworkDenyCaptureUntamed readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getStringArray(
                element,
                "FoodItemIDs",
                this.foodItemIds,
                null,
                0,
                Integer.MAX_VALUE,
                StringArrayNoEmptyStringsValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional list of item IDs to include in the untamed message.",
                "If provided, will list items that can tame this NPC."
        );
        return this;
    }

    public String[] getFoodItemIds(BuilderSupport support) {
        return this.foodItemIds.get(support.getExecutionContext());
    }

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkDenyCaptureUntamed build(BuilderSupport support) {
        return new ActionTameworkDenyCaptureUntamed(this, support);
    }

    public String getShortDescription() {
        return "Sends a message when capture is attempted before taming.";
    }

    public String getLongDescription() {
        return "Custom action that warns the player they must tame a pet before capturing it.";
    }
}
