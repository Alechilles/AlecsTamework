package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthAnchor;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/** Immutable live-parent snapshot used throughout one guarded breeding attempt. */
record BreedingPreparedParents(
        Ref<EntityStore> sourceRef,
        NPCEntity sourceNpc,
        TameworkBreedingComponent sourceBreeding,
        Ref<EntityStore> partnerRef,
        NPCEntity partnerNpc,
        TameworkBreedingComponent partnerBreeding,
        BreedingParentIdentity sourceIdentity,
        BreedingParentIdentity partnerIdentity,
        ParentBreedingSnapshot sourceSnapshot,
        ParentBreedingSnapshot partnerSnapshot,
        AppliedCooldownFingerprint sourceFingerprint,
        AppliedCooldownFingerprint partnerFingerprint,
        BreedingParentCooldownResolver.ResolvedCooldown sourceCooldown,
        BreedingParentCooldownResolver.ResolvedCooldown partnerCooldown,
        long nowMs,
        long happinessUpdatedAtMs,
        BreedingBirthAnchor anchor,
        String worldId,
        @Nullable String sourceRoleId,
        @Nullable String partnerRoleId,
        BreedingOffspringProgressionService.OwnerSnapshot sourceOwner,
        BreedingOffspringProgressionService.OwnerSnapshot partnerOwner) {
}
