package com.alechilles.alecstamework.npc.filters.builders;

import com.alechilles.alecstamework.npc.filters.EntityFilterTameworkAttackedTargetSlotRecently;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.ComponentContext;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNullOrNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderEntityFilterBase;
import javax.annotation.Nonnull;

/**
 * Builder for EntityFilterTameworkAttackedTargetSlotRecently.
 */
public final class BuilderEntityFilterTameworkAttackedTargetSlotRecently extends BuilderEntityFilterBase {
    public static final String BUILDER_ID = EntityFilterTameworkAttackedTargetSlotRecently.TYPE;

    private final StringHolder sourceTargetSlot = new StringHolder();
    private final DoubleHolder maxAgeSeconds = new DoubleHolder();
    private final BooleanHolder useSelfWhenSourceMissing = new BooleanHolder();
    private final BooleanHolder includeSelfAsSource = new BooleanHolder();

    @Nonnull
    @Override
    public IEntityFilter build(@Nonnull BuilderSupport support) {
        return new EntityFilterTameworkAttackedTargetSlotRecently(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Matches when the candidate recently attacked the entity in SourceTargetSlot.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks recent damage-memory records and passes when the candidate has attacked the configured source target.";
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
                "Target slot used as the attacked target reference.",
                null
        );
        this.getDouble(
                data,
                "MaxAgeSeconds",
                this.maxAgeSeconds,
                30.0,
                null,
                BuilderDescriptorState.Stable,
                "Maximum age of a recorded hit in seconds. Set < 0 to disable age checks.",
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
        this.getBoolean(
                data,
                "IncludeSelfAsSource",
                this.includeSelfAsSource,
                false,
                BuilderDescriptorState.Stable,
                "Also match when the candidate recently attacked this NPC.",
                null
        );
        this.requireContext(InstructionType.Any, ComponentContext.NotSelfEntitySensor);
        return this;
    }

    public int getSourceTargetSlot(@Nonnull BuilderSupport support) {
        return support.getTargetSlot(this.sourceTargetSlot.get(support.getExecutionContext()));
    }

    public double getMaxAgeSeconds(@Nonnull BuilderSupport support) {
        return this.maxAgeSeconds.get(support.getExecutionContext());
    }

    public boolean useSelfWhenSourceMissing(@Nonnull BuilderSupport support) {
        return this.useSelfWhenSourceMissing.get(support.getExecutionContext());
    }

    public boolean includeSelfAsSource(@Nonnull BuilderSupport support) {
        return this.includeSelfAsSource.get(support.getExecutionContext());
    }
}
