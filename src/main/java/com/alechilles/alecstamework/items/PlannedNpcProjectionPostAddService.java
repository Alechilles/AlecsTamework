package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restores live-only role state and presentation after a projection enters the world. */
final class PlannedNpcProjectionPostAddService {

    void apply(@Nonnull World world,
               @Nonnull Ref<EntityStore> reference,
               @Nonnull NPCEntity npc,
               @Nonnull Store<EntityStore> store,
               @Nonnull CoopResidentStateRestorer.PostAddWork work) {
        if (!reference.isValid()) {
            return;
        }
        applyOwnerTarget(world, reference, npc, store);
        if (work.hasDisplayNameWork()) {
            EntitySupport.setDisplayName(reference, work.displayName(), store);
        }
        if (work.hasHealthWork()) {
            CompanionStatModifierService.applyTraitModifiers(reference, store);
            CompanionHealthStateService.applyStoredHealth(reference, store,
                    work.currentHealth(), work.maximumHealth(),
                    work.healthPercent());
        }
        if (work.hasAttachmentWork()) {
            CompanionModelAttachmentService.applyAttachments(
                    reference,
                    npc,
                    store,
                    work.attachments().getAttachmentIds()
            );
        }
    }

    private void applyOwnerTarget(
            World world,
            Ref<EntityStore> reference,
            NPCEntity npc,
            Store<EntityStore> store
    ) {
        Role role = npc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return;
        }
        assignOwnerTarget(
                reference,
                store,
                TameworkOwnerComponent.getComponentType(),
                world::getEntityRef,
                ownerRef -> role.getMarkedEntitySupport()
                        .setMarkedEntity("MasterTarget", ownerRef)
        );
    }

    static boolean assignOwnerTarget(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            Function<UUID, Ref<EntityStore>> ownerResolver,
            Consumer<Ref<EntityStore>> targetAssigner
    ) {
        if (ownerType == null) {
            return false;
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        UUID ownerUuid = owner == null ? null : owner.getOwnerId();
        if (ownerUuid == null) {
            return false;
        }
        Ref<EntityStore> ownerRef = ownerResolver.apply(ownerUuid);
        if (ownerRef == null || !ownerRef.isValid()) {
            return false;
        }
        targetAssigner.accept(ownerRef);
        return true;
    }
}
