package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Dispatches a card action with the live entity-store context supplied by the
 * UI event, rather than the context that originally opened the page.
 */
@FunctionalInterface
public interface LinkedNpcPanelFeatureAction {
    void accept(UUID npcUuid, Ref<EntityStore> playerRef,
                Store<EntityStore> store);
}
