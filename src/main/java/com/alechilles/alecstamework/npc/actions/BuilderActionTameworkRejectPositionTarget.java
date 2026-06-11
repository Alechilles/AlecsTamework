package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkRejectPositionTarget.
 */
public final class BuilderActionTameworkRejectPositionTarget extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkRejectPositionTarget";

    private final StringHolder label = new StringHolder();
    private final DoubleHolder suppressSeconds = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkRejectPositionTarget readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getString(
                element,
                "Label",
                label,
                "Generic",
                null,
                BuilderDescriptorState.Stable,
                "Target cache label shared with the sensor that produced the failed position.",
                null
        );
        getDouble(
                element,
                "SuppressSeconds",
                suppressSeconds,
                30.0,
                null,
                BuilderDescriptorState.Stable,
                "How long this NPC should skip the failed position target.",
                null
        );
        return this;
    }

    public String getLabel(BuilderSupport support) {
        return label.get(support.getExecutionContext());
    }

    public double getSuppressSeconds(BuilderSupport support) {
        return suppressSeconds.get(support.getExecutionContext());
    }

    public ActionTameworkRejectPositionTarget build(BuilderSupport support) {
        return new ActionTameworkRejectPositionTarget(this, support);
    }

    public String getShortDescription() {
        return "Suppresses a failed position target for this NPC.";
    }

    public String getLongDescription() {
        return "Records the current position target as temporarily rejected so later scans can skip it.";
    }
}
