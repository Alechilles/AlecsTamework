package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.items.builders.BuilderActionDropItem;
import com.hypixel.hytale.server.npc.instructions.Action;
import javax.annotation.Nonnull;

/**
 * Builder for {@link ActionTameworkHarvestDrop}.
 */
public final class BuilderActionTameworkHarvestDrop extends BuilderActionDropItem {
    public static final String BUILDER_ID = "TameworkHarvestDrop";

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
