package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.damage.DamageTargetMemoryService;
import com.alechilles.alecstamework.damage.RecentNeedsDeathCauseService;
import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Copies authoritative Hytale lifecycle evidence into immutable dormant observations.
 *
 * <p>This boundary accepts a saved {@link DeathComponent}, an explicit destructive entity
 * removal, or an uncancelled delete-on-remove world event supplied by the world adapter. It never
 * converts unload, absence, or elapsed time into a lifecycle transition.</p>
 */
public final class HytaleDormantCompanionObservationFactory
        implements DormantCompanionEcsBridge.ObservationFactory {
    private static final String OBSERVATION_NAMESPACE =
            "companion-dormant-observation:v1";
    private static final String RECEIPT_NAMESPACE =
            "companion-dormant-evidence:v1";
    private static final String REVIVE_COOLDOWN_MULTIPLIER =
            "ReviveCooldownMultiplier";
    private static final long RECENT_ATTACKER_MAX_AGE_MS = 30_000L;

    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent>
            linksType;
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent>
            projectionType;
    private final ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
            retirementType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final LongSupplier clock;

    /** Creates the production evidence freezer from already-registered component types. */
    public HytaleDormantCompanionObservationFactory(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkCommandLinksComponent>
                    linksType,
            @Nonnull ComponentType<EntityStore,
                    TameworkProjectionIdentityComponent> projectionType,
            @Nonnull ComponentType<EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nonnull LongSupplier clock
    ) {
        this.npcType = Objects.requireNonNull(npcType, "npcType");
        this.linksType = Objects.requireNonNull(linksType, "linksType");
        this.projectionType = Objects.requireNonNull(
                projectionType, "projectionType"
        );
        this.retirementType = Objects.requireNonNull(
                retirementType, "retirementType"
        );
        this.deathType = Objects.requireNonNull(deathType, "deathType");
        this.transformType = Objects.requireNonNull(
                transformType, "transformType"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Nullable
    public DormantCompanionEcsBridge.FrozenObservation death(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store
    ) {
        return observe(
                reference,
                store,
                DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT,
                death
        );
    }

    @Override
    @Nullable
    public DormantCompanionEcsBridge.FrozenObservation removal(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store
    ) {
        if (!authoritativeRemoval(
                reason,
                store.getComponent(reference, deathType) != null,
                store.getComponent(reference, retirementType) != null,
                managedCaptureSource(store.getComponent(
                        reference, projectionType
                ))
        )) {
            return null;
        }
        return observe(
                reference,
                store,
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL,
                null
        );
    }

    @Override
    @Nullable
    public DormantCompanionEcsBridge.FrozenObservation worldDeletion(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store
    ) {
        if (store.getComponent(reference, deathType) != null
                || store.getComponent(reference, retirementType) != null
                || managedCaptureSource(store.getComponent(
                        reference, projectionType
                ))) {
            return null;
        }
        return observe(
                reference,
                store,
                DormantCompanionObservation.Evidence.WORLD_DELETION,
                null
        );
    }

    @Nullable
    private DormantCompanionEcsBridge.FrozenObservation observe(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            DormantCompanionObservation.Evidence evidence,
            @Nullable DeathComponent death
    ) {
        NPCEntity npc = store.getComponent(reference, npcType);
        TameworkCommandLinksComponent links =
                store.getComponent(reference, linksType);
        UUID npcUuid = npc == null ? null : npc.getUuid();
        String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
        String worldKey = worldKey(store);
        if (npcUuid == null || roleId == null || roleId.isBlank()
                || worldKey == null
                || !CompanionRevivePolicy.supportsRevive(roleId, links)) {
            return null;
        }
        TameworkProjectionIdentityComponent projection =
                store.getComponent(reference, projectionType);
        ProfileId profileId = profileId(projection, npcUuid);
        if (profileId == null) {
            return null;
        }
        NpcAlias sourceAlias = new NpcAlias(npcUuid);
        long observedAtMs = clock.getAsLong();
        DormantCompanionObservation.DeathObservation deathObservation =
                death == null ? null : deathObservation(
                        reference, store, npcUuid, roleId, death, observedAtMs
                );
        DormantCompanionObservation.LostObservation lostObservation =
                death == null
                        ? new DormantCompanionObservation.LostObservation(0L, 0)
                        : null;
        String[] stableParts = {
                profileId.toString(),
                sourceAlias.toString(),
                worldKey,
                evidence.name()
        };
        DormantCompanionObservation observation =
                new DormantCompanionObservation(
                        StablePersistenceIds.idempotencyKey(
                                OBSERVATION_NAMESPACE, stableParts
                        ).value(),
                        profileId,
                        sourceAlias,
                        worldKey,
                        evidence,
                        StablePersistenceIds.receipt(
                                RECEIPT_NAMESPACE, stableParts
                        ),
                        observedAtMs,
                        position(reference, store),
                        deathObservation,
                        lostObservation
                );
        return new DormantCompanionEcsBridge.FrozenObservation(
                observation, roleId
        );
    }

    private DormantCompanionObservation.DeathObservation deathObservation(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            UUID npcUuid,
            String roleId,
            DeathComponent death,
            long diedAtMs
    ) {
        DamageTargetMemoryService.RecentAttackerSnapshot attacker =
                DamageTargetMemoryService.getInstance().getRecentAttacker(
                        npcUuid, RECENT_ATTACKER_MAX_AGE_MS, diedAtMs
                );
        DeathSnapshotV2Payload.DeathCauseKind needs =
                RecentNeedsDeathCauseService.getInstance().consumeRecent(
                        npcUuid, diedAtMs
                );
        DeathSnapshotV2Payload.DeathCauseKind cause = needs != null
                ? needs
                : attacker != null
                ? attackerCause(attacker)
                : persistedCause(death);
        long cooldown = reviveCooldownMs(reference, store, roleId);
        return new DormantCompanionObservation.DeathObservation(
                diedAtMs,
                saturatingAdd(diedAtMs, cooldown),
                cause,
                attacker == null ? null : attacker.attackerName()
        );
    }

    private long reviveCooldownMs(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            String roleId
    ) {
        long configured = Math.max(
                0L,
                TwCompanionConfig.resolveEffectiveForRole(roleId)
                        .getDeadRespawnCooldownMs()
        );
        double multiplier =
                CompanionProgressionModifierService.resolveMultiplier(
                        reference,
                        store,
                        REVIVE_COOLDOWN_MULTIPLIER,
                        1.0
                );
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double scaled = configured * multiplier;
        return Double.isFinite(scaled)
                ? Math.max(0L, Math.round(scaled))
                : configured;
    }

    @Nonnull
    private DeathSnapshotV2Payload.DeathCauseKind attackerCause(
            DamageTargetMemoryService.RecentAttackerSnapshot attacker
    ) {
        return switch (attacker.attackerKind()) {
            case PLAYER -> DeathSnapshotV2Payload.DeathCauseKind.PLAYER;
            case NPC -> DeathSnapshotV2Payload.DeathCauseKind.NPC;
            case OTHER -> DeathSnapshotV2Payload.DeathCauseKind.UNKNOWN;
        };
    }

    @Nonnull
    private DeathSnapshotV2Payload.DeathCauseKind persistedCause(
            DeathComponent death
    ) {
        if (death.getDeathCause() == null) {
            return DeathSnapshotV2Payload.DeathCauseKind.UNKNOWN;
        }
        String id = death.getDeathCause().getId();
        if (id == null || id.isBlank()) {
            return DeathSnapshotV2Payload.DeathCauseKind.UNKNOWN;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.contains("physical")
                || normalized.contains("projectile")
                ? DeathSnapshotV2Payload.DeathCauseKind.UNKNOWN
                : DeathSnapshotV2Payload.DeathCauseKind.ENVIRONMENT;
    }

    @Nullable
    private ProfileId profileId(
            @Nullable TameworkProjectionIdentityComponent projection,
            UUID fallback
    ) {
        if (projection == null) {
            return new ProfileId(fallback);
        }
        try {
            return ProfileId.parse(projection.getProfileId());
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    @Nullable
    private DormantCompanionObservation.PositionObservation position(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        TransformComponent transform =
                store.getComponent(reference, transformType);
        Vector3d value = transform == null ? null : transform.getPosition();
        if (value == null || !Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            return null;
        }
        return new DormantCompanionObservation.PositionObservation(
                value.x, value.y, value.z
        );
    }

    @Nullable
    private String worldKey(Store<EntityStore> store) {
        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        String name = world == null ? null : world.getName();
        return name == null || name.isBlank() ? null : name.trim();
    }

    static boolean authoritativeRemoval(
            @Nullable RemoveReason reason,
            boolean hasDeath,
            boolean hasRetirement,
            boolean managedCaptureSource
    ) {
        return reason == RemoveReason.REMOVE
                && !hasDeath
                && !hasRetirement
                && !managedCaptureSource;
    }

    static boolean managedCaptureSource(
            @Nullable TameworkProjectionIdentityComponent projection
    ) {
        return projection != null
                && TameworkProjectionIdentityComponent
                .KIND_MANAGED_COOP_CAPTURE_SOURCE.equals(
                        projection.getProjectionKind()
                );
    }

    private long saturatingAdd(long value, long nonnegativeDelta) {
        return value > Long.MAX_VALUE - nonnegativeDelta
                ? Long.MAX_VALUE
                : value + nonnegativeDelta;
    }
}
