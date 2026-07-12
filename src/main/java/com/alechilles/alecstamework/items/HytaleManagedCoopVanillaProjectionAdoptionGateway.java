package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Hytale 0.5.6 owning-world adapter for adopting an existing vanilla coop resident in place.
 *
 * <p>Vanilla has no public operation that transfers a deployed resident to another occupancy
 * authority without despawning it. This minimal Java seam therefore proves one exact live UUID,
 * installs a persistent Tamework marker, and removes only {@link CoopResidentComponent}. It never
 * creates, removes, spawns, or marks the NPC for despawn.</p>
 */
final class HytaleManagedCoopVanillaProjectionAdoptionGateway
        implements ManagedCoopVanillaProjectionAdoptionGateway {
    private static final Pattern IMPORT_OPERATION_ID = Pattern.compile(
            "managed-coop-import-operation:[0-9a-f]{64}");

    private final CoopResidentStateSnapshotService snapshots;
    private final CoopResidentStateSnapshotCodec snapshotCodec =
            new CoopResidentStateSnapshotCodec();
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType;
    private final ManagedCoopVanillaProjectionIdentityGuard identityGuard;
    private final ConcurrentHashMap<String, PendingAdoption> pending = new ConcurrentHashMap<>();

    HytaleManagedCoopVanillaProjectionAdoptionGateway(
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType,
            @Nonnull NpcIdentityRepository identities,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.markerType = Objects.requireNonNull(markerType, "markerType");
        this.identityGuard = new ManagedCoopVanillaProjectionIdentityGuard(
                identities, loadedIdentities);
    }

    @Nonnull
    @Override
    public InspectionResult inspect(@Nonnull InspectionRequest request) {
        Objects.requireNonNull(request, "request");
        final Resolution resolution;
        try {
            resolution = resolve(request.authorityKey().worldName(), request.sourceNpcUuid());
        } catch (RuntimeException | AssertionError exception) {
            return InspectionResult.unavailable("deployed_projection_inspection_failed:"
                    + detail(exception));
        }
        if (!resolution.resolved()) {
            return inspectionFailure(resolution);
        }
        ManagedCoopVanillaProjectionIdentityGuard.Result identity = identityGuard.verify(
                request.profileId(), request.sourceNpcUuid(),
                request.authorityKey(), request.managedResidentSlot());
        if (!identity.isVerified()) {
            return identity.status() == ManagedCoopVanillaProjectionIdentityGuard.Status.CONFLICT
                    ? InspectionResult.conflict(identity.detail(), null)
                    : InspectionResult.unavailable(identity.detail());
        }
        String identityFailure = validateLiveIdentity(
                resolution, request.authorityKey().x(), request.authorityKey().y(),
                request.authorityKey().z(), request.roleId(), true);
        if (identityFailure != null) {
            return InspectionResult.conflict(identityFailure, null);
        }
        if (resolution.marker() != null) {
            return InspectionResult.conflict(
                    "deployed_projection_marker_already_present",
                    "pre_import_projection_must_not_have_a_tamework_marker");
        }
        try {
            CoopResidentStateSnapshot snapshot = snapshots.captureSnapshotForManagedCoopPersistence(
                    resolution.reference(), resolution.store(), request.sourceNpcUuid(),
                    request.coopId(), request.managedResidentSlot(), request.roleId());
            if (!validSnapshot(snapshot, request)) {
                return InspectionResult.unavailable("deployed_projection_snapshot_unavailable");
            }
            String snapshotJson = snapshotCodec.encode(snapshot);
            return InspectionResult.verified(
                    snapshotJson,
                    ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson),
                    Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION));
        } catch (RuntimeException | LinkageError exception) {
            return InspectionResult.unavailable("deployed_projection_snapshot_failed:"
                    + detail(exception));
        }
    }

    @Nonnull
    @Override
    public AdoptionResult adopt(@Nonnull AdoptionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!IMPORT_OPERATION_ID.matcher(request.operationId()).matches()) {
            return AdoptionResult.conflict("deployed_projection_import_operation_id_invalid");
        }
        PendingAdoption existing = pending.get(request.operationId());
        if (existing != null) {
            return settlePending(request, existing);
        }
        World world = resolveWorld(request.authorityKey().worldName());
        if (world == null) {
            return AdoptionResult.unavailable("deployed_projection_world_unavailable");
        }
        PendingAdoption created = new PendingAdoption(request, new CompletableFuture<>());
        existing = pending.putIfAbsent(request.operationId(), created);
        if (existing != null) {
            return settlePending(request, existing);
        }
        try {
            world.execute(() -> completeOnWorldThread(created));
            return AdoptionResult.pending();
        } catch (RuntimeException | LinkageError exception) {
            pending.remove(request.operationId(), created);
            return AdoptionResult.unavailable("deployed_projection_adoption_enqueue_failed:"
                    + detail(exception));
        }
    }

    private void completeOnWorldThread(PendingAdoption pendingAdoption) {
        AdoptionResult result;
        try {
            result = adoptOnWorldThread(pendingAdoption.request());
        } catch (RuntimeException | AssertionError | LinkageError exception) {
            result = AdoptionResult.unavailable("deployed_projection_adoption_failed:"
                    + detail(exception));
        }
        pendingAdoption.completion().complete(result);
    }

    @Nonnull
    private AdoptionResult adoptOnWorldThread(AdoptionRequest request) {
        Resolution resolution = resolve(
                request.authorityKey().worldName(), request.sourceNpcUuid());
        if (!resolution.resolved()) {
            return adoptionFailure(resolution);
        }
        ManagedCoopVanillaProjectionIdentityGuard.Result identity = identityGuard.verify(
                request.profileId(), request.sourceNpcUuid(),
                request.authorityKey(), request.residentSlot());
        if (!identity.isVerified()) {
            return identity.status() == ManagedCoopVanillaProjectionIdentityGuard.Status.CONFLICT
                    ? AdoptionResult.conflict(identity.detail())
                    : AdoptionResult.unavailable(identity.detail());
        }
        String identityFailure = validateLiveIdentity(
                resolution, request.authorityKey().x(), request.authorityKey().y(),
                request.authorityKey().z(), null, false);
        if (identityFailure != null) {
            return AdoptionResult.conflict(identityFailure);
        }
        TameworkProjectionIdentityComponent expected = marker(request);
        TameworkProjectionIdentityComponent existing = resolution.marker();
        if (existing != null && !matches(existing, request)) {
            return AdoptionResult.conflict("deployed_projection_marker_conflict");
        }
        CoopResidentComponent vanilla = resolution.vanillaResident();
        if (vanilla == null) {
            return matches(existing, request)
                    ? AdoptionResult.alreadyAdopted()
                    : AdoptionResult.conflict("deployed_projection_detached_without_adoption_marker");
        }
        try {
            if (existing == null) {
                resolution.store().putComponent(resolution.reference(), markerType, expected);
            }
            TameworkProjectionIdentityComponent installed = resolution.store().getComponent(
                    resolution.reference(), markerType);
            if (!matches(installed, request)) {
                return AdoptionResult.unavailable("deployed_projection_marker_not_installed");
            }
            resolution.store().removeComponent(
                    resolution.reference(), CoopResidentComponent.getComponentType());
            if (!postAdoptionMatches(resolution, request)) {
                return AdoptionResult.unavailable("deployed_projection_detachment_not_observed");
            }
            return AdoptionResult.adopted();
        } catch (RuntimeException | LinkageError exception) {
            return AdoptionResult.unavailable("deployed_projection_adoption_mutation_failed:"
                    + detail(exception));
        }
    }

    @Nonnull
    private AdoptionResult settlePending(AdoptionRequest request,
                                         PendingAdoption pendingAdoption) {
        if (!pendingAdoption.request().equals(request)) {
            return AdoptionResult.conflict("deployed_projection_pending_request_conflict");
        }
        if (!pendingAdoption.completion().isDone()) {
            return AdoptionResult.pending();
        }
        final AdoptionResult result;
        try {
            result = pendingAdoption.completion().join();
        } catch (RuntimeException exception) {
            pending.remove(request.operationId(), pendingAdoption);
            return AdoptionResult.unavailable("deployed_projection_adoption_completion_failed:"
                    + detail(exception));
        }
        pending.remove(request.operationId(), pendingAdoption);
        return result == null
                ? AdoptionResult.unavailable("deployed_projection_adoption_result_missing")
                : result;
    }

    @Nonnull
    static TameworkProjectionIdentityComponent marker(@Nonnull AdoptionRequest request) {
        return new TameworkProjectionIdentityComponent(
                request.profileId(),
                request.operationId(),
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                request.authoritySlotKey(),
                request.sourceNpcUuid(),
                request.residentGeneration());
    }

    static boolean matches(@Nullable TameworkProjectionIdentityComponent marker,
                           @Nonnull AdoptionRequest request) {
        return marker != null
                && marker.matches(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                request.operationId(), request.profileId())
                && Objects.equals(marker.getSlotKey(), request.authoritySlotKey())
                && Objects.equals(marker.getSourceNpcUuid(), request.sourceNpcUuid())
                && marker.getGeneration() == request.residentGeneration();
    }

    private Resolution resolve(String worldName, UUID sourceUuid) {
        World world = resolveWorld(worldName);
        Store<EntityStore> store = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
        if (world == null || store == null || world.getName() == null
                || !world.getName().equalsIgnoreCase(worldName)) {
            return Resolution.unavailable("deployed_projection_world_or_store_unavailable");
        }
        store.assertThread();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        ComponentType<EntityStore, CoopResidentComponent> vanillaType =
                CoopResidentComponent.getComponentType();
        if (npcType == null || uuidType == null || vanillaType == null) {
            return Resolution.unavailable("deployed_projection_component_type_unavailable");
        }
        List<Ref<EntityStore>> matches = findUuidMatches(store, sourceUuid, npcType, uuidType);
        if (matches.isEmpty()) {
            return Resolution.unavailable("deployed_projection_not_loaded");
        }
        if (matches.size() != 1) {
            return Resolution.conflict("deployed_projection_uuid_ambiguous");
        }
        Ref<EntityStore> reference = matches.getFirst();
        Ref<EntityStore> external = world.getEntityRef(sourceUuid);
        if (!sameReference(reference, external)) {
            return Resolution.conflict("deployed_projection_external_uuid_mapping_mismatch");
        }
        return Resolution.resolved(
                sourceUuid,
                store,
                reference,
                store.getComponent(reference, npcType),
                store.getComponent(reference, uuidType),
                store.getComponent(reference, vanillaType),
                store.getComponent(reference, markerType));
    }

    @Nullable
    private World resolveWorld(String worldName) {
        Universe universe = Universe.get();
        return universe != null ? universe.getWorld(worldName) : null;
    }

    private List<Ref<EntityStore>> findUuidMatches(
            Store<EntityStore> store,
            UUID sourceUuid,
            ComponentType<EntityStore, NPCEntity> npcType,
            ComponentType<EntityStore, UUIDComponent> uuidType) {
        ArrayList<Ref<EntityStore>> matches = new ArrayList<>(2);
        store.forEachChunk(
                Query.and(npcType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        NPCEntity npc = chunk.getComponent(index, npcType);
                        UUIDComponent uuid = chunk.getComponent(index, uuidType);
                        if (npc != null && (sourceUuid.equals(npc.getUuid())
                                || uuid != null && sourceUuid.equals(uuid.getUuid()))) {
                            matches.add(chunk.getReferenceTo(index));
                        }
                    }
                });
        return List.copyOf(matches);
    }

    @Nullable
    private String validateLiveIdentity(Resolution resolution,
                                        int x,
                                        int y,
                                        int z,
                                        @Nullable String expectedRole,
                                        boolean requireVanillaResident) {
        UUIDComponent uuid = resolution.uuid();
        NPCEntity npc = resolution.npc();
        if (uuid == null || !resolution.expectedSourceUuid().equals(uuid.getUuid())
                || npc == null || !resolution.expectedSourceUuid().equals(npc.getUuid())) {
            return "deployed_projection_uuid_identity_mismatch";
        }
        if (npc.isDespawning()) {
            return "deployed_projection_is_despawning";
        }
        if (expectedRole != null && (npc.getRoleName() == null
                || !expectedRole.equals(npc.getRoleName().trim().toLowerCase(Locale.ROOT)))) {
            return "deployed_projection_role_mismatch";
        }
        CoopResidentComponent resident = resolution.vanillaResident();
        if (resident == null) {
            return requireVanillaResident
                    ? "deployed_projection_vanilla_component_missing" : null;
        }
        Vector3i location = resident.getCoopLocation();
        if (location == null || location.x != x || location.y != y || location.z != z) {
            return "deployed_projection_coop_location_mismatch";
        }
        return resident.getMarkedForDespawn()
                ? "deployed_projection_marked_for_despawn" : null;
    }

    private boolean validSnapshot(@Nullable CoopResidentStateSnapshot snapshot,
                                  InspectionRequest request) {
        return snapshot != null
                && request.sourceNpcUuid().equals(snapshot.npcUuid())
                && request.coopId().equalsIgnoreCase(snapshot.coopId())
                && request.managedResidentSlot() == snapshot.residentSlot()
                && request.roleId().equalsIgnoreCase(snapshot.roleId())
                && snapshot.capturedAtMs() != 0L;
    }

    private boolean postAdoptionMatches(Resolution resolution, AdoptionRequest request) {
        if (resolution.reference() == null || !resolution.reference().isValid()) {
            return false;
        }
        UUIDComponent uuid = resolution.store().getComponent(
                resolution.reference(), UUIDComponent.getComponentType());
        NPCEntity npc = resolution.store().getComponent(
                resolution.reference(), NPCEntity.getComponentType());
        return uuid != null && request.sourceNpcUuid().equals(uuid.getUuid())
                && npc != null && request.sourceNpcUuid().equals(npc.getUuid())
                && !npc.isDespawning()
                && resolution.store().getComponent(
                resolution.reference(), CoopResidentComponent.getComponentType()) == null
                && matches(resolution.store().getComponent(
                resolution.reference(), markerType), request);
    }

    private InspectionResult inspectionFailure(Resolution resolution) {
        return resolution.conflict()
                ? InspectionResult.conflict(resolution.detail(), null)
                : InspectionResult.unavailable(resolution.detail());
    }

    private AdoptionResult adoptionFailure(Resolution resolution) {
        return resolution.conflict()
                ? AdoptionResult.conflict(resolution.detail())
                : AdoptionResult.unavailable(resolution.detail());
    }

    private boolean sameReference(@Nullable Ref<EntityStore> left,
                                  @Nullable Ref<EntityStore> right) {
        return left != null && right != null && left.isValid() && right.isValid()
                && left.getStore() == right.getStore() && left.getIndex() == right.getIndex();
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record Resolution(@Nullable UUID expectedSourceUuid,
                              @Nullable Store<EntityStore> store,
                              @Nullable Ref<EntityStore> reference,
                              @Nullable NPCEntity npc,
                              @Nullable UUIDComponent uuid,
                              @Nullable CoopResidentComponent vanillaResident,
                              @Nullable TameworkProjectionIdentityComponent marker,
                              @Nullable String detail,
                              boolean conflict) {
        private static Resolution resolved(
                UUID expectedSourceUuid,
                Store<EntityStore> store,
                Ref<EntityStore> reference,
                NPCEntity npc,
                UUIDComponent uuid,
                CoopResidentComponent vanillaResident,
                TameworkProjectionIdentityComponent marker) {
            return new Resolution(
                    expectedSourceUuid, store, reference, npc, uuid,
                    vanillaResident, marker, null, false);
        }

        private static Resolution unavailable(String detail) {
            return new Resolution(
                    null, null, null, null, null, null, null, detail, false);
        }

        private static Resolution conflict(String detail) {
            return new Resolution(
                    null, null, null, null, null, null, null, detail, true);
        }

        private boolean resolved() {
            return store != null && reference != null;
        }

    }

    private record PendingAdoption(@Nonnull AdoptionRequest request,
                                   @Nonnull CompletableFuture<AdoptionResult> completion) {
    }
}
