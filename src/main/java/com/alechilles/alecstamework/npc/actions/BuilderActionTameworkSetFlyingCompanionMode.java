package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;

/**
 * Builder for ActionTameworkSetFlyingCompanionMode.
 */
public final class BuilderActionTameworkSetFlyingCompanionMode extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkSetFlyingCompanionMode";

    private final StringHolder mode = new StringHolder();
    private final StringHolder landingState = new StringHolder();
    private final StringHolder groundedState = new StringHolder();
    private final DoubleHolder descendStep = new DoubleHolder();
    private final DoubleHolder reissueDelayMs = new DoubleHolder();
    private final DoubleHolder groundedStableTicks = new DoubleHolder();
    private final DoubleHolder verticalMovementEpsilon = new DoubleHolder();
    private final StringHolder landingTargetSlot = new StringHolder();
    private final BooleanHolder landingUseInfoProviderPosition = new BooleanHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkSetFlyingCompanionMode readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        requireString(
                element,
                "Mode",
                mode,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Flying companion mode to apply: Follow or Hold.",
                null
        );
        getString(
                element,
                "LandingState",
                landingState,
                "Hold",
                null,
                BuilderDescriptorState.Stable,
                "State to keep active while the companion is still descending.",
                null
        );
        getString(
                element,
                "GroundedState",
                groundedState,
                "HoldGrounded",
                null,
                BuilderDescriptorState.Stable,
                "State to enter once the companion is considered grounded.",
                null
        );
        getDouble(
                element,
                "DescendStep",
                descendStep,
                4.0,
                null,
                BuilderDescriptorState.Stable,
                "Vertical distance to request per settle command while holding.",
                null
        );
        getDouble(
                element,
                "ReissueDelayMs",
                reissueDelayMs,
                300.0,
                null,
                BuilderDescriptorState.Stable,
                "Delay between repeated descend commands while settling.",
                null
        );
        getDouble(
                element,
                "GroundedStableTicks",
                groundedStableTicks,
                3.0,
                null,
                BuilderDescriptorState.Stable,
                "How many low-movement sweeps count as grounded.",
                null
        );
        getDouble(
                element,
                "VerticalMovementEpsilon",
                verticalMovementEpsilon,
                0.15,
                null,
                BuilderDescriptorState.Stable,
                "Maximum Y movement treated as stable during landing detection.",
                null
        );
        getString(
                element,
                "LandingTargetSlot",
                landingTargetSlot,
                "",
                null,
                BuilderDescriptorState.Stable,
                "Optional marked-entity target slot used as the safe-landing search origin.",
                "Use for player-relative hold landings, for example MasterTarget."
        );
        getBoolean(
                element,
                "LandingUseInfoProviderPosition",
                landingUseInfoProviderPosition,
                false,
                BuilderDescriptorState.Stable,
                "When true, capture the current sensor position as the safe-landing search origin.",
                "Use for resource-relative landings after ReadPosition has provided a block or entity position."
        );
        return this;
    }

    public String getMode(BuilderSupport support) {
        return mode.get(support.getExecutionContext());
    }

    public String getLandingState(BuilderSupport support) {
        return landingState.get(support.getExecutionContext());
    }

    public String getGroundedState(BuilderSupport support) {
        return groundedState.get(support.getExecutionContext());
    }

    public double getDescendStep(BuilderSupport support) {
        return descendStep.get(support.getExecutionContext());
    }

    public long getReissueDelayMs(BuilderSupport support) {
        return Math.round(reissueDelayMs.get(support.getExecutionContext()));
    }

    public long getGroundedStableTicks(BuilderSupport support) {
        return Math.round(groundedStableTicks.get(support.getExecutionContext()));
    }

    public double getVerticalMovementEpsilon(BuilderSupport support) {
        return verticalMovementEpsilon.get(support.getExecutionContext());
    }

    public int getLandingTargetSlotIndex(BuilderSupport support) {
        String slot = landingTargetSlot.get(support.getExecutionContext());
        if (slot == null || slot.isBlank()) {
            return -1;
        }
        return support.getTargetSlot(slot);
    }

    public boolean getLandingUseInfoProviderPosition(BuilderSupport support) {
        return landingUseInfoProviderPosition.get(support.getExecutionContext());
    }

    public ActionTameworkSetFlyingCompanionMode build(BuilderSupport support) {
        return new ActionTameworkSetFlyingCompanionMode(this, support);
    }

    public String getShortDescription() {
        return "Stores high-level flying companion mode for Tamework-managed settling.";
    }

    public String getLongDescription() {
        return "Custom action that switches a flying companion between Follow and Hold behavior and configures landing settle thresholds and targets.";
    }
}
