package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkIsOwner;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

public final class BuilderSensorTameworkIsOwner extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkIsOwner";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    public Sensor build(BuilderSupport support) {
        return new SensorTameworkIsOwner(this, support);
    }

    @Nonnull
    public String getShortDescription() {
        return "True when the current interaction player is the NPC's owner.";
    }

    @Nonnull
    public String getLongDescription() {
        return "Checks whether the interacting player matches the NPC owner (component or owner store).";
    }
}
