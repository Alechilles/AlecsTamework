package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkIsTamed;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

public final class BuilderSensorTameworkIsTamed extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkIsTamed";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    public Sensor build(BuilderSupport support) {
        return new SensorTameworkIsTamed(this, support);
    }

    @Nonnull
    public String getShortDescription() {
        return "True when the NPC has the Tamework tamed flag set.";
    }

    @Nonnull
    public String getLongDescription() {
        return "Checks whether the NPC has its Tamework tamed flag set to true.";
    }
}
