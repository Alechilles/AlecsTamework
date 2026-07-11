package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/** Restores pre-job cooldown state only while live state still matches the job fingerprint. */
final class BreedingCooldownRollbackService {
    private final BreedingParentStateService parentStateService = new BreedingParentStateService();

    int rollback(@Nonnull BreedingBirthJob job) {
        Universe universe = Universe.get();
        World world = universe != null ? universe.getWorld(job.pairKey().worldId()) : null;
        Store<EntityStore> store = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore()
                : null;
        if (world == null || store == null) {
            return 0;
        }
        int restored = 0;
        if (restore(
                job.firstParent(),
                job.firstParentSnapshot(),
                job.firstParentCooldownFingerprint(),
                world,
                store
        )) {
            restored++;
        }
        if (restore(
                job.secondParent(),
                job.secondParentSnapshot(),
                job.secondParentCooldownFingerprint(),
                world,
                store
        )) {
            restored++;
        }
        return restored;
    }

    private boolean restore(BreedingParentIdentity identity,
                            ParentBreedingSnapshot snapshot,
                            AppliedCooldownFingerprint fingerprint,
                            World world,
                            Store<EntityStore> store) {
        Ref<EntityStore> ref = world.getEntityRef(identity.entityUuid());
        if (ref == null || !ref.isValid() || isDead(ref, store)) {
            return false;
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType =
                TameworkBreedingComponent.getComponentType();
        TameworkBreedingComponent breeding = breedingType != null
                ? store.getComponent(ref, breedingType)
                : null;
        return npc != null && breeding != null && parentStateService.restoreIfFingerprintMatches(
                identity,
                snapshot,
                fingerprint,
                ref,
                npc,
                breeding,
                store
        );
    }

    private boolean isDead(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, DeathComponent> deathType = DeathComponent.getComponentType();
        return deathType != null && store.getComponent(ref, deathType) != null;
    }
}
