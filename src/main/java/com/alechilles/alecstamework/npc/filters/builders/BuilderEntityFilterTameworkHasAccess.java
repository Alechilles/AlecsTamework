package com.alechilles.alecstamework.npc.filters.builders;

import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.npc.filters.EntityFilterTameworkHasAccess;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasAccess;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasAccess.MinLevel;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.ComponentContext;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumHolder;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderEntityFilterBase;
import javax.annotation.Nonnull;

/** Builder for EntityFilterTameworkHasAccess. */
public final class BuilderEntityFilterTameworkHasAccess extends BuilderEntityFilterBase {
    public static final String BUILDER_ID = EntityFilterTameworkHasAccess.TYPE;

    private final EnumHolder<MinLevel> minLevel = new EnumHolder<>();

    @Nonnull
    @Override
    public IEntityFilter build(@Nonnull BuilderSupport support) {
        return new EntityFilterTameworkHasAccess(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Matches when the candidate entity is a player with access to the NPC.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks whether the entity being evaluated is the NPC's owner or a player who holds shared "
                + "access to it at MinLevel or higher. Use TameworkIsOwner when only the owner may match.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<IEntityFilter> readConfig(@Nonnull JsonElement data) {
        this.getEnum(data, BuilderSensorTameworkHasAccess.MIN_LEVEL_KEY, this.minLevel, MinLevel.class,
                MinLevel.CARETAKER, BuilderDescriptorState.Stable,
                BuilderSensorTameworkHasAccess.MIN_LEVEL_DESCRIPTION, null);
        this.requireContext(InstructionType.Any, ComponentContext.NotSelfEntitySensor);
        return this;
    }

    /** The configured level, read once when the filter is built. */
    @Nonnull
    public AccessLevel getMinLevel(@Nonnull BuilderSupport support) {
        return this.minLevel.get(support.getExecutionContext()).level();
    }
}
