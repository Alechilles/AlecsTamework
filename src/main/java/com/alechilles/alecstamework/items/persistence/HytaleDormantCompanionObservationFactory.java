package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.flow.CompanionDeathTiming;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
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

    private final ComponentType<EntityStore, NPCEntity> npcType;
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
            @Nonnull ComponentType<EntityStore,
                    TameworkProjectionIdentityComponent> projectionType,
            @Nonnull ComponentType<EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nonnull LongSupplier clock
    ) {
        this.npcType = Objects.requireNonNull(npcType, "npcType");
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
                CompanionRevivePolicy.isOldAgeDeath(death)
                        ? DormantCompanionObservation.Evidence.OLD_AGE_DEATH
                        : DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT,
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
        UUIDComponent uuid = store.getComponent(reference, UUIDComponent.getComponentType());
        if (uuid != null && hasRemovalSurvivor(
                reference, uuid.getUuid(), store.getExternalData()
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
        UUID npcUuid = npc == null ? null : npc.getUuid();
        String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
        String worldKey = worldKey(store);
        if (npcUuid == null || roleId == null || roleId.isBlank()
                || worldKey == null) {
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
        CompanionDeathTiming.Timing timing = CompanionDeathTiming.resolve(
                reference, store, npcUuid, roleId, death, diedAtMs
        );
        if (timing.kind() == CompanionDeathTiming.Kind.OLD_AGE) {
            return new DormantCompanionObservation.DeathObservation(
                    diedAtMs, 0L, DeathSnapshotV2Payload.DeathCauseKind.ENVIRONMENT,
                    CompanionRevivePolicy.OLD_AGE_SOURCE);
        }
        return new DormantCompanionObservation.DeathObservation(
                diedAtMs,
                timing.reviveAvailableAtMs(),
                DeathSnapshotV2Payload.DeathCauseKind.valueOf(timing.kind().name()),
                timing.attackerName()
        );
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

    /** Duplicate cleanup removes one ref while another still owns its UUID. */
    static boolean hasRemovalSurvivor(
            Ref<EntityStore> removed,
            @Nullable UUID uuid,
            EntityStore entityStore
    ) {
        Ref<EntityStore> current = uuid == null
                ? null : entityStore.getRefFromUUID(uuid);
        return current != null && current.isValid() && !current.equals(removed);
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
}
