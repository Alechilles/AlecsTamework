package com.alechilles.alecstamework.npc.filters.builders;

import com.alechilles.alecstamework.npc.filters.EntityFilterTameworkAttitudeFromTargetSlot;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.ComponentContext;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumSetHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNullOrNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderEntityFilterBase;
import java.util.EnumSet;
import javax.annotation.Nonnull;

/**
 * Builder for EntityFilterTameworkAttitudeFromTargetSlot.
 */
public final class BuilderEntityFilterTameworkAttitudeFromTargetSlot extends BuilderEntityFilterBase {
    public static final String BUILDER_ID = EntityFilterTameworkAttitudeFromTargetSlot.TYPE;

    private final StringHolder sourceTargetSlot = new StringHolder();
    private final EnumSetHolder<Attitude> attitudes = new EnumSetHolder<>();
    private final BooleanHolder useSelfWhenSourceMissing = new BooleanHolder();

    @Nonnull
    @Override
    public IEntityFilter build(@Nonnull BuilderSupport support) {
        return new EntityFilterTameworkAttitudeFromTargetSlot(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Matches when the source target slot is hostile to the candidate.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks the attitude from a marked target slot (for example MasterTarget) toward the candidate target.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<IEntityFilter> readConfig(@Nonnull JsonElement data) {
        this.getString(
                data,
                "SourceTargetSlot",
                this.sourceTargetSlot,
                "MasterTarget",
                StringNullOrNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Target slot used as the source attitude entity.",
                null
        );
        this.requireEnumSet(
                data,
                "Attitudes",
                this.attitudes,
                Attitude.class,
                BuilderDescriptorState.Stable,
                "Attitudes from the source entity to the candidate that should pass.",
                null
        );
        this.getBoolean(
                data,
                "UseSelfWhenSourceMissing",
                this.useSelfWhenSourceMissing,
                false,
                BuilderDescriptorState.Stable,
                "Fallback to the NPC itself when SourceTargetSlot is unset.",
                null
        );
        this.requireContext(InstructionType.Any, ComponentContext.NotSelfEntitySensor);
        return this;
    }

    public int getSourceTargetSlot(@Nonnull BuilderSupport support) {
        return support.getTargetSlot(this.sourceTargetSlot.get(support.getExecutionContext()));
    }

    public EnumSet<Attitude> getAttitudes(@Nonnull BuilderSupport support) {
        return this.attitudes.get(support.getExecutionContext());
    }

    public boolean useSelfWhenSourceMissing(@Nonnull BuilderSupport support) {
        return this.useSelfWhenSourceMissing.get(support.getExecutionContext());
    }
}
