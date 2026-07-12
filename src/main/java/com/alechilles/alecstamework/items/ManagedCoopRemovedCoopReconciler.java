package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchOutcome;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSite;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeSweepOrchestrator.RemovedCoopReconciler;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Confirms physical coop removal, durably disables its v5 authority, then releases housed slots.
 *
 * <p>All physical evidence is consumed synchronously on the chunk-store thread. Persistence
 * continuations retain only immutable authority/resident/site values and always enter release
 * through {@link ManagedCoopRuntimeOperationDispatcher}; vanilla occupancy is never read or
 * mutated.</p>
 */
public final class ManagedCoopRemovedCoopReconciler implements RemovedCoopReconciler {
    private static final String REMOVAL_MARKER = "managed_coop_block_confirmed_removed";

    private final ManagedCoopResidentIndex residents;
    private final ManagedCoopLifecycleOperationIndex operations;
    private final BooleanSupplier compositeTrust;
    private final HytaleManagedCoopRemovalEvidenceReader physicalReader;
    private final AuthorityTransitionGateway authorityTransitions;
    private final RefreshGateway refresh;
    private final ReleaseGateway releases;
    private final ConcurrentHashMap<String, Boolean> authorityTransitionsInFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> releasesInFlight =
            new ConcurrentHashMap<>();

    public ManagedCoopRemovedCoopReconciler(
            @Nonnull ManagedCoopResidentRepository repository,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopRuntimeOperationDispatcher dispatcher) {
        this(
                residentIndex,
                operationIndex,
                compositeIndexes::isTrusted,
                new HytaleManagedCoopRemovalEvidenceReader(),
                (key, nowMs) -> committed(repository.transitionAuthority(
                        key, AuthorityState.TWORK_MANAGED, AuthorityState.DISABLED,
                        REMOVAL_MARKER, nowMs)),
                () -> {
                    ManagedCoopCompositeIndexRefreshService.RefreshResult result =
                            compositeIndexes.refresh();
                    return result != null && result.refreshed() && compositeIndexes.isTrusted();
                },
                dispatcher::release);
    }

