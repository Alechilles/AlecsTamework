package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Live world-thread state for one candidate breeding pair. */
record BreedingPairCandidate(
        Ref<EntityStore> sourceRef,
        Ref<EntityStore> partnerRef,
        NPCEntity sourceNpc,
        NPCEntity partnerNpc,
        TameworkBreedingComponent sourceBreeding,
        TameworkBreedingComponent partnerBreeding,
        Store<EntityStore> store,
        World world,
        @Nullable Vector3d spawnAnchor,
        BreedingOffspringProgressionService.OwnerSnapshot sourceOwner,
        BreedingOffspringProgressionService.OwnerSnapshot partnerOwner
) {
}
