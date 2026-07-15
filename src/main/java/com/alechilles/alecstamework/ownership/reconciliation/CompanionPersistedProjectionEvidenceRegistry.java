package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Publishes one content-stable persisted projection scan to restart recovery consumers.
 *
 * <p>The registry starts unsealed and is invalidated before every startup scan. Consumers must
 * fail closed until the matching scan epoch is sealed. Epoch-checked publication prevents an
 * older asynchronous pass from replacing newer evidence after a restart or retry.</p>
 */
public final class CompanionPersistedProjectionEvidenceRegistry {
    public enum State {
        UNSEALED,
        SCANNING,
        SEALED,
        DEGRADED
    }

    /** Currentness of a scan-authoritative absence for one exact durable projection marker. */
    public enum ProjectionStatus {
        UNAVAILABLE,
        OBSERVED,
        STABLE_ABSENT,
        STALE_ABSENT
    }

    /** Operation-specific loaded-marker evidence paired with one sealed persisted scan. */
    public record ProjectionCurrentness(
            @Nonnull ProjectionStatus status,
            long evidenceRevision,
            long loadedIdentityRevision,
            @Nonnull List<LoadedNpcIdentityIndex.LoadedNpcObservation> observations
    ) {
        public ProjectionCurrentness {
            Objects.requireNonNull(status, "status");
            if (evidenceRevision < 0L || loadedIdentityRevision < 0L) {
                throw new IllegalArgumentException("projection revisions must not be negative");
            }
            observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        }

        public boolean stableAbsent() {
            return status == ProjectionStatus.STABLE_ABSENT;
        }
    }

    /** Immutable evidence generation safe to retain across asynchronous recovery continuations. */
    public record Snapshot(
            @Nonnull State state,
            @Nullable String scanEpoch,
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nullable LoadedNpcIdentitySnapshot loadedIdentities,
            long liveEvidenceRevision,
            long revision,
            @Nullable String detail
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(evidenceSet, "evidenceSet");
            if (liveEvidenceRevision < 0L || revision < 0L) {
                throw new IllegalArgumentException("revisions must not be negative");
            }
        }

