package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthAnchor;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Captures and revalidates the canonical parent state for one pairing attempt. */
final class BreedingParentPreparationService {
    private final BreedingParentStateService parentStateService;
    private final BreedingParentCooldownResolver cooldownResolver;
    private final BreedingParentLifecycleGate lifecycleGate;

    BreedingParentPreparationService() {
        this(
                new BreedingParentStateService(),
                new BreedingParentCooldownResolver(),
                new BreedingParentLifecycleGate()
        );
    }

    BreedingParentPreparationService(
            @Nonnull BreedingParentStateService parentStateService,
            @Nonnull BreedingParentCooldownResolver cooldownResolver) {
        this(parentStateService, cooldownResolver, new BreedingParentLifecycleGate());
    }

    BreedingParentPreparationService(
            @Nonnull BreedingParentStateService parentStateService,
            @Nonnull BreedingParentCooldownResolver cooldownResolver,
            @Nonnull BreedingParentLifecycleGate lifecycleGate) {
        this.parentStateService = Objects.requireNonNull(
                parentStateService, "parentStateService"
        );
        this.cooldownResolver = Objects.requireNonNull(cooldownResolver, "cooldownResolver");
        this.lifecycleGate = Objects.requireNonNull(lifecycleGate, "lifecycleGate");
    }

