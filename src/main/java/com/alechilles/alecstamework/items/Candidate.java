package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/**
 * Candidate NPC selected for command evaluation/execution.
 */
final class Candidate {
    final Ref<EntityStore> ref;
    final NPCEntity npc;
    final double distSq;
    @Nullable
    final String profileId;

    Candidate(Ref<EntityStore> ref, NPCEntity npc, double distSq) {
        this(ref, npc, distSq, null);
    }

    Candidate(Ref<EntityStore> ref, NPCEntity npc, double distSq, @Nullable String profileId) {
        this.ref = ref;
        this.npc = npc;
        this.distSq = distSq;
        this.profileId = profileId;
    }
}