        public boolean sealed() {
            return state == State.SEALED;
        }
    }

    private Snapshot snapshot = new Snapshot(
            State.UNSEALED,
            null,
            new CompanionPopulationEvidenceSet(List.of()),
            null,
            0L,
            0L,
            "persisted_projection_evidence_not_scanned"
    );

    /** Invalidates prior evidence and opens an exact startup scan epoch. */
    public synchronized void begin(@Nonnull String scanEpoch) {
        String epoch = requireText(scanEpoch, "scanEpoch");
        snapshot = new Snapshot(
                State.SCANNING,
                epoch,
                new CompanionPopulationEvidenceSet(List.of()),
                null,
                0L,
                nextRevision(),
                "persisted_projection_evidence_scan_in_progress"
        );
    }

    /** Publishes evidence only when it belongs to the currently open scan epoch. */
    public boolean publishSealed(
            @Nonnull String scanEpoch,
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            long expectedLoadedIdentityRevision,
            long expectedLiveEvidenceRevision
    ) {
        String epoch = requireText(scanEpoch, "scanEpoch");
        Objects.requireNonNull(evidenceSet, "evidenceSet");
        if (expectedLoadedIdentityRevision < 0L || expectedLiveEvidenceRevision < 0L) {
            return false;
        }
        LoadedNpcIdentityIndex index;
        CompanionLiveEvidenceRevision liveRevision;
        synchronized (this) {
            if (!epoch.equals(snapshot.scanEpoch()) || snapshot.state() != State.SCANNING) {
                return false;
            }
            index = loadedIdentityIndex;
            liveRevision = liveEvidenceRevision;
        }
        if (index == null || liveRevision == null
                || !liveRevision.isCurrent(expectedLiveEvidenceRevision)) {
            return false;
        }
        LoadedNpcIdentitySnapshot loaded = index.snapshot();
        if (!loaded.initializationComplete()
                || loaded.mutationRevision() != expectedLoadedIdentityRevision) {
            return false;
        }
        synchronized (this) {
            if (index != loadedIdentityIndex
                    || liveRevision != liveEvidenceRevision
                    || !liveRevision.isCurrent(expectedLiveEvidenceRevision)
                    || !epoch.equals(snapshot.scanEpoch())
                    || snapshot.state() != State.SCANNING) {
                return false;
            }
            snapshot = new Snapshot(
                    State.SEALED,
                    epoch,
                    evidenceSet,
                    loaded,
                    expectedLiveEvidenceRevision,
                    nextRevision(),
                    null
            );
        }
        return true;
    }

    /** Revokes recovery authority for a failed or content-unstable matching scan. */
    public synchronized boolean degrade(
            @Nonnull String scanEpoch,
            @Nonnull String detail
    ) {
        String epoch = requireText(scanEpoch, "scanEpoch");
        String reason = requireText(detail, "detail");
        if (!epoch.equals(snapshot.scanEpoch())) {
            return false;
        }
        snapshot = new Snapshot(
                State.DEGRADED,
                epoch,
                new CompanionPopulationEvidenceSet(List.of()),
                null,
                0L,
                nextRevision(),
                reason
        );
        return true;
    }

    /** Returns the current immutable generation. */
    @Nonnull
    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Binds the sole all-world loaded identity authority used to seal and revalidate absence.
     */
    public synchronized void bindLoadedIdentityIndex(@Nonnull LoadedNpcIdentityIndex index) {
        LoadedNpcIdentityIndex required = Objects.requireNonNull(index, "index");
        if (loadedIdentityIndex != null && loadedIdentityIndex != required) {
            throw new IllegalStateException("persisted projection registry already has an identity index");
        }
        loadedIdentityIndex = required;
    }

    /** Binds the shared live inventory/NPC evidence fence used by startup reconciliation. */
    public synchronized void bindLiveEvidenceRevision(
            @Nonnull CompanionLiveEvidenceRevision liveRevision) {
        CompanionLiveEvidenceRevision required = Objects.requireNonNull(
                liveRevision, "liveRevision");
        if (liveEvidenceRevision != null && liveEvidenceRevision != required) {
            throw new IllegalStateException("persisted projection registry already has a live fence");
        }
        liveEvidenceRevision = required;
    }

    /**
     * Resolves whether one exact marker was present at seal/current time or remained absent across
     * an unchanged loaded-identity generation.
     */
    @Nonnull
    public ProjectionCurrentness projectionCurrentness(
            @Nonnull LoadedNpcIdentityIndex.ProjectionKey key) {
        Objects.requireNonNull(key, "key");
        Snapshot sealed;
        LoadedNpcIdentityIndex index;
        CompanionLiveEvidenceRevision liveRevision;
        synchronized (this) {
            sealed = snapshot;
            index = loadedIdentityIndex;
            liveRevision = liveEvidenceRevision;
        }
        LoadedNpcIdentitySnapshot baseline = sealed.loadedIdentities();
        if (!sealed.sealed() || index == null || liveRevision == null || baseline == null) {
            return unavailable(sealed.revision(), 0L);
        }
        LoadedNpcIdentitySnapshot current = index.snapshot();
        List<LoadedNpcIdentityIndex.LoadedNpcObservation> observations = new ArrayList<>();
        appendMatches(observations, baseline.observations(), key);
        appendMatches(observations, current.observations(), key);
        if (!observations.isEmpty()) {
            return new ProjectionCurrentness(
                    ProjectionStatus.OBSERVED,
                    sealed.revision(),
                    current.mutationRevision(),
                    observations
            );
        }
        if (!baseline.initializationComplete() || !current.initializationComplete()) {
            return unavailable(sealed.revision(), current.mutationRevision());
        }
        ProjectionStatus status = baseline.mutationRevision() == current.mutationRevision()
                && liveRevision.isCurrent(sealed.liveEvidenceRevision())
                ? ProjectionStatus.STABLE_ABSENT : ProjectionStatus.STALE_ABSENT;
        return new ProjectionCurrentness(
                status,
                sealed.revision(),
                current.mutationRevision(),
                List.of()
        );
    }

    /**
     * Returns current positive loaded evidence for one exact marker without granting absence
     * authority. This remains useful while the persisted scan is unsealed or degraded because a
     * unique exact loaded projection can prove that its own journaled spawn already happened.
     */
    @Nonnull
    public LoadedNpcIdentityIndex.ProjectionProbe loadedProjection(
            @Nonnull LoadedNpcIdentityIndex.ProjectionKey key) {
        Objects.requireNonNull(key, "key");
        LoadedNpcIdentityIndex index;
        synchronized (this) {
            index = loadedIdentityIndex;
        }
        return index == null
                ? new LoadedNpcIdentityIndex.ProjectionProbe(
                        key,
                        LoadedNpcIdentityIndex.ProjectionProbeStatus.UNKNOWN,
                        List.of())
                : index.probeProjection(key);
    }

    /** Confirms both persisted evidence and loaded-marker absence remain on the same generation. */
    public boolean current(long evidenceRevision, long loadedIdentityRevision) {
        Snapshot sealed;
        LoadedNpcIdentityIndex index;
        CompanionLiveEvidenceRevision liveRevision;
        synchronized (this) {
            sealed = snapshot;
            index = loadedIdentityIndex;
            liveRevision = liveEvidenceRevision;
        }
        if (!sealed.sealed() || sealed.revision() != evidenceRevision
                || index == null || liveRevision == null
                || !liveRevision.isCurrent(sealed.liveEvidenceRevision())) {
            return false;
        }
        LoadedNpcIdentitySnapshot current = index.snapshot();
        return current.initializationComplete()
                && current.mutationRevision() == loadedIdentityRevision;
    }

    @Nullable
    private LoadedNpcIdentityIndex loadedIdentityIndex;
    @Nullable
    private CompanionLiveEvidenceRevision liveEvidenceRevision;

    private static void appendMatches(
            List<LoadedNpcIdentityIndex.LoadedNpcObservation> destination,
            List<LoadedNpcIdentityIndex.LoadedNpcObservation> candidates,
            LoadedNpcIdentityIndex.ProjectionKey key) {
        for (LoadedNpcIdentityIndex.LoadedNpcObservation candidate : candidates) {
            if (key.equals(candidate.projectionKey()) && !destination.contains(candidate)) {
                destination.add(candidate);
            }
        }
    }

    private static ProjectionCurrentness unavailable(
            long evidenceRevision, long loadedIdentityRevision) {
        return new ProjectionCurrentness(
                ProjectionStatus.UNAVAILABLE,
                evidenceRevision,
                loadedIdentityRevision,
                List.of()
        );
    }

    private long nextRevision() {
        if (snapshot.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("persisted projection evidence revision exhausted");
        }
        return snapshot.revision() + 1L;
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
