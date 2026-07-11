package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkSetOwner.
 */
public final class BuilderActionTameworkSetOwner extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkSetOwner";
    private boolean tameOnApplied;
    private boolean consumeHeldItemOnApplied;
    private final StringHolder stateOnApplied = new StringHolder();
    private final StringHolder particleSystemOnApplied = new StringHolder();
    private final StringHolder soundEventParamOnApplied = new StringHolder();

    @Override
    public BuilderActionTameworkSetOwner readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getBoolean(
                element,
                "TameOnApplied",
                value -> tameOnApplied = value,
                false,
                BuilderDescriptorState.Stable,
                "Apply the complete tame bundle only after ownership admission succeeds.",
                null
        );
        getBoolean(
                element,
                "ConsumeHeldItemOnApplied",
                value -> consumeHeldItemOnApplied = value,
                false,
                BuilderDescriptorState.Stable,
                "Consume one matching held item only after ownership admission succeeds.",
                null
        );
        getString(
                element,
                "StateOnApplied",
                stateOnApplied,
                null,
                null,
                BuilderDescriptorState.Stable,
                "Optional State.SubState applied after ownership succeeds.",
                null
        );
        getString(
                element,
                "ParticleSystemOnApplied",
                particleSystemOnApplied,
                null,
                null,
                BuilderDescriptorState.Stable,
                "Optional particle system played after ownership succeeds.",
                null
        );
        getString(
                element,
                "SoundEventParamOnApplied",
                soundEventParamOnApplied,
                null,
                null,
                BuilderDescriptorState.Stable,
                "Optional role string parameter containing the success sound event id.",
                null
        );
        return this;
    }

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    public ActionTameworkSetOwner build(BuilderSupport support) {
        return new ActionTameworkSetOwner(this, support);
    }

    boolean isTameOnApplied() {
        return tameOnApplied;
    }

    boolean isConsumeHeldItemOnApplied() {
        return consumeHeldItemOnApplied;
    }

    String getStateOnApplied(BuilderSupport support) {
        return stateOnApplied.get(support.getExecutionContext());
    }

    String getParticleSystemOnApplied(BuilderSupport support) {
        return particleSystemOnApplied.get(support.getExecutionContext());
    }

    String getSoundEventParamOnApplied(BuilderSupport support) {
        return soundEventParamOnApplied.get(support.getExecutionContext());
    }

    public String getShortDescription() {
        return "Sets the NPC owner to the interacting player.";
    }

    public String getLongDescription() {
        return "Custom action that sets Tamework owner data and can defer tame success effects "
                + "until admission commits.";
    }
}
