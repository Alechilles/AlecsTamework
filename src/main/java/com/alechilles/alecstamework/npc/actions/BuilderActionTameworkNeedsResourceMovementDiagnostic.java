package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkNeedsResourceMovementDiagnostic.
 */
public final class BuilderActionTameworkNeedsResourceMovementDiagnostic extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceMovementDiagnostic";

    private final StringHolder resourceType = new StringHolder();
    private final StringHolder stage = new StringHolder();
    private final StringHolder detail = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkNeedsResourceMovementDiagnostic readConfig(JsonElement element) {
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
                "Resource target kind being moved toward: Water or FoodContainer.",
                null
        );
        getString(
                element,
                "Stage",
                stage,
                "unknown",
                null,
                BuilderDescriptorState.Stable,
                "Stable movement diagnostic stage.",
                null
        );
        getString(
                element,
                "Detail",
                detail,
                "unknown",
                null,
                BuilderDescriptorState.Stable,
                "Stable movement diagnostic detail.",
                null
        );
        return this;
    }

    public String getResourceType(BuilderSupport support) {
        return resourceType.get(support.getExecutionContext());
    }

    public String getStage(BuilderSupport support) {
        return stage.get(support.getExecutionContext());
    }

    public String getDetail(BuilderSupport support) {
        return detail.get(support.getExecutionContext());
    }

    public ActionTameworkNeedsResourceMovementDiagnostic build(BuilderSupport support) {
        return new ActionTameworkNeedsResourceMovementDiagnostic(this, support);
    }

    public String getShortDescription() {
        return "Logs needs seek movement diagnostics.";
    }

    public String getLongDescription() {
        return "Custom action that emits gated movement-phase diagnostics for needs seek resource movement.";
    }
}
