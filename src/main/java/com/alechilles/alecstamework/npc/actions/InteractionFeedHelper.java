package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.npc.role.Role;

/** Resolves feed items/heal values and applies item consumption. */
final class InteractionFeedHelper {
    private final InteractionParamAccess paramAccess;

    // Builds a feed helper tied to the shared param access.
    InteractionFeedHelper(InteractionParamAccess paramAccess) {
        this.paramAccess = paramAccess;
    }

    // Resolves the feed item set for an interaction using params and defaults.
    InteractionFeedItems resolveFeedItems(FeedInteraction interaction, Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveFeedItems(interaction, role, ctx);
    }

    // Resolves the heal amount for feeding based on held item overrides.
    double resolveFeedHeal(FeedInteraction feed, Role role, InteractionContextSnapshot ctx) {
        if (feed == null) {
            return 0;
        }
        Double baseHeal = feed.getHeal();
        String heldItem = ctx != null ? ctx.activeItemId : null;
        if (heldItem != null && !heldItem.isBlank()) {
            InteractionFeedItems resolved = resolveFeedItems(feed, role, ctx);
            FeedItem[] feedItems = resolved != null ? resolved.getFeedItems() : null;
            if (feedItems != null) {
                for (FeedItem feedItem : feedItems) {
                    if (feedItem == null) {
                        continue;
                    }
                    String itemId = feedItem.getItem();
                    if (itemId != null && itemId.equalsIgnoreCase(heldItem)) {
                        Double itemHeal = feedItem.getHeal();
                        return itemHeal != null ? itemHeal : (baseHeal != null ? baseHeal : 0);
                    }
                }
            }
        }
        return baseHeal != null ? baseHeal : 0;
    }

    // Removes a quantity of the held item from the player's hotbar.
    boolean consumeHeldItem(Player player, int quantity) {
        return InteractionItemConsumption.removeHeldItemQuantity(player, quantity);
    }
}
