package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkHasTalent;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/** Builder for SensorTameworkHasTalent. */
public final class BuilderSensorTameworkHasTalent extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkHasTalent";

    private final StringHolder talentId = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkHasTalent(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when this NPC has purchased the configured talent.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks this NPC's purchased Tamework talents.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkHasTalent readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "TalentId",
                this.talentId,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Purchased talent id to check.",
                null
        );
        return this;
    }

    @Nonnull
    public String getTalentId(@Nonnull BuilderSupport support) {
        return this.talentId.get(support.getExecutionContext());
    }
}
