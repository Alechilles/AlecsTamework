package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Resolves nearby breeding partners based on role/config pairing rules.
 */
final class BreedingPartnerService {
    @Nullable
    PartnerCandidate findNearestPartner(Ref<EntityStore> sourceRef,
                                        Store<EntityStore> store,
                                        TameworkBreedingComponent sourceBreeding,
                                        @Nullable TwBreedingConfig config) {
        if (sourceRef == null || !sourceRef.isValid() || store == null || sourceBreeding == null) {
            return null;
        }
        if (!sourceBreeding.isEnabled()) {
            return null;
        }
        NPCEntity sourceNpc = store.getComponent(sourceRef, NPCEntity.getComponentType());
        TransformComponent sourceTransform = store.getComponent(sourceRef, TransformComponent.getComponentType());
        if (sourceNpc == null || sourceTransform == null) {
            return null;
        }
        String sourceRoleId = resolveRoleId(sourceNpc);
        UUID sourceOwnerId = resolveOwnerId(sourceRef, store);
        long now = BreedingTimeService.resolveCurrentTimeMs(store);

        TwBreedingConfig.PairingSettings pairing = config != null ? config.resolvePairing(sourceRoleId) : null;
        TwBreedingConfig.EligibilitySettings eligibility = config != null ? config.resolveEligibility(sourceRoleId) : null;
        double radius = sanitizeRadius(pairing != null ? pairing.getBreedRadius() : 10.0);
        double radiusSq = radius * radius;
        boolean requireSameRole = pairing == null || pairing.isRequireSameRoleId();
        boolean requireSameOwner = pairing != null && pairing.isRequireSameOwner();
        boolean requireWander = pairing == null || pairing.isRequireWanderMode();
        boolean requireTamed = eligibility == null || eligibility.isRequireTamed();
        boolean requireAdult = eligibility == null || eligibility.isRequireAdult();

        if (requireWander && !isInWanderState(sourceNpc.getRole())) {
            return null;
        }
        if (requireAdult && !CompanionLifeStageService.isAdult(sourceRef, store, sourceRoleId)) {
            return null;
        }

        BestPartner best = new BestPartner();
        Vector3d sourcePos = sourceTransform.getPosition();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();

        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity candidateNpc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (candidateNpc == null || sourceNpc.getUuid().equals(candidateNpc.getUuid())) {
                    continue;
                }
                TransformComponent candidateTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (candidateTransform == null) {
                    continue;
                }
                Ref<EntityStore> candidateRef = chunk.getReferenceTo(i);
                if (candidateRef == null || !candidateRef.isValid()) {
                    continue;
                }
                TameworkBreedingComponent candidateBreeding = breedingType != null
                        ? chunk.getComponent(i, breedingType)
                        : null;
                if (candidateBreeding == null
                        || !candidateBreeding.isEnabled()
                        || !candidateBreeding.isReady()
                        || candidateBreeding.isCooldownActive(now)) {
                    continue;
                }
                if (requireTamed && !TamedStateResolver.isTamed(candidateRef, store)) {
                    continue;
                }
                String candidateRoleId = resolveRoleId(candidateNpc);
                if (requireSameRole && !equalsIgnoreCase(sourceRoleId, candidateRoleId)) {
                    continue;
                }
                if (requireAdult && !CompanionLifeStageService.isAdult(candidateRef, store, candidateRoleId)) {
                    continue;
                }
                if (requireWander && !isInWanderState(candidateNpc.getRole())) {
                    continue;
                }
                TameworkOwnerComponent candidateOwner = ownerType != null
                        ? chunk.getComponent(i, ownerType)
                        : null;
                TameworkCommandLinksComponent candidateLinks = linksType != null
                        ? chunk.getComponent(i, linksType)
                        : null;
                UUID candidateOwnerId = resolveOwnerId(candidateOwner, candidateLinks);
                if (requireSameOwner && !sameOwner(sourceOwnerId, candidateOwnerId)) {
                    continue;
                }

                double distanceSq = new Vector3d(candidateTransform.getPosition()).subtract(sourcePos).squaredLength();
                if (distanceSq <= 0.000001 || distanceSq > radiusSq) {
                    continue;
                }
                if (distanceSq < best.distanceSq) {
                    best.distanceSq = distanceSq;
                    best.ref = candidateRef;
                    best.npc = candidateNpc;
                    best.breeding = candidateBreeding;
                    best.roleId = candidateRoleId;
                    best.ownerId = candidateOwnerId;
                }
            }
        });

        if (best.ref == null || best.npc == null || best.breeding == null) {
            return null;
        }
        return new PartnerCandidate(best.ref, best.npc, best.breeding, best.roleId, best.ownerId, best.distanceSq);
    }

    private static boolean equalsIgnoreCase(@Nullable String left, @Nullable String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private static boolean sameOwner(@Nullable UUID sourceOwnerId, @Nullable UUID candidateOwnerId) {
        if (sourceOwnerId == null || candidateOwnerId == null) {
            return false;
        }
        return sourceOwnerId.equals(candidateOwnerId);
    }

    private static UUID resolveOwnerId(@Nullable Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType != null ? store.getComponent(npcRef, ownerType) : null;
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType != null ? store.getComponent(npcRef, linksType) : null;
        return resolveOwnerId(owner, links);
    }

    private static UUID resolveOwnerId(@Nullable TameworkOwnerComponent owner,
                                       @Nullable TameworkCommandLinksComponent links) {
        if (owner != null && owner.getOwnerId() != null) {
            return owner.getOwnerId();
        }
        return links != null ? links.getOwnerId() : null;
    }

    private static double sanitizeRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return 10.0;
        }
        return radius;
    }

    @Nullable
    private static String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0 && NPCPlugin.get() != null) {
            String resolved = NPCPlugin.get().getName(roleIndex);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return null;
    }

    private static boolean isInWanderState(@Nullable Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return false;
        }
        StateSupport stateSupport = role.getStateSupport();
        int stateIndex = stateSupport.getStateIndex();
        if (stateIndex == StateSupport.NO_STATE) {
            return false;
        }
        String stateName = stateSupport.getStateHelper().getStateName(stateIndex);
        if (stateName == null || stateName.isBlank()) {
            return false;
        }
        return stateName.toLowerCase(Locale.ROOT).contains("wander");
    }

    static final class PartnerCandidate {
        final Ref<EntityStore> ref;
        final NPCEntity npc;
        final TameworkBreedingComponent breeding;
        final String roleId;
        final UUID ownerId;
        final double distanceSq;

        PartnerCandidate(Ref<EntityStore> ref,
                         NPCEntity npc,
                         TameworkBreedingComponent breeding,
                         @Nullable String roleId,
                         @Nullable UUID ownerId,
                         double distanceSq) {
            this.ref = ref;
            this.npc = npc;
            this.breeding = breeding;
            this.roleId = roleId;
            this.ownerId = ownerId;
            this.distanceSq = distanceSq;
        }
    }

    private static final class BestPartner {
        private Ref<EntityStore> ref;
        private NPCEntity npc;
        private TameworkBreedingComponent breeding;
        private String roleId;
        private UUID ownerId;
        private double distanceSq = Double.MAX_VALUE;
    }
}
