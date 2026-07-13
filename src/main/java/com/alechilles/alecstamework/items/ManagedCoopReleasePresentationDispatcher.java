package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.PresentationCommand;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.PresentationDispatcher;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Queues immutable finalized-release identities and applies deferred projection presentation once.
 *
 * <p>The queue captures no world, store, reference, NPC, or snapshot component. The world, exact
 * DEPLOYED resident, full snapshot, and projection marker are all re-resolved inside the queued
 * owning-world-thread callback. This service never creates, remaps, or replaces an entity.</p>
 */
public final class ManagedCoopReleasePresentationDispatcher implements PresentationDispatcher {
    public enum DispatchStatus {
        QUEUED,
        APPLYING,
        APPLIED,
        REJECTED
    }

    public record DispatchView(@Nonnull DispatchStatus status, @Nullable String detail) {
    }

    enum EvidenceStatus {
        FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    enum LiveStatus {
        FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    record StateEvidence(@Nonnull EvidenceStatus status,
                         @Nullable ResidentRecord resident,
                         @Nullable String detail) {
        static StateEvidence found(ResidentRecord resident) {
            return new StateEvidence(EvidenceStatus.FOUND, resident, null);
        }

        static StateEvidence conflict(String detail) {
            return new StateEvidence(EvidenceStatus.CONFLICT, null, detail);
        }

        static StateEvidence unavailable(String detail) {
            return new StateEvidence(EvidenceStatus.UNAVAILABLE, null, detail);
        }
    }

    record LiveProjection(@Nonnull Ref<EntityStore> reference,
                          @Nonnull NPCEntity npc,
                          @Nonnull Store<EntityStore> store,
                          @Nonnull TameworkProjectionIdentityComponent marker) {
    }

    record LiveResolution(@Nonnull LiveStatus status,
                          @Nullable LiveProjection projection,
                          @Nullable String detail) {
        static LiveResolution found(LiveProjection projection) {
            return new LiveResolution(LiveStatus.FOUND, projection, null);
        }

        static LiveResolution conflict(String detail) {
            return new LiveResolution(LiveStatus.CONFLICT, null, detail);
        }

        static LiveResolution unavailable(String detail) {
            return new LiveResolution(LiveStatus.UNAVAILABLE, null, detail);
        }
    }

    @FunctionalInterface
    interface StateEvidenceGateway {
        @Nonnull
        StateEvidence load(@Nonnull PresentationCommand command);
    }

    @FunctionalInterface
    interface WorldThreadConsumer {
        void accept(@Nonnull PresentationCommand command);
    }

    interface WorldThreadGateway {
        boolean enqueue(@Nonnull PresentationCommand command,
                        @Nonnull WorldThreadConsumer consumer);

        @Nonnull
        LiveResolution resolve(@Nonnull PresentationCommand command);
    }

    @FunctionalInterface
    interface PresentationApplier {
        void apply(@Nonnull PresentationCommand command,
                   @Nonnull LiveProjection projection,
                   @Nonnull CoopResidentStateRestorer.PostAddWork work);
    }

    private final StateEvidenceGateway stateEvidence;
    private final WorldThreadGateway worlds;
    private final CoopResidentStateSnapshotCodec snapshotCodec;
    private final CoopResidentStateRestorer stateRestorer;
    private final PresentationApplier presentationApplier;
    private final ConcurrentMap<String, DispatchEntry> dispatches = new ConcurrentHashMap<>();

    public ManagedCoopReleasePresentationDispatcher(
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex) {
        this(
                new IndexStateEvidence(compositeIndexes, residentIndex, operationIndex),
                new HytaleWorldThreadGateway(),
                new CoopResidentStateSnapshotCodec(),
                new CoopResidentStateRestorer(),
                new HytaleManagedCoopReleasePresentationApplier(
                        new PlannedNpcProjectionPostAddService(), new CoopEffectService())
        );
    }

    ManagedCoopReleasePresentationDispatcher(
            @Nonnull StateEvidenceGateway stateEvidence,
            @Nonnull WorldThreadGateway worlds,
            @Nonnull CoopResidentStateSnapshotCodec snapshotCodec,
            @Nonnull CoopResidentStateRestorer stateRestorer,
            @Nonnull PresentationApplier presentationApplier) {
        this.stateEvidence = Objects.requireNonNull(stateEvidence, "stateEvidence");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.stateRestorer = Objects.requireNonNull(stateRestorer, "stateRestorer");
        this.presentationApplier = Objects.requireNonNull(presentationApplier, "presentationApplier");
    }

    /** Queues one immutable command. Duplicate delivery cannot queue or apply a second time. */
    @Override
    public void dispatch(@Nonnull PresentationCommand command) {
        final DispatchEntry candidate;
        try {
            candidate = new DispatchEntry(validateCommand(command));
        } catch (RuntimeException exception) {
            return;
        }
        DispatchEntry existing = dispatches.putIfAbsent(command.operationId(), candidate);
        if (existing != null) {
            existing.rejectConflictingReplay(command);
            return;
        }
        try {
            if (!worlds.enqueue(command, this::applyOnWorldThread)) {
                candidate.reject("owning_world_queue_unavailable");
            }
        } catch (RuntimeException exception) {
            candidate.reject("owning_world_queue_failed:" + exceptionDetail(exception));
        }
    }

    /** Immutable diagnostic view for tests and admin-level reconciliation telemetry. */
    @Nullable
    public DispatchView view(@Nonnull String operationId) {
        DispatchEntry entry = dispatches.get(operationId);
        return entry != null ? entry.view() : null;
    }

    private void applyOnWorldThread(PresentationCommand command) {
        DispatchEntry entry = dispatches.get(command.operationId());
        if (entry == null || !entry.command.equals(command) || !entry.beginApply()) {
            return;
        }
        try {
            StateEvidence evidence = stateEvidence.load(command);
            if (evidence == null || evidence.status() == null) {
                entry.reject("finalized_state_evidence_missing");
                return;
            }
            if (evidence.status() != EvidenceStatus.FOUND || evidence.resident() == null) {
                entry.reject(detail(evidence.detail(), evidence.status() == EvidenceStatus.CONFLICT
                        ? "finalized_state_conflict" : "finalized_state_unavailable"));
                return;
            }
            CoopResidentStateRestorer.PostAddWork work = deferredWork(
                    command, evidence.resident());
            LiveResolution live = worlds.resolve(command);
            if (live == null || live.status() == null || live.projection() == null) {
                String fallback = live != null && live.status() == LiveStatus.CONFLICT
                        ? "live_projection_conflict" : "live_projection_unavailable";
                entry.reject(detail(live != null ? live.detail() : null, fallback));
                return;
            }
            if (live.status() != LiveStatus.FOUND
                    || !markerMatches(command, live.projection().marker())) {
                entry.reject("live_projection_marker_conflict");
                return;
            }
            presentationApplier.apply(command, live.projection(), work);
            entry.applied();
        } catch (RuntimeException exception) {
            entry.reject("release_presentation_failed:" + exceptionDetail(exception));
        }
    }

    @Nonnull
    private CoopResidentStateRestorer.PostAddWork deferredWork(
            PresentationCommand command,
            ResidentRecord resident) {
        validateDeployedResident(command, resident);
        String snapshotJson = requireTextPreserving(resident.snapshotJson(), "snapshotJson");
        String snapshotHash = requireText(resident.snapshotHash(), "snapshotHash");
        if (!snapshotHash.equals(command.snapshotHash())
                || !snapshotHash.matches("[0-9a-f]{64}")
                || !snapshotHash.equals(
                    ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson))) {
            throw new IllegalArgumentException("deployed resident snapshot hash mismatch");
        }
        CoopResidentStateSnapshotCodec.DecodeResult decoded = snapshotCodec.decode(snapshotJson);
        if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || decoded.snapshot() == null) {
            throw new IllegalArgumentException("deployed resident snapshot decode failed");
        }
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot = decoded.snapshot();
        boolean metadataMatches = command.sourceNpcUuid().equals(snapshot.npcUuid())
                && normalize(command.coopId(), "command.coopId")
                    .equals(normalize(snapshot.coopId(), "snapshot.coopId"))
                && command.residentSlot() == snapshot.residentSlot()
                && !normalize(snapshot.roleId(), "snapshot.roleId").isBlank();
        if (!metadataMatches) {
            throw new IllegalArgumentException("deployed resident snapshot metadata mismatch");
        }
        return stateRestorer.restore((slot, component) -> { }, snapshot, null);
    }

