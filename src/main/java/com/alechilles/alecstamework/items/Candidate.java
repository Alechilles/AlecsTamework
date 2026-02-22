package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Candidate NPC selected for command evaluation/execution.
 */
final class Candidate {
    final Ref<EntityStore> ref;
    final NPCEntity npc;
    final double distSq;

    Candidate(Ref<EntityStore> ref, NPCEntity npc, double distSq) {
        this.ref = ref;
        this.npc = npc;
        this.distSq = distSq;
    }
}
