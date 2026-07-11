package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import javax.annotation.Nonnull;

/** Applies live-entity presentation work only after a projection's durable finalization commits. */
final class PlannedNpcProjectionPostAddService {

    void apply(@Nonnull Ref<EntityStore> reference,
               @Nonnull NPCEntity npc,
               @Nonnull Store<EntityStore> store,
               @Nonnull CoopResidentStateRestorer.PostAddWork work) {
        if (!reference.isValid()) {
            return;
        }
        if (work.hasDisplayNameWork()) {
            EntitySupport.setDisplayName(reference, work.displayName(), store);
        }
        if (work.hasHealthWork()) {
            CompanionStatModifierService.applyTraitModifiers(reference, store);
            CompanionHealthStateService.applyStoredHealthPercent(reference, store, work.healthPercent());
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
}
