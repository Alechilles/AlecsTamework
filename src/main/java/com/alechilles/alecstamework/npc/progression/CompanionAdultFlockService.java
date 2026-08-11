package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/**
 * Applies the role-configured flock cleanup when a companion becomes an adult.
 */
final class CompanionAdultFlockService {
    private static final String REMOVE_ON_ADULT_PARAM = "FlockRemoveOnAdult";

    private CompanionAdultFlockService() {
    }

    static boolean removeIfConfigured(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable NPCEntity npc,
                                      @Nullable Store<EntityStore> store) {
        if (npc == null || npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        StdScope scope = NpcSupportAccess.sensorScope(npc.getRole(), npcRef, store);
        if (!shouldRemove(scope)) {
            return false;
        }
        ComponentType<EntityStore, FlockMembership> flockType = FlockMembership.getComponentType();
        if (flockType == null || store.getComponent(npcRef, flockType) == null) {
            return false;
        }
        store.tryRemoveComponent(npcRef, flockType);
        return true;
    }

    private static boolean shouldRemove(@Nullable StdScope scope) {
        if (scope == null) {
            return false;
        }
        try {
            BooleanSupplier supplier = scope.getBooleanSupplier(REMOVE_ON_ADULT_PARAM);
            return supplier != null && supplier.getAsBoolean();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}
