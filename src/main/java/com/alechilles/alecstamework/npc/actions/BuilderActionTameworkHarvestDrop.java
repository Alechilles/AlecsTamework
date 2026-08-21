package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.corecomponents.items.builders.BuilderActionDropItem;
import com.hypixel.hytale.server.npc.instructions.Action;
import javax.annotation.Nonnull;

/**
 * Builder for {@link ActionTameworkHarvestDrop}.
 */
public final class BuilderActionTameworkHarvestDrop extends BuilderActionDropItem {
    public static final String BUILDER_ID = "TameworkHarvestDrop";
    private final BooleanHolder awardXp = new BooleanHolder();

    @Override
    public BuilderActionTameworkHarvestDrop readConfig(JsonElement element) {
        super.readConfig(element);
        getBoolean(
                element,
                "AwardXp",
                awardXp,
                true,
                BuilderDescriptorState.Stable,
                "Award companion harvest XP after items are dropped.",
                "Disable this for passive production that is not a manual harvest."
        );
        return this;
    }

    public boolean getAwardXp(@Nonnull BuilderSupport support) {
        return awardXp.get(support.getExecutionContext());
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport support) {
        return new ActionTameworkHarvestDrop(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Drop harvest items with trait-aware bonus roll support.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "DropItem-compatible harvest action that can emit a second drop pass using the Bounty trait chance.";
    }
}
