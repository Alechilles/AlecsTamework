package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkHasOwner;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkHasOwner.
 */
public final class BuilderSensorTameworkHasOwner extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkHasOwner";

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    public Sensor build(BuilderSupport support) {
        return new SensorTameworkHasOwner(this, support);
    }

    @Nonnull
    public String getShortDescription() {
        return "True when the NPC has a non-null owner.";
    }

    @Nonnull
    public String getLongDescription() {
        return "Checks whether the NPC has an owner stored via component or owner store.";
    }
}