    ManagedCoopRemovedCoopReconciler(
            @Nonnull ManagedCoopResidentIndex residents,
            @Nonnull ManagedCoopLifecycleOperationIndex operations,
            @Nonnull BooleanSupplier compositeTrust,
            @Nonnull HytaleManagedCoopRemovalEvidenceReader physicalReader,
            @Nonnull AuthorityTransitionGateway authorityTransitions,
            @Nonnull RefreshGateway refresh,
            @Nonnull ReleaseGateway releases) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
        this.physicalReader = Objects.requireNonNull(physicalReader, "physicalReader");
        this.authorityTransitions = Objects.requireNonNull(
                authorityTransitions, "authorityTransitions");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.releases = Objects.requireNonNull(releases, "releases");
    }

    /** Consumes live chunk/store evidence before starting any persistence continuation. */
    @Override
    public void reconcile(@Nonnull Store<ChunkStore> chunkStore,
                          @Nonnull World world,
                          @Nonnull Set<String> activeCoopKeys,
                          long nowMs) {
        Objects.requireNonNull(chunkStore, "chunkStore");
        Objects.requireNonNull(world, "world");
        chunkStore.assertThread();
        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) {
            return;
        }
        reconcileSnapshot(worldName, activeCoopKeys, nowMs,
                authority -> physicalReader.inspect(
                        chunkStore, world, authority.authorityKey(), authority.coopId()));
    }

    /** Package-visible pure orchestration seam for deterministic removal/restart tests. */
    void reconcileSnapshot(@Nonnull String worldName,
                           @Nonnull Set<String> activeCoopKeys,
                           long nowMs,
                           @Nonnull EvidenceGateway evidence) {
        Objects.requireNonNull(activeCoopKeys, "activeCoopKeys");
        Objects.requireNonNull(evidence, "evidence");
        SnapshotPair pair = coherentSnapshots();
        if (pair == null) {
            return;
        }
        String normalizedWorld = normalize(worldName);
        for (AuthorityRecord authority : pair.residents().authorities()) {
            if (authority == null || !authority.active()
                    || !authority.authorityKey().worldName().equals(normalizedWorld)
                    || authority.state() != AuthorityState.TWORK_MANAGED
                    && authority.state() != AuthorityState.DISABLED) {
                continue;
            }
            if (authority.state() == AuthorityState.TWORK_MANAGED
                    && activeCoopKeys.contains(coopKey(authority))) {
                continue;
            }
            ManagedCoopRemovalEvidence.Result physical;
            try {
                physical = evidence.inspect(authority);
            } catch (RuntimeException exception) {
                continue;
            }
            if (authority.state() == AuthorityState.TWORK_MANAGED) {
                if (physical != null && physical.confirmedRemoved()) {
                    disableBeforeRelease(authority, nowMs);
                }
            } else if (physical != null && physical.permitsDisabledRelease()) {
                dispatchDisabled(authority, nowMs);
            }
        }
    }

    private void disableBeforeRelease(AuthorityRecord authority, long nowMs) {
        String key = authority.authorityId();
        if (authorityTransitionsInFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        final CompletionStage<MutationResult> transition;
        try {
            transition = authorityTransitions.disable(authority.authorityKey(), nowMs);
        } catch (RuntimeException exception) {
            authorityTransitionsInFlight.remove(key);
            return;
        }
        if (transition == null) {
            authorityTransitionsInFlight.remove(key);
            return;
        }
        transition.handle((result, failure) -> {
            if (failure == null && result != null && result.succeeded() && refreshSafely()) {
                AuthorityRecord disabled = currentDisabledAuthority(
                        authority.authorityKey(), authority.coopId());
                if (disabled != null) {
                    dispatchDisabled(disabled, nowMs);
                }
            }
            return null;
        }).whenComplete((ignored, failure) -> authorityTransitionsInFlight.remove(key));
    }

    private void dispatchDisabled(AuthorityRecord authority, long nowMs) {
        SnapshotPair pair = coherentSnapshots();
        if (pair == null) {
            return;
        }
        AuthorityRecord current = pair.residents().authority(
                authority.authorityKey(), authority.coopId());
        if (!isSameDisabled(authority, current)) {
            return;
        }
        final ReleaseSite site;
        try {
            site = ReleaseSite.copyOfDisabled(current);
        } catch (RuntimeException exception) {
            return;
        }
        List<ResidentRecord> currentResidents = pair.residents().residents(
                authority.authorityKey());
        for (ResidentRecord resident : currentResidents) {
            if (resident == null || !resident.active()
                    || resident.state() != ResidentState.HOUSED
                    || !resident.coopId().equalsIgnoreCase(authority.coopId())
                    || pair.operations().operationAt(
                            authority.authorityKey(), resident.residentSlot()) != null) {
                continue;
            }
            dispatchResident(site, resident, nowMs);
        }
    }

    private void dispatchResident(ReleaseSite site, ResidentRecord resident, long nowMs) {
        String key = resident.authorityKey().slotKey(resident.residentSlot());
        if (releasesInFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        final CompletionStage<DispatchOutcome> release;
        try {
            release = releases.release(site, resident, nowMs);
        } catch (RuntimeException exception) {
            releasesInFlight.remove(key);
            return;
        }
        if (release == null) {
            releasesInFlight.remove(key);
            return;
        }
        release.whenComplete((ignored, failure) -> releasesInFlight.remove(key));
    }

    @Nullable
    private AuthorityRecord currentDisabledAuthority(ManagedCoopAuthorityKey key, String coopId) {
        SnapshotPair pair = coherentSnapshots();
        AuthorityRecord authority = pair != null
                ? pair.residents().authority(key, coopId) : null;
        return authority != null && authority.state() == AuthorityState.DISABLED
                ? authority : null;
    }

    private boolean refreshSafely() {
        try {
            return refresh.refresh();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Nullable
    private SnapshotPair coherentSnapshots() {
        if (!trusted()) {
            return null;
        }
        ManagedCoopResidentIndex.Snapshot residentSnapshot = residents.snapshot();
        ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot = operations.snapshot();
        return trusted()
                && operationSnapshot.trusted()
                && residents.snapshot().revision() == residentSnapshot.revision()
                && operations.snapshot().revision() == operationSnapshot.revision()
                ? new SnapshotPair(residentSnapshot, operationSnapshot) : null;
    }

    private boolean trusted() {
        try {
            return compositeTrust.getAsBoolean() && residents.isTrusted() && operations.isTrusted();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isSameDisabled(AuthorityRecord expected, @Nullable AuthorityRecord current) {
        return current != null && current.active() && current.state() == AuthorityState.DISABLED
                && current.authorityId().equals(expected.authorityId())
                && current.authorityKey().equals(expected.authorityKey())
                && current.coopId().equalsIgnoreCase(expected.coopId());
    }

    @Nonnull
    private String coopKey(AuthorityRecord authority) {
        return authority.authorityKey().authorityId()
                + "|coop=" + normalize(authority.coopId());
    }

    @Nonnull
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static CompletionStage<MutationResult> committed(
            @Nullable PersistenceWriteQueue.WriteSubmission<MutationResult> submission) {
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("managed_coop_disable_submission_missing"));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        outcome != null && outcome.failureReason() != null
                                ? outcome.failureReason()
                                : "managed_coop_disable_not_committed"));
            }
            return CompletableFuture.completedFuture(outcome.value());
        });
    }

    private record SnapshotPair(ManagedCoopResidentIndex.Snapshot residents,
                                ManagedCoopLifecycleOperationIndex.Snapshot operations) {
    }

    @FunctionalInterface
    interface EvidenceGateway {
        @Nullable
        ManagedCoopRemovalEvidence.Result inspect(@Nonnull AuthorityRecord authority);
    }

    @FunctionalInterface
    interface AuthorityTransitionGateway {
        @Nullable
        CompletionStage<MutationResult> disable(
                @Nonnull ManagedCoopAuthorityKey authorityKey, long nowMs);
    }

    @FunctionalInterface
    interface RefreshGateway {
        boolean refresh();
    }

    @FunctionalInterface
    interface ReleaseGateway {
        @Nullable
        CompletionStage<DispatchOutcome> release(
                @Nonnull ReleaseSite site,
                @Nonnull ResidentRecord resident,
                long requestedAtMs);
    }
}
