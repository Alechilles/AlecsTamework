package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.LiveSourceDecision;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RetirementCommand;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.WorldGateway;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owning-world implementation for exact managed-coop capture-source retirement. */
final class HytaleManagedCoopCaptureSourceGateway implements WorldGateway {
    private static final Pattern FINALIZED_RELEASE_OPERATION = Pattern.compile(
            "managed-coop-release:[0-9a-f]{64}");
    private static final Pattern FINALIZED_IMPORT_OPERATION = Pattern.compile(
            "managed-coop-import-operation:[0-9a-f]{64}");
    @Override
    public boolean enqueue(@Nonnull String worldName, @Nonnull Runnable task) {
        World world = resolveWorld(worldName);
        if (world == null) {
            return false;
        }
        world.execute(Objects.requireNonNull(task, "task"));
        return true;
    }

    @Nonnull
    @Override
    public LiveSourceDecision retire(@Nonnull RetirementCommand command) {
        Objects.requireNonNull(command, "command");
        World world = resolveWorld(command.worldName());
        if (world == null || world.getEntityStore() == null
                || world.getEntityStore().getStore() == null) {
            return LiveSourceDecision.unavailable("source_world_or_store_unavailable");
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.assertThread();
        if (world.getName() == null
                || !world.getName().equalsIgnoreCase(command.worldName())) {
            return LiveSourceDecision.conflict("source_world_identity_mismatch");
        }
        Ref<EntityStore> reference = world.getEntityRef(command.sourceNpcUuid());
        if (reference == null || !reference.isValid()) {
            return LiveSourceDecision.absent();
        }
        return retireResolvedSource(store, reference, command);
    }

    @Nonnull
    private LiveSourceDecision retireResolvedSource(
            Store<EntityStore> store,
            Ref<EntityStore> reference,
            RetirementCommand command) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (uuidType == null || npcType == null || markerType == null) {
            return LiveSourceDecision.unavailable("source_component_type_unavailable");
        }
        UUIDComponent uuid = store.getComponent(reference, uuidType);
        NPCEntity npc = store.getComponent(reference, npcType);
        if (uuid == null || !command.sourceNpcUuid().equals(uuid.getUuid())) {
            return LiveSourceDecision.conflict("resolved_source_uuid_mismatch");
        }
        if (npc == null) {
            return LiveSourceDecision.conflict("resolved_source_is_not_npc");
        }
        if (npc.getUuid() != null && !command.sourceNpcUuid().equals(npc.getUuid())) {
            return LiveSourceDecision.conflict("resolved_source_legacy_uuid_mismatch");
        }
        return markAndDespawn(store, reference, npc, markerType, command);
    }

    @Nonnull
    private LiveSourceDecision markAndDespawn(
            Store<EntityStore> store,
            Ref<EntityStore> reference,
            NPCEntity npc,
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType,
            RetirementCommand command) {
        TameworkProjectionIdentityComponent expected = marker(command);
        TameworkProjectionIdentityComponent existing = store.getComponent(reference, markerType);
        if (existing != null && !matches(existing, command)
                && !canTransitionFinalizedProjection(existing, command)) {
            return LiveSourceDecision.conflict("source_projection_marker_conflict");
        }
        if (!matches(existing, command)) {
            store.putComponent(reference, markerType, expected);
        }
        TameworkProjectionIdentityComponent installed = store.getComponent(reference, markerType);
        if (!matches(installed, command)) {
            return LiveSourceDecision.unavailable("source_projection_marker_not_installed");
        }
        if (!npc.isDespawning()) {
            npc.setToDespawn();
        }
        return LiveSourceDecision.despawnRequested();
    }

    @Nonnull
    static TameworkProjectionIdentityComponent marker(RetirementCommand command) {
        return new TameworkProjectionIdentityComponent(
                command.profileId(),
                command.operationId(),
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE,
                command.authoritySlotKey(),
                command.sourceNpcUuid(),
                command.operationGeneration()
        );
    }

    static boolean matches(@Nullable TameworkProjectionIdentityComponent marker,
                           RetirementCommand command) {
        return marker != null
                && marker.matches(
                    TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE,
                    command.operationId(), command.profileId())
                && Objects.equals(marker.getSlotKey(), command.authoritySlotKey())
                && Objects.equals(marker.getSourceNpcUuid(), command.sourceNpcUuid())
                && marker.getGeneration() == command.operationGeneration();
    }

    /**
     * Allows an exact finalized managed release to become the source of a committed recapture.
     * Arbitrary, active, cross-profile, or cross-slot markers remain conflicts.
     */
    static boolean canTransitionFinalizedProjection(
            @Nullable TameworkProjectionIdentityComponent marker,
            @Nonnull RetirementCommand command) {
        return canTransitionFinalizedRelease(marker, command)
                || canTransitionFinalizedImport(marker, command)
                || canTransitionFinalizedRecovery(marker, command);
    }

    private static boolean canTransitionFinalizedRelease(
            @Nullable TameworkProjectionIdentityComponent marker,
            RetirementCommand command) {
        if (marker == null
                || !TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE.equals(
                        marker.getProjectionKind())
                || marker.getOperationId() == null
                || !FINALIZED_RELEASE_OPERATION.matcher(marker.getOperationId()).matches()
                || !Objects.equals(marker.getProfileId(), command.profileId())
                || !Objects.equals(marker.getSlotKey(), command.authoritySlotKey())
                || marker.getSourceNpcUuid() == null
                || marker.getSourceNpcUuid().equals(command.sourceNpcUuid())
                || marker.getGeneration() != 1L) {
            return false;
        }
        return true;
    }

    private static boolean canTransitionFinalizedImport(
            @Nullable TameworkProjectionIdentityComponent marker,
            RetirementCommand command) {
        return marker != null
                && TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION.equals(
                marker.getProjectionKind())
                && marker.getOperationId() != null
                && FINALIZED_IMPORT_OPERATION.matcher(marker.getOperationId()).matches()
                && Objects.equals(marker.getProfileId(), command.profileId())
                && Objects.equals(marker.getSlotKey(), command.authoritySlotKey())
                && Objects.equals(marker.getSourceNpcUuid(), command.sourceNpcUuid())
                && marker.getGeneration() == command.expectedResidentGeneration();
    }

    private static boolean canTransitionFinalizedRecovery(
            @Nullable TameworkProjectionIdentityComponent marker,
            RetirementCommand command) {
        return marker != null
                && TameworkProjectionIdentityComponent.KIND_RECOVERY.equals(
                marker.getProjectionKind())
                && canonicalUuid(marker.getOperationId())
                && Objects.equals(marker.getProfileId(), command.profileId())
                && marker.getSlotKey() == null
                && marker.getSourceNpcUuid() != null
                && !marker.getSourceNpcUuid().equals(command.sourceNpcUuid())
                && marker.getGeneration() == 0L;
    }

    private static boolean canonicalUuid(@Nullable String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Nullable
    private World resolveWorld(String worldName) {
        Universe universe = Universe.get();
        return universe != null ? universe.getWorld(worldName) : null;
    }
}
