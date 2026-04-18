package com.alechilles.alecstamework.npc.actions;

/** Holds resolved item ids plus whether the interaction actually requires an item. */
final class InteractionRequiredItems {
    private final String[] items;
    private final boolean requiresItems;

    InteractionRequiredItems(String[] items, boolean requiresItems) {
        this.items = items == null ? new String[0] : items;
        this.requiresItems = requiresItems;
    }

    String[] getItems() {
        return items;
    }

    boolean requiresItems() {
        return requiresItems;
    }
}
