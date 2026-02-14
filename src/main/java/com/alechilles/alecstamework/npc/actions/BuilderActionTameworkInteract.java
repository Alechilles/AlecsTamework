package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkInteract.
 */
public final class BuilderActionTameworkInteract extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkInteract";

    private final StringHolder configId = new StringHolder();
    private final StringArrayHolder lovedItems = new StringArrayHolder();
    private final BooleanHolder isMountable = new BooleanHolder();
    private final BooleanHolder isHarvestable = new BooleanHolder();
    private final StringHolder harvestInteractionContext = new StringHolder();

    private boolean hasConfigId;
    private boolean hasLovedItems;
    private boolean hasIsMountable;
    private boolean hasIsHarvestable;
    private boolean hasHarvestContext;

    @Override
    public BuilderActionTameworkInteract readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        hasConfigId = getString(
                element,
                "ConfigId",
                configId,
                null,
                null,
                BuilderDescriptorState.Stable,
                "Optional interaction config id override.",
                "If set, this config id is used instead of RoleIds matching."
        );
        hasLovedItems = getStringArray(
                element,
                "LovedItems",
                lovedItems,
                null,
                0,
                Integer.MAX_VALUE,
                null,
                BuilderDescriptorState.Stable,
                "Optional loved items override for interactions.",
                "If set, this list is used instead of role parameters."
        );
        hasIsMountable = getBoolean(
                element,
                "IsMountable",
                isMountable,
                false,
                BuilderDescriptorState.Stable,
                "Optional mountable override for interactions.",
                "If set, this value is used instead of role parameters."
        );
        hasIsHarvestable = getBoolean(
                element,
                "IsHarvestable",
                isHarvestable,
                false,
                BuilderDescriptorState.Stable,
                "Optional harvestable override for interactions.",
                "If set, this value is used instead of role parameters."
        );
        hasHarvestContext = getString(
                element,
                "HarvestInteractionContext",
                harvestInteractionContext,
                null,
                null,
                BuilderDescriptorState.Stable,
                "Optional harvest interaction context override.",
                "If set, this value is used instead of role parameters."
        );
        return this;
    }

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public boolean hasConfigIdOverride() {
        return hasConfigId;
    }

    public String getConfigId(BuilderSupport support) {
        return configId.get(support.getExecutionContext());
    }

    public boolean hasLovedItemsOverride() {
        return hasLovedItems;
    }

    public String[] getLovedItems(BuilderSupport support) {
        return lovedItems.get(support.getExecutionContext());
    }

    public boolean hasIsMountableOverride() {
        return hasIsMountable;
    }

    public boolean getIsMountable(BuilderSupport support) {
        return isMountable.get(support.getExecutionContext());
    }

    public boolean hasIsHarvestableOverride() {
        return hasIsHarvestable;
    }

    public boolean getIsHarvestable(BuilderSupport support) {
        return isHarvestable.get(support.getExecutionContext());
    }

    public boolean hasHarvestInteractionContextOverride() {
        return hasHarvestContext;
    }

    public String getHarvestInteractionContext(BuilderSupport support) {
        return harvestInteractionContext.get(support.getExecutionContext());
    }

    public ActionTameworkInteract build(BuilderSupport support) {
        return new ActionTameworkInteract(this, support);
    }

    public String getShortDescription() {
        return "Runs the optimized Tamework interaction pipeline.";
    }

    public String getLongDescription() {
        return "Custom action that resolves a TwInteractionConfig and executes the first valid interaction.";
    }
}
