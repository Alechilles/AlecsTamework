package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthAnchor;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Gathers live Hytale parent state and submits one guarded pairing admission transaction. */
final class BreedingHytalePairingService {
    private final BreedingPartnerService partnerService;
    private final BreedingPairingCoordinator coordinator;
    private final BreedingFertilityOffspringService fertilityService = new BreedingFertilityOffspringService();
    private final BreedingOffspringRoleResolver roleResolver = new BreedingOffspringRoleResolver();
    private final BreedingPopulationTypeService populationTypeService = new BreedingPopulationTypeService();
    private final BreedingCapacityRequestFactory capacityFactory = new BreedingCapacityRequestFactory();
    private final BreedingParentStateService parentStateService = new BreedingParentStateService();
    private final BreedingParentCooldownResolver cooldownResolver = new BreedingParentCooldownResolver();
    private final BreedingPairEffectsService pairEffectsService = new BreedingPairEffectsService();

    BreedingHytalePairingService(@Nonnull BreedingPartnerService partnerService,
                                 @Nonnull BreedingPairingCoordinator coordinator) {
        this.partnerService = partnerService;
        this.coordinator = coordinator;
    }

    boolean tryPassive(@Nullable Ref<EntityStore> sourceRef,
                       @Nullable Store<EntityStore> store,
                       @Nullable TameworkBreedingComponent sourceBreeding,
                       @Nullable TwBreedingConfig config,
                       @Nullable CommandBuffer<EntityStore> commandBuffer) {
        long now = store != null ? BreedingTimeService.resolveCurrentTimeMs(store) : 0L;
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                commandBuffer,
                BreedingReadinessPolicy.passive(now),
                BreedingPopulationAdmissionService.BreedingMode.PASSIVE
        );
    }

    boolean tryManual(@Nullable Ref<EntityStore> sourceRef,
                      @Nullable Store<EntityStore> store,
                      @Nullable TameworkBreedingComponent sourceBreeding,
                      @Nullable TwBreedingConfig config,
                      @Nonnull UUID playerUuid) {
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                null,
                BreedingReadinessPolicy.manual(playerUuid, ManualBreedingClock.nowMs()),
                BreedingPopulationAdmissionService.BreedingMode.MANUAL
        );
    }

    private boolean tryPair(@Nullable Ref<EntityStore> sourceRef,
                            @Nullable Store<EntityStore> store,
                            @Nullable TameworkBreedingComponent sourceBreeding,
                            @Nullable TwBreedingConfig config,
                            @Nullable CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull BreedingReadinessPolicy readinessPolicy,
                            @Nonnull BreedingPopulationAdmissionService.BreedingMode mode) {
        if (sourceRef == null || !sourceRef.isValid() || store == null || sourceBreeding == null) {
            return false;
        }
        BreedingPartnerService.PartnerCandidate partner = partnerService.findNearestPartner(
                sourceRef,
                store,
                sourceBreeding,
                config,
                readinessPolicy
        );
        if (partner == null || partner.ref == null || !partner.ref.isValid()) {
            return false;
        }
        NPCEntity sourceNpc = store.getComponent(sourceRef, NPCEntity.getComponentType());
        NPCEntity partnerNpc = store.getComponent(partner.ref, NPCEntity.getComponentType());
        TameworkBreedingComponent partnerBreeding = breedingComponent(partner.ref, store);
        if (sourceNpc == null || sourceNpc.getUuid() == null
                || partnerNpc == null || partnerNpc.getUuid() == null
                || !BreedingOffspringService.acceptsPartnerReadiness(readinessPolicy, partnerBreeding)) {
            return false;
        }
        PreparedParents prepared = prepareParents(
                sourceRef,
                sourceNpc,
                sourceBreeding,
                partner.ref,
                partnerNpc,
                partnerBreeding,
                store,
                config
        );
        if (prepared == null) {
            return false;
        }
        BreedingPairingCoordinator.PairingResult result = coordinator.admit(request(
                prepared,
                store,
                config,
                mode,
                commandBuffer
        ));
        if (!result.accepted()) {
            logInfo("Breeding pairing admission rejected: status=" + result.status()
                    + " reason=" + result.reason());
        }
        return result.accepted();
    }

    @Nullable
    private PreparedParents prepareParents(Ref<EntityStore> sourceRef,
                                           NPCEntity sourceNpc,
                                           TameworkBreedingComponent sourceBreeding,
                                           Ref<EntityStore> partnerRef,
                                           NPCEntity partnerNpc,
                                           TameworkBreedingComponent partnerBreeding,
                                           Store<EntityStore> store,
                                           @Nullable TwBreedingConfig config) {
        Vector3d anchor = resolveAnchor(sourceRef, partnerRef, store);
        String worldId = resolveWorldId(store);
        if (anchor == null || worldId == null) {
            return null;
        }
        ParentBreedingSnapshot sourceSnapshot = parentStateService.snapshot(sourceBreeding, sourceNpc);
        ParentBreedingSnapshot partnerSnapshot = parentStateService.snapshot(partnerBreeding, partnerNpc);
        if (sourceSnapshot == null || partnerSnapshot == null) {
            return null;
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        long happinessUpdatedAtMs = System.currentTimeMillis();
        BreedingParentCooldownResolver.ResolvedCooldown sourceCooldown =
                cooldownResolver.resolve(config, sourceRef, store);
        BreedingParentCooldownResolver.ResolvedCooldown partnerCooldown =
                cooldownResolver.resolve(config, partnerRef, store);
        BreedingCooldownService.CooldownWindow sourceWindow =
                BreedingCooldownService.resolveWindow(now, sourceCooldown.durationMs());
        BreedingCooldownService.CooldownWindow partnerWindow =
                BreedingCooldownService.resolveWindow(now, partnerCooldown.durationMs());
        BreedingParentIdentity sourceIdentity = parentStateService.resolveIdentity(sourceRef, sourceNpc, store);
        BreedingParentIdentity partnerIdentity = parentStateService.resolveIdentity(partnerRef, partnerNpc, store);
        AppliedCooldownFingerprint sourceFingerprint = parentStateService.fingerprint(
                sourceNpc, sourceWindow, partnerNpc.getUuid(), happinessUpdatedAtMs
        );
        AppliedCooldownFingerprint partnerFingerprint = parentStateService.fingerprint(
                partnerNpc, partnerWindow, sourceNpc.getUuid(), happinessUpdatedAtMs
        );
        return new PreparedParents(
                sourceRef,
                sourceNpc,
                sourceBreeding,
                partnerRef,
                partnerNpc,
                partnerBreeding,
                sourceIdentity,
                partnerIdentity,
                sourceSnapshot,
                partnerSnapshot,
                sourceFingerprint,
                partnerFingerprint,
                sourceCooldown,
                partnerCooldown,
                now,
                happinessUpdatedAtMs,
                new BreedingBirthAnchor(anchor.x, anchor.y, anchor.z),
                worldId,
                resolveRoleId(sourceNpc),
                resolveRoleId(partnerNpc),
                ownerSnapshot(sourceRef, store),
                ownerSnapshot(partnerRef, store)
        );
    }

    private BreedingPairingCoordinator.PairingRequest request(
            PreparedParents prepared,
            Store<EntityStore> store,
            @Nullable TwBreedingConfig config,
            BreedingPopulationAdmissionService.BreedingMode mode,
            @Nullable CommandBuffer<EntityStore> commandBuffer) {
        BreedingFertilityOffspringService.FertilityMultipliers fertility = fertilityService.resolveMultipliers(
                prepared.sourceRef(), prepared.partnerRef(), store
        );
        return new BreedingPairingCoordinator.PairingRequest(
                store,
                prepared.worldId(),
                mode,
                prepared.sourceIdentity(),
                prepared.partnerIdentity(),
                fertility.parentAMultiplier(),
                fertility.parentBMultiplier(),
                index -> plannedChild(prepared, config),
                (jobId, plan) -> capacityFactory.prepare(
                        jobId,
                        mode,
                        plan,
                        prepared.anchor(),
                        store,
                        config,
                        prepared.sourceRoleId(),
                        prepared.sourceOwner(),
                        prepared.partnerOwner(),
                        null
                ),
                prepared.sourceSnapshot(),
                prepared.partnerSnapshot(),
                prepared.sourceFingerprint(),
                prepared.partnerFingerprint(),
                prepared.anchor(),
                job -> applyRegisteredEffects(prepared, store, commandBuffer),
                job -> pairEffectsService.rollback(
                        effectContext(prepared, store, commandBuffer),
                        job
                )
        );
    }

    @Nullable
    private PlannedChild plannedChild(PreparedParents prepared, @Nullable TwBreedingConfig config) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        BreedingOffspringRoleResolver.OffspringRoleSelection role = roleResolver.selectOffspringRole(
                prepared.sourceRoleId(),
                prepared.partnerRoleId(),
                config,
                npcPlugin,
                Math.random(),
                Math.random(),
                Math.random(),
                Math.random()
        );
        if (role == null || npcPlugin == null || npcPlugin.getIndex(role.roleId()) < 0) {
            return null;
        }
        String populationType = populationTypeService.resolveTypeKey(role.roleId(), config);
        if (populationType == null) {
            return null;
        }
        return new PlannedChild(
                role.roleId(),
                role.adultRoleId(),
                role.gender() != null ? role.gender().toConfigValue() : null,
                lifecycleKey(role.lifecycleFamily()),
                populationType
        );
    }

    private boolean applyRegisteredEffects(PreparedParents prepared,
                                           Store<EntityStore> store,
                                           @Nullable CommandBuffer<EntityStore> commandBuffer) {
        return pairEffectsService.apply(effectContext(prepared, store, commandBuffer));
    }

    private BreedingPairEffectsService.EffectContext effectContext(
            PreparedParents prepared,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer) {
        return new BreedingPairEffectsService.EffectContext(
                prepared.sourceRef(),
                prepared.sourceNpc(),
                prepared.sourceBreeding(),
                prepared.partnerRef(),
                prepared.partnerNpc(),
                prepared.partnerBreeding(),
                prepared.sourceCooldown(),
                prepared.partnerCooldown(),
                prepared.sourceOwner(),
                prepared.partnerOwner(),
                prepared.nowMs(),
                prepared.happinessUpdatedAtMs(),
                store,
                commandBuffer
        );
    }

    @Nullable
    private Vector3d resolveAnchor(Ref<EntityStore> sourceRef,
                                   Ref<EntityStore> partnerRef,
                                   Store<EntityStore> store) {
        TransformComponent source = transform(sourceRef, store);
        TransformComponent partner = transform(partnerRef, store);
        if (source == null || partner == null) {
            return null;
        }
        Vector3d a = source.getPosition();
        Vector3d b = partner.getPosition();
        return new Vector3d((a.x + b.x) * 0.5, Math.max(a.y, b.y) + 1.0, (a.z + b.z) * 0.5);
    }

    @Nullable
    private TransformComponent transform(Ref<EntityStore> ref, Store<EntityStore> store) {
        return ref != null && ref.isValid()
                ? store.getComponent(ref, TransformComponent.getComponentType())
                : null;
    }

    @Nullable
    private TameworkBreedingComponent breedingComponent(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        return type != null ? store.getComponent(ref, type) : null;
    }


    @Nonnull
    private BreedingOffspringProgressionService.OwnerSnapshot ownerSnapshot(
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType != null ? store.getComponent(ref, ownerType) : null;
        UUID ownerId = owner != null ? owner.getOwnerId() : null;
        if (ownerId == null) {
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                    TameworkCommandLinksComponent.getComponentType();
            TameworkCommandLinksComponent links = linksType != null ? store.getComponent(ref, linksType) : null;
            ownerId = links != null ? links.getOwnerId() : null;
        }
        return ownerId == null
                ? BreedingOffspringProgressionService.OwnerSnapshot.empty()
                : new BreedingOffspringProgressionService.OwnerSnapshot(
                        ownerId,
                        owner != null ? owner.getOwnerName() : null
                );
    }

    @Nullable
    private String resolveWorldId(Store<EntityStore> store) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        return world != null && world.getName() != null && !world.getName().isBlank() ? world.getName() : null;
    }

    @Nullable
    private String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null && npc.getRoleIndex() >= 0 ? plugin.getName(npc.getRoleIndex()) : null;
    }

    @Nullable
    private String lifecycleKey(@Nullable TwBreedingConfig.RoleFamily family) {
        if (family == null) {
            return null;
        }
        String id = family.getId();
        String line = family.getSelectedLineId();
        if (id == null || id.isBlank()) {
            return line;
        }
        return line == null || line.isBlank() ? id : id + ":" + line;
    }

    private void logInfo(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null && plugin.isDebugBreedingEnabled()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
    }

    private record PreparedParents(
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
}
