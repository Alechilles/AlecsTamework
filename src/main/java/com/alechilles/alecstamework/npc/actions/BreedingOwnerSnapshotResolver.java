package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Resolves current parent ownership for pairing and delayed offspring birth. */
final class BreedingOwnerSnapshotResolver {
    private BreedingOwnerSnapshotResolver() {
    }

    @Nonnull
    static BreedingOffspringProgressionService.OwnerSnapshot resolve(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null
                ? null
                : store.getComponent(ref, ownerType);
        UUID ownerId = owner == null ? null : owner.getOwnerId();
        if (ownerId == null) {
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                    TameworkCommandLinksComponent.getComponentType();
            TameworkCommandLinksComponent links = linksType == null
                    ? null
                    : store.getComponent(ref, linksType);
            ownerId = links == null ? null : links.getOwnerId();
        }
        return ownerId == null
                ? BreedingOffspringProgressionService.OwnerSnapshot.empty()
                : new BreedingOffspringProgressionService.OwnerSnapshot(
                        ownerId, owner == null ? null : owner.getOwnerName()
                );
    }

    static boolean allowsDelayedBirth(
            boolean requireSameOwner,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentA,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentB
    ) {
        if (!requireSameOwner) {
            return true;
        }
        return parentA.ownerId() != null
                && parentA.ownerId().equals(parentB.ownerId());
    }
}
