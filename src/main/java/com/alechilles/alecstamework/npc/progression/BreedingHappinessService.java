package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * @deprecated Use {@link CompanionHappinessService} for shared happiness updates.
 */
@Deprecated
public final class BreedingHappinessService {
    private BreedingHappinessService() {
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        return CompanionHappinessService.applyFeedGain(npcRef, store);
    }
}
