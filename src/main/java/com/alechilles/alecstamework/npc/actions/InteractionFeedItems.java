package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;

// Holds resolved feed items and their item id list.
final class InteractionFeedItems {
    private final String[] itemIds;
    private final FeedItem[] feedItems;
    private final boolean requiresItems;

    InteractionFeedItems(String[] itemIds, FeedItem[] feedItems, boolean requiresItems) {
        this.itemIds = itemIds == null ? new String[0] : itemIds;
        this.feedItems = feedItems == null ? new FeedItem[0] : feedItems;
        this.requiresItems = requiresItems;
    }

    // Returns the resolved item ids that satisfy the feed requirement.
    String[] getItemIds() {
        return itemIds;
    }

    // Returns the resolved feed item definitions.
    FeedItem[] getFeedItems() {
        return feedItems;
    }

    // Returns whether the interaction requires items to be present.
    boolean requiresItems() {
        return requiresItems;
    }
}
