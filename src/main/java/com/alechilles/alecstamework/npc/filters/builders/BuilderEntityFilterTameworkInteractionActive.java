package com.alechilles.alecstamework.npc.filters.builders;

import com.alechilles.alecstamework.npc.filters.EntityFilterTameworkInteractionActive;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.ComponentContext;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderEntityFilterBase;
import javax.annotation.Nonnull;

/** Selects an entity while it is running a specified root interaction. */
public final class BuilderEntityFilterTameworkInteractionActive extends BuilderEntityFilterBase {
    public static final String BUILDER_ID = "TameworkInteractionActive";
    private final StringHolder rootInteractionId = new StringHolder();

    @Nonnull
    @Override
    public IEntityFilter build(@Nonnull BuilderSupport support) {
        return new EntityFilterTameworkInteractionActive(this, support);
    }

    @Nonnull
    @Override
    public BuilderEntityFilterTameworkInteractionActive readConfig(@Nonnull JsonElement data) {
        requireContext(InstructionType.Any, ComponentContext.NotSelfEntitySensor);
        requireString(data, "RootInteractionId", rootInteractionId, StringNotEmptyValidator.get(),
                BuilderDescriptorState.WorkInProgress, "Initial root interaction ID that must still be active.", null);
        return this;
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Matches entities running the specified root interaction.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks active server interaction chains. Finished or cancelled chains do not match.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.WorkInProgress;
    }

    public String getRootInteractionId(@Nonnull BuilderSupport support) {
        return rootInteractionId.get(support.getExecutionContext());
    }
}