    @Nullable
    BreedingPreparedParents prepare(
            @Nonnull Ref<EntityStore> sourceRef,
            @Nonnull NPCEntity sourceNpc,
            @Nonnull TameworkBreedingComponent sourceBreeding,
            @Nonnull Ref<EntityStore> partnerRef,
            @Nonnull NPCEntity partnerNpc,
            @Nonnull TameworkBreedingComponent partnerBreeding,
            @Nonnull Store<EntityStore> store,
            @Nullable TwBreedingConfig config) {
        Vector3d anchor = resolveAnchor(sourceRef, partnerRef, store);
        String worldId = resolveWorldId(store);
        if (anchor == null || worldId == null) {
            return null;
        }
        ParentBreedingSnapshot sourceSnapshot = parentStateService.snapshot(
                sourceBreeding, sourceNpc
        );
        ParentBreedingSnapshot partnerSnapshot = parentStateService.snapshot(
                partnerBreeding, partnerNpc
        );
        if (sourceSnapshot == null || partnerSnapshot == null) {
            return null;
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        long happinessUpdatedAtMs = System.currentTimeMillis();
        PreparedParentState source = prepareState(
                sourceRef, sourceNpc, sourceSnapshot, partnerNpc.getUuid(),
                config, store, now, happinessUpdatedAtMs
        );
        PreparedParentState partner = prepareState(
                partnerRef, partnerNpc, partnerSnapshot, sourceNpc.getUuid(),
                config, store, now, happinessUpdatedAtMs
        );
        return new BreedingPreparedParents(
                sourceRef,
                sourceNpc,
                sourceBreeding,
                partnerRef,
                partnerNpc,
                partnerBreeding,
                source.identity(),
                partner.identity(),
                source.snapshot(),
                partner.snapshot(),
                source.fingerprint(),
                partner.fingerprint(),
                source.cooldown(),
                partner.cooldown(),
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

    private PreparedParentState prepareState(
            Ref<EntityStore> ref,
            NPCEntity npc,
            ParentBreedingSnapshot snapshot,
            UUID partnerUuid,
            @Nullable TwBreedingConfig config,
            Store<EntityStore> store,
            long now,
            long happinessUpdatedAtMs) {
        BreedingParentCooldownResolver.ResolvedCooldown cooldown =
                cooldownResolver.resolve(config, ref, store);
        BreedingCooldownService.CooldownWindow window =
                BreedingCooldownService.resolveWindow(now, cooldown.durationMs());
        return new PreparedParentState(
                parentStateService.resolveIdentity(ref, npc, store),
                snapshot,
                parentStateService.fingerprint(
                        npc, window, partnerUuid, happinessUpdatedAtMs
                ),
                cooldown
        );
    }

    @Nullable
    TameworkBreedingComponent breedingComponent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type =
                TameworkBreedingComponent.getComponentType();
        return type != null ? store.getComponent(ref, type) : null;
    }

    @Nonnull
    String preparationIssue(@Nonnull Ref<EntityStore> sourceRef,
                            @Nonnull NPCEntity sourceNpc,
                            @Nonnull TameworkBreedingComponent sourceBreeding,
                            @Nonnull Ref<EntityStore> partnerRef,
                            @Nonnull NPCEntity partnerNpc,
                            @Nonnull TameworkBreedingComponent partnerBreeding,
                            @Nonnull Store<EntityStore> store) {
        String sourceIssue = parentStateService.snapshotIssue(sourceBreeding, sourceNpc);
        if (sourceIssue != null) {
            return "source-" + sourceIssue;
        }
        String partnerIssue = parentStateService.snapshotIssue(partnerBreeding, partnerNpc);
        if (partnerIssue != null) {
            return "partner-" + partnerIssue;
        }
        if (resolveAnchor(sourceRef, partnerRef, store) == null) {
            return "parent-transform-unavailable";
        }
        return resolveWorldId(store) == null
                ? "world-context-unavailable"
                : "parent-snapshot-unavailable";
    }

    @Nonnull
    AppliedCooldownFingerprint persistedFingerprint(@Nonnull ParentBreedingSnapshot snapshot) {
        return parentStateService.fingerprint(snapshot);
    }

    boolean parentsStillCurrent(
            @Nonnull BreedingPreparedParents prepared,
            @Nonnull Store<EntityStore> store) {
        if (!prepared.sourceRef().isValid() || !prepared.partnerRef().isValid()) {
            return false;
        }
        NPCEntity source = store.getComponent(
                prepared.sourceRef(), NPCEntity.getComponentType()
        );
        NPCEntity partner = store.getComponent(
                prepared.partnerRef(), NPCEntity.getComponentType()
        );
        return source != null
                && partner != null
                && breedingComponent(prepared.sourceRef(), store) == prepared.sourceBreeding()
                && breedingComponent(prepared.partnerRef(), store) == prepared.partnerBreeding()
                && parentStateService.matchesIdentity(
                        prepared.sourceIdentity(), prepared.sourceRef(), source, store
                )
                && parentStateService.matchesIdentity(
                        prepared.partnerIdentity(), prepared.partnerRef(), partner, store
                )
                && lifecycleGate.inspect(prepared.sourceIdentity()).allowed()
                && lifecycleGate.inspect(prepared.partnerIdentity()).allowed();
    }

    @Nullable
    private Vector3d resolveAnchor(
            Ref<EntityStore> sourceRef,
            Ref<EntityStore> partnerRef,
            Store<EntityStore> store) {
        TransformComponent source = transform(sourceRef, store);
        TransformComponent partner = transform(partnerRef, store);
        if (source == null || partner == null) {
            return null;
        }
        Vector3d a = source.getPosition();
        Vector3d b = partner.getPosition();
        return new Vector3d(
                (a.x + b.x) * 0.5,
                Math.max(a.y, b.y) + 1.0,
                (a.z + b.z) * 0.5
        );
    }

    @Nullable
    private TransformComponent transform(
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        return ref != null && ref.isValid()
                ? store.getComponent(ref, TransformComponent.getComponentType())
                : null;
    }

    @Nonnull
    private BreedingOffspringProgressionService.OwnerSnapshot ownerSnapshot(
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType != null
                ? store.getComponent(ref, ownerType)
                : null;
        UUID ownerId = owner != null ? owner.getOwnerId() : null;
        if (ownerId == null) {
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                    TameworkCommandLinksComponent.getComponentType();
            TameworkCommandLinksComponent links = linksType != null
                    ? store.getComponent(ref, linksType)
                    : null;
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
        World world = store.getExternalData() != null
                ? store.getExternalData().getWorld()
                : null;
        return world != null && world.getName() != null && !world.getName().isBlank()
                ? world.getName()
                : null;
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
        return plugin != null && npc.getRoleIndex() >= 0
                ? plugin.getName(npc.getRoleIndex())
                : null;
    }

    private record PreparedParentState(
            BreedingParentIdentity identity,
            ParentBreedingSnapshot snapshot,
            AppliedCooldownFingerprint fingerprint,
            BreedingParentCooldownResolver.ResolvedCooldown cooldown) {
    }
}
