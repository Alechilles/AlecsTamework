package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringArrayNoEmptyStringsValidator;

/**
 * Builder for ActionTameworkDebugCombatSnapshot.
 */
public final class BuilderActionTameworkDebugCombatSnapshot extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkDebugCombatSnapshot";

    private final StringHolder message = new StringHolder();
    private final StringArrayHolder targetSlots = new StringArrayHolder();
    private final StringArrayHolder numberParams = new StringArrayHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkDebugCombatSnapshot readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getString(
                element,
                "Message",
                message,
                "",
                null,
                BuilderDescriptorState.Stable,
                "Optional debug label to include in the combat snapshot log line.",
                null
        );
        getStringArray(
                element,
                "TargetSlots",
                targetSlots,
                null,
                0,
                Integer.MAX_VALUE,
                StringArrayNoEmptyStringsValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional marked-entity target slots to include in the snapshot.",
                "Defaults to LockedTarget, CAETargetSlot, and MasterTarget."
        );
        getStringArray(
                element,
                "NumberParams",
                numberParams,
                null,
                0,
                Integer.MAX_VALUE,
                StringArrayNoEmptyStringsValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional numeric role params to include in the snapshot.",
                "Defaults include AttackDistance and combat spacing params."
        );
        return this;
    }

    public String getMessage(BuilderSupport support) {
        return message.get(support.getExecutionContext());
    }

    public String[] getTargetSlots(BuilderSupport support) {
        return targetSlots.get(support.getExecutionContext());
    }

    public String[] getNumberParams(BuilderSupport support) {
        return numberParams.get(support.getExecutionContext());
    }

    public ActionTameworkDebugCombatSnapshot build(BuilderSupport support) {
        return new ActionTameworkDebugCombatSnapshot(this, support);
    }

    public String getShortDescription() {
        return "Writes a structured combat-state snapshot to the server log.";
    }

    public String getLongDescription() {
        return "Custom action that logs role state, target slots, and combat params for CAE diagnostics.";
    }
}
