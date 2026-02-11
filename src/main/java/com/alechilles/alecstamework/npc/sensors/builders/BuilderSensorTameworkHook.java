package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkHook;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkHook.
 */
public final class BuilderSensorTameworkHook extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkHook";

    private final StringHolder hookId = new StringHolder();
    private final BooleanHolder consume = new BooleanHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkHook(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when the NPC has a matching Tamework hook signal.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks whether the NPC's Tamework hook matches the provided HookId. Optionally consumes the hook on match.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkHook readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "HookId",
                this.hookId,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Hook id to match.",
                null
        );
        this.getBoolean(
                data,
                "Consume",
                this.consume,
                false,
                BuilderDescriptorState.Stable,
                "Clear the hook after a successful match.",
                null
        );
        return this;
    }

    public String getHookId(@Nonnull BuilderSupport support) {
        return this.hookId.get(support.getExecutionContext());
    }

    public boolean isConsume(@Nonnull BuilderSupport support) {
        return this.consume.get(support.getExecutionContext());
    }
}
