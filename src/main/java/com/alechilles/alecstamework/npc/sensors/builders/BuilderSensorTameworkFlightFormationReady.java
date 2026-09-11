package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkFlightFormationReady;
import com.alechilles.alecstamework.npc.movement.BuilderBodyMotionTameworkFlightFormation.Formation;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumHolder;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/** Builder for the flight formation eligibility sensor. */
public final class BuilderSensorTameworkFlightFormationReady extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkFlightFormationReady";
    private final EnumHolder<Formation> formation = new EnumHolder<>();

    @Nonnull
    @Override
    public BuilderSensorTameworkFlightFormationReady readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getEnum(data, "Formation", formation, Formation.class, Formation.NONE,
                BuilderDescriptorState.WorkInProgress,
                "Flight formation: None, Loose, or Chevron.", null);
        return this;
    }

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(BuilderSupport support) {
        return new SensorTameworkFlightFormationReady(this,
                formation.get(support.getExecutionContext()) != Formation.NONE);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True for an autonomous TameworkFormationFly follower with a flying NPC flock leader.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return getShortDescription();
    }
}
