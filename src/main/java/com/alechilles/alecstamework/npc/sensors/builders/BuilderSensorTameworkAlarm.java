package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkAlarm;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for {@link SensorTameworkAlarm}.
 */
public final class BuilderSensorTameworkAlarm extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkAlarm";

    private final StringHolder name = new StringHolder();
    private final StringHolder state = new StringHolder();
    private final BooleanHolder clear = new BooleanHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkAlarm(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when a named Tamework alarm is in the configured state.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Mirrors the base Alarm sensor syntax for alarms stored in TameworkAlarmComponent.";
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkAlarm readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "Name",
                this.name,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Alarm name to inspect.",
                null
        );
        this.getString(
                data,
                "State",
                this.state,
                "Passed",
                null,
                BuilderDescriptorState.Stable,
                "Alarm state to match: Unset, Active, Passed, Ready, or Set.",
                null
        );
        this.getBoolean(
                data,
                "Clear",
                this.clear,
                false,
                BuilderDescriptorState.Stable,
                "Clear the alarm after a successful match.",
                null
        );
        return this;
    }

    @Nonnull
    public String getName(@Nonnull BuilderSupport support) {
        return this.name.get(support.getExecutionContext());
    }

    @Nonnull
    public String getState(@Nonnull BuilderSupport support) {
        String configured = this.state.get(support.getExecutionContext());
        return configured == null || configured.isBlank() ? "Passed" : configured;
    }

    public boolean isClear(@Nonnull BuilderSupport support) {
        return this.clear.get(support.getExecutionContext());
    }
}
