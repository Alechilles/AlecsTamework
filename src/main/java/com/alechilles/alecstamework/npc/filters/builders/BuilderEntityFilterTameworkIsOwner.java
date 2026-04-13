package com.alechilles.alecstamework.npc.filters.builders;

import com.alechilles.alecstamework.npc.filters.EntityFilterTameworkIsOwner;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.ComponentContext;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderEntityFilterBase;
import javax.annotation.Nonnull;

/** Builder for EntityFilterTameworkIsOwner. */
public final class BuilderEntityFilterTameworkIsOwner extends BuilderEntityFilterBase {
    public static final String BUILDER_ID = EntityFilterTameworkIsOwner.TYPE;

    @Nonnull
    @Override
    public IEntityFilter build(@Nonnull BuilderSupport support) {
        return new EntityFilterTameworkIsOwner(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Matches when the candidate entity is the NPC's owner.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks whether the entity being evaluated resolves to the NPC owner's player UUID.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<IEntityFilter> readConfig(@Nonnull JsonElement data) {
        this.requireContext(InstructionType.Any, ComponentContext.NotSelfEntitySensor);
        return this;
    }
}
