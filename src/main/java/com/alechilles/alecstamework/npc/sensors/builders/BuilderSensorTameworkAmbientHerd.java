package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.ambient.AmbientHerdCoordinator.SensorPhase;
import com.alechilles.alecstamework.npc.sensors.SensorTameworkAmbientHerd;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.Feature;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumHolder;
import com.hypixel.hytale.server.npc.instructions.Sensor;

/** Passive builder; constructing a sensor does not install the ambient runtime. */
public final class BuilderSensorTameworkAmbientHerd extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkAmbientHerd";
    private final BooleanHolder canLead = new BooleanHolder();
    private final EnumHolder<SensorPhase> phase = new EnumHolder<>();

    @Override
    public String getBuilderId() { return BUILDER_ID; }

    @Override
    public BuilderSensorTameworkAmbientHerd readConfig(JsonElement data) {
        super.readConfig(data);
        getBoolean(data, "CanLead", canLead, false, BuilderDescriptorState.WorkInProgress,
                "Allows an adult flock leader to initiate a journey.", null);
        getEnum(data, "Phase", phase, SensorPhase.class, SensorPhase.READY,
                BuilderDescriptorState.WorkInProgress, "Ambient phase to match.", null);
        provideFeature(Feature.Position);
        return this;
    }

    @Override
    public Sensor build(BuilderSupport support) {
        return new SensorTameworkAmbientHerd(this, isEnabled(support.getExecutionContext()),
                canLead.get(support.getExecutionContext()), phase.get(support.getExecutionContext()));
    }

    @Override
    public String getShortDescription() { return "Offers or follows a bounded wild herd journey."; }

    @Override
    public String getLongDescription() { return getShortDescription(); }
}
