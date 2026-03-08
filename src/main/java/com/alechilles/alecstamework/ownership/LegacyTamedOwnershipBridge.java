package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Bridges legacy vanilla-tamed NPCs into Tamework ownership when mods are added mid-playthrough.
 *
 * <p>If an NPC is considered tamed by {@link TamedStateResolver} but has no Tamework owner set,
 * the first interacting player can be assigned as owner. Existing non-null owners are never
 * overridden by this bridge.
 */
public final class LegacyTamedOwnershipBridge {

    private LegacyTamedOwnershipBridge() {
    }

    /**
     * Claims ownership for legacy tamed NPCs that are missing a Tamework owner.
     */
    public static ClaimResult claimForPlayerIfEligible(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store,
                                                        Player player) {
        if (npcRef == null || store == null || !npcRef.isValid() || player == null) {
            return ClaimResult.none();
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return ClaimResult.none();
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return ClaimResult.none();
        }
        TameworkOwnerComponent existingOwner = store.getComponent(npcRef, ownerType);
        UUID existingOwnerId = existingOwner != null ? existingOwner.getOwnerId() : null;
        String existingOwnerName = existingOwner != null ? existingOwner.getOwnerName() : null;
        if (existingOwnerId != null) {
            return new ClaimResult(existingOwnerId, existingOwnerName, false);
        }
        if (!TamedStateResolver.isTamed(npcRef, store)) {
            return ClaimResult.none();
        }

        String ownerName = OwnerNameUtil.resolve(player);
        store.putComponent(npcRef, ownerType, new TameworkOwnerComponent(playerId, ownerName));
        ensureTamedComponent(store, npcRef);
        return new ClaimResult(playerId, ownerName, true);
    }

    /**
     * Resolves owner metadata without mutating NPC state.
     */
    public static ClaimResult resolveOwner(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return ClaimResult.none();
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return ClaimResult.none();
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        if (owner == null) {
            return ClaimResult.none();
        }
        return new ClaimResult(owner.getOwnerId(), owner.getOwnerName(), false);
    }

    private static void ensureTamedComponent(Store<EntityStore> store, Ref<EntityStore> npcRef) {
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType == null) {
            return;
        }
        TameworkTamedComponent tamed = store.getComponent(npcRef, tamedType);
        if (tamed != null && tamed.isTamed()) {
            return;
        }
        store.putComponent(npcRef, tamedType, new TameworkTamedComponent(true));
    }

    /**
     * Owner resolution result for bridge checks.
     */
    public static final class ClaimResult {
        private final UUID ownerId;
        private final String ownerName;
        private final boolean claimed;

        private ClaimResult(UUID ownerId, String ownerName, boolean claimed) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.claimed = claimed;
        }

        public UUID getOwnerId() {
            return ownerId;
        }

        public String getOwnerName() {
            return ownerName;
        }

        public boolean isClaimed() {
            return claimed;
        }

        public static ClaimResult none() {
            return new ClaimResult(null, null, false);
        }
    }
}