    private void validateDeployedResident(PresentationCommand command, ResidentRecord resident) {
        boolean matches = resident.active()
                && resident.state() == ResidentState.DEPLOYED
                && resident.generation() == command.expectedResidentGeneration() + 2L
                && resident.snapshotVersion()
                    == Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION)
                && command.residentId().equals(resident.residentId())
                && command.profileId().equals(resident.profileId())
                && command.authorityKey().equals(resident.authorityKey())
                && command.coopId().equalsIgnoreCase(resident.coopId())
                && command.residentSlot() == resident.residentSlot()
                && command.sourceNpcUuid().equals(resident.sourceNpcUuid())
                && command.actualTargetUuid().equals(resident.residentUuid())
                && command.actualTargetUuid().equals(resident.deployedNpcUuid());
        if (!matches) {
            throw new IllegalArgumentException("resident is not exact finalized deployment");
        }
    }

    private boolean markerMatches(PresentationCommand command,
                                  TameworkProjectionIdentityComponent marker) {
        return Objects.equals(command.operationId(), marker.getOperationId())
                && Objects.equals(command.profileId(), marker.getProfileId())
                && Objects.equals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                    marker.getProjectionKind())
                && Objects.equals(command.authorityKey().slotKey(command.residentSlot()),
                    marker.getSlotKey())
                && Objects.equals(command.sourceNpcUuid(), marker.getSourceNpcUuid())
                && marker.getGeneration() == 1L;
    }

    @Nonnull
    private PresentationCommand validateCommand(PresentationCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.operationId(), "operationId");
        requireText(command.profileId(), "profileId");
        requireText(command.residentId(), "residentId");
        normalize(command.coopId(), "coopId");
        Objects.requireNonNull(command.authorityKey(), "authorityKey");
        Objects.requireNonNull(command.sourceNpcUuid(), "sourceNpcUuid");
        Objects.requireNonNull(command.plannedTargetUuid(), "plannedTargetUuid");
        Objects.requireNonNull(command.actualTargetUuid(), "actualTargetUuid");
        if (!command.plannedTargetUuid().equals(command.actualTargetUuid())
                || command.residentSlot() < 0 || command.expectedResidentGeneration() < 0L
                || !requireText(command.snapshotHash(), "snapshotHash").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("presentation command identity is invalid");
        }
        return command;
    }

    static final class IndexStateEvidence implements StateEvidenceGateway {
        private final ManagedCoopCompositeIndexRefreshService composite;
        private final ManagedCoopResidentIndex residents;
        private final ManagedCoopLifecycleOperationIndex operations;

        IndexStateEvidence(ManagedCoopCompositeIndexRefreshService composite,
                           ManagedCoopResidentIndex residents,
                           ManagedCoopLifecycleOperationIndex operations) {
            this.composite = Objects.requireNonNull(composite, "compositeIndexes");
            this.residents = Objects.requireNonNull(residents, "residentIndex");
            this.operations = Objects.requireNonNull(operations, "operationIndex");
        }

        @Override
        public StateEvidence load(PresentationCommand command) {
            if (!composite.isTrusted() || !residents.isTrusted() || !operations.isTrusted()) {
                return StateEvidence.unavailable("managed_coop_indexes_untrusted");
            }
            ManagedCoopResidentIndex.Snapshot residentSnapshot = residents.snapshot();
            ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot = operations.snapshot();
            if (!composite.isTrusted() || !residents.isTrusted() || !operationSnapshot.trusted()) {
                return StateEvidence.unavailable("managed_coop_index_trust_changed");
            }
            boolean activeOperation = operationSnapshot.operationById(command.operationId()) != null
                    || operationSnapshot.operationByProfile(command.profileId()) != null
                    || operationSnapshot.operationAt(
                        command.authorityKey(), command.residentSlot()) != null
                    || operationSnapshot.operationByUuid(command.actualTargetUuid()) != null;
            if (activeOperation) {
                return StateEvidence.conflict("release_operation_not_finalized");
            }
            ResidentRecord resident = residentSnapshot.residentByProfile(command.profileId());
            return resident != null
                    ? StateEvidence.found(resident)
                    : StateEvidence.unavailable("deployed_resident_not_indexed");
        }
    }

    private static final class HytaleWorldThreadGateway implements WorldThreadGateway {
        @Override
        public boolean enqueue(PresentationCommand command, WorldThreadConsumer consumer) {
            World world = resolveWorld(command.authorityKey().worldName());
            if (world == null) {
                return false;
            }
            world.execute(() -> consumer.accept(command));
            return true;
        }

        @Override
        public LiveResolution resolve(PresentationCommand command) {
            World world = resolveWorld(command.authorityKey().worldName());
            if (world == null || world.getEntityStore() == null) {
                return LiveResolution.unavailable("owning_world_or_store_unavailable");
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> reference = world.getEntityRef(command.actualTargetUuid());
            if (store == null || reference == null || !reference.isValid()) {
                return LiveResolution.unavailable("deployed_projection_not_loaded");
            }
            store.assertThread();
            ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                    TameworkProjectionIdentityComponent.getComponentType();
            if (npcType == null || markerType == null) {
                return LiveResolution.unavailable("projection_components_unavailable");
            }
            NPCEntity npc = store.getComponent(reference, npcType);
            if (npc == null) {
                return LiveResolution.conflict("deployed_projection_is_not_npc");
            }
            TameworkProjectionIdentityComponent marker = store.getComponent(reference, markerType);
            return marker != null
                    ? LiveResolution.found(new LiveProjection(
                        reference, npc, store, marker.clone()))
                    : LiveResolution.conflict("projection_marker_missing");
        }

        @Nullable
        private static World resolveWorld(String worldName) {
            Universe universe = Universe.get();
            return universe != null ? universe.getWorld(worldName) : null;
        }
    }

    private static final class DispatchEntry {
        private final PresentationCommand command;
        private final AtomicReference<DispatchView> state =
                new AtomicReference<>(new DispatchView(DispatchStatus.QUEUED, null));

        private DispatchEntry(PresentationCommand command) {
            this.command = command;
        }

        private boolean beginApply() {
            DispatchView current = state.get();
            return current.status() == DispatchStatus.QUEUED
                    && state.compareAndSet(
                        current, new DispatchView(DispatchStatus.APPLYING, null));
        }

        private void applied() {
            replace(DispatchStatus.APPLYING, DispatchStatus.APPLIED, null);
        }

        private void reject(String failure) {
            while (true) {
                DispatchView current = state.get();
                if (current.status() == DispatchStatus.APPLIED
                        || current.status() == DispatchStatus.REJECTED) {
                    return;
                }
                if (state.compareAndSet(
                        current, new DispatchView(DispatchStatus.REJECTED, failure))) {
                    return;
                }
            }
        }

        private void rejectConflictingReplay(PresentationCommand replay) {
            if (!command.equals(replay)) {
                replace(DispatchStatus.QUEUED, DispatchStatus.REJECTED,
                        "presentation_operation_identity_conflict");
            }
        }

        private DispatchView view() {
            return state.get();
        }

        private void replace(DispatchStatus expected,
                             DispatchStatus replacement,
                             @Nullable String detail) {
            while (true) {
                DispatchView current = state.get();
                if (current.status() != expected) {
                    return;
                }
                if (state.compareAndSet(current, new DispatchView(replacement, detail))) {
                    return;
                }
            }
        }
    }

    @Nonnull
    private String normalize(@Nullable String value, String field) {
        return requireText(value, field).toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    private static String requireTextPreserving(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Nonnull
    private static String detail(@Nullable String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    @Nonnull
    private static String exceptionDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
