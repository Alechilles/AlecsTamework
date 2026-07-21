package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Captures the thread-affine live state needed before an asynchronous owner admission. */
final class OwnerMutationSnapshotResolver {
    private final CompanionIdentityResolver identityResolver;

    OwnerMutationSnapshotResolver(@Nonnull CompanionIdentityResolver identityResolver) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    }

    @Nullable
    Snapshot resolve(@Nonnull Ref<EntityStore> npcRef,
                     @Nonnull Store<EntityStore> store,
                     @Nullable String canonicalProfileId,
                     @Nullable UUID previousNpcUuid,
                     boolean explicitLiveOwnerExpectation,
                     @Nullable UUID expectedLiveOwnerId,
                     @Nonnull String idempotencyKey) {
        LiveEntity live = readLiveEntity(npcRef, store);
        if (live == null) {
            return null;
        }
        Identity identity = resolveIdentity(
                live.npcUuid(),
                canonicalProfileId,
                previousNpcUuid,
                idempotencyKey
        );
        if (identity == null) {
            return null;
        }
        return new Snapshot(
                live.world(),
                live.worldName(),
                live.npcUuid(),
                identity.baselineNpcUuid(),
                identity.profileId(),
                live.ownerId(),
                live.ownerName(),
                live.roleId(),
                explicitLiveOwnerExpectation ? expectedLiveOwnerId : live.ownerId(),
                live.chunkX(),
                live.chunkZ(),
                identity.provisional()
        );
    }

    @Nullable
    private LiveEntity readLiveEntity(@Nonnull Ref<EntityStore> npcRef,
                                      @Nonnull Store<EntityStore> store) {
        if (!npcRef.isValid() || store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (uuidType == null || transformType == null || ownerType == null) {
            return null;
        }
        UUIDComponent uuid = store.getComponent(npcRef, uuidType);
        TransformComponent transform = store.getComponent(npcRef, transformType);
        Vector3d position = transform == null ? null : transform.getPosition();
        if (uuid == null || uuid.getUuid() == null || position == null) {
            return null;
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        return new LiveEntity(
                world,
                world.getName().trim(),
                uuid.getUuid(),
                owner == null ? null : owner.getOwnerId(),
                owner == null ? null : owner.getOwnerName(),
                CompanionRoleIdResolver.resolveRoleId(npcRef, store),
                ChunkUtil.chunkCoordinate((int) Math.floor(position.x)),
                ChunkUtil.chunkCoordinate((int) Math.floor(position.z))
        );
    }

    @Nullable
    Identity resolveIdentity(@Nonnull UUID liveNpcUuid,
                             @Nullable String canonicalProfileId,
                             @Nullable UUID previousNpcUuid,
                             @Nonnull String idempotencyKey) {
        if (canonicalProfileId == null) {
            CompanionIdentityResolver.Resolution resolution =
                    identityResolver.resolveOrAllocate(liveNpcUuid, idempotencyKey);
            return new Identity(resolution.profileId(), liveNpcUuid, resolution.provisional());
        }
        String mappedLiveProfile = identityResolver.resolveProfileId(liveNpcUuid).orElse(null);
        if (mappedLiveProfile != null && !canonicalProfileId.equals(mappedLiveProfile)) {
            return null;
        }
        UUID baselineNpcUuid = previousNpcUuid != null
                ? previousNpcUuid
                : identityResolver.currentNpcUuid(canonicalProfileId).orElse(null);
        if (baselineNpcUuid == null) {
            return null;
        }
        String mappedBaselineProfile = identityResolver.resolveProfileId(baselineNpcUuid).orElse(null);
        return mappedBaselineProfile == null || canonicalProfileId.equals(mappedBaselineProfile)
                ? new Identity(canonicalProfileId, baselineNpcUuid, false)
                : null;
    }

    boolean releaseProvisional(@Nonnull Snapshot snapshot) {
        return !snapshot.provisionalIdentity()
                || !identityResolver.isProvisional(snapshot.profileId(), snapshot.npcUuid())
                || identityResolver.releaseProvisional(snapshot.profileId(), snapshot.npcUuid());
    }

    static boolean isDuplicateRepresentation(@Nonnull Snapshot snapshot,
                                             @Nullable OwnerPopulationEntry current,
                                             @Nonnull OwnerPopulationOperation operation) {
        if (current == null || snapshot.npcUuid().equals(snapshot.baselineNpcUuid())) {
            return false;
        }
        if (operation != OwnerPopulationOperation.RESTORE
                && operation != OwnerPopulationOperation.LEGACY_ADOPTION) {
            return false;
        }
        return current.lifecycleState() == CompanionLifecycleState.ACTIVE
                || current.lifecycleState() == CompanionLifecycleState.UNLOADED
                || current.lifecycleState() == CompanionLifecycleState.RESTORING;
    }

    record Snapshot(@Nonnull World world,
                    @Nonnull String worldName,
                    @Nonnull UUID npcUuid,
                    @Nonnull UUID baselineNpcUuid,
                    @Nonnull String profileId,
                    @Nullable UUID liveOwnerId,
                    @Nullable String liveOwnerName,
                    @Nullable String roleId,
                    @Nullable UUID expectedLiveOwnerId,
                    int chunkX,
                    int chunkZ,
                    boolean provisionalIdentity) {
        Snapshot(@Nonnull World world,
                 @Nonnull String worldName,
                 @Nonnull UUID npcUuid,
                 @Nonnull UUID baselineNpcUuid,
                 @Nonnull String profileId,
                 @Nullable UUID liveOwnerId,
                 @Nullable String liveOwnerName,
                 @Nullable UUID expectedLiveOwnerId,
                 int chunkX,
                 int chunkZ,
                 boolean provisionalIdentity) {
            this(world, worldName, npcUuid, baselineNpcUuid, profileId, liveOwnerId,
                    liveOwnerName, null, expectedLiveOwnerId, chunkX, chunkZ, provisionalIdentity);
        }
    }

    private record LiveEntity(@Nonnull World world,
                              @Nonnull String worldName,
                              @Nonnull UUID npcUuid,
                              @Nullable UUID ownerId,
                              @Nullable String ownerName,
                              @Nullable String roleId,
                              int chunkX,
                              int chunkZ) {
    }

    record Identity(@Nonnull String profileId,
                    @Nonnull UUID baselineNpcUuid,
                    boolean provisional) {
    }
}
