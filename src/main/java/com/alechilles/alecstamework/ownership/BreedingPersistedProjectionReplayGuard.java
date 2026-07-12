package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry.ProjectionCurrentness;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry.ProjectionStatus;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionEvidence;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Prevents a terminal RETRYABLE breeding row from respawning a persisted child projection. */
final class BreedingPersistedProjectionReplayGuard {
    enum Status {
        CLEAR,
        COMMITTED_BY_EVIDENCE,
        BLOCKED
    }

    /** Generation token proving an exact loaded marker remained absent while replay was admitted. */
    record ReplayToken(long evidenceRevision, long loadedIdentityRevision) {
        ReplayToken {
            if (evidenceRevision < 0L || loadedIdentityRevision < 0L) {
                throw new IllegalArgumentException("replay revisions must not be negative");
            }
        }
    }

    record Decision(
            @Nonnull Status status,
            @Nullable ReplayToken replayToken,
            @Nullable String detail) {
        Decision {
            Objects.requireNonNull(status, "status");
            if ((status == Status.CLEAR) != (replayToken != null)) {
                throw new IllegalArgumentException("only clear replay decisions carry a token");
            }
        }

        static Decision allowed() {
            return allowed(new ReplayToken(0L, 0L));
        }

        static Decision allowed(ReplayToken replayToken) {
            return new Decision(
                    Status.CLEAR, Objects.requireNonNull(replayToken, "replayToken"), null);
        }

        static Decision committed() {
            return new Decision(Status.COMMITTED_BY_EVIDENCE, null, null);
        }

        static Decision blocked(String detail) {
            return new Decision(
                    Status.BLOCKED, null, Objects.requireNonNull(detail, "detail"));
        }
    }

    @Nullable
    private final CompanionPersistedProjectionEvidenceRegistry registry;

    /** Compatibility guard for isolated replay tests that do not model startup scanning. */
    BreedingPersistedProjectionReplayGuard() {
        this.registry = null;
    }

    BreedingPersistedProjectionReplayGuard(
            @Nonnull CompanionPersistedProjectionEvidenceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    boolean ready() {
        return registry == null || registry.snapshot().sealed();
    }

    boolean current(@Nullable ReplayToken replayToken) {
        return registry == null || replayToken == null
                || registry.current(
                        replayToken.evidenceRevision(), replayToken.loadedIdentityRevision());
    }

    @Nonnull
    Decision inspect(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull BreedingPopulationReplayTargetCodec.Target target) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(target, "target");
        if (registry == null) {
            return Decision.allowed();
        }
        CompanionPersistedProjectionEvidenceRegistry.Snapshot sealed = registry.snapshot();
        if (!sealed.sealed()) {
            return Decision.blocked("breeding-replay-persisted-evidence-unsealed");
        }
        try {
            LoadedNpcIdentityIndex.ProjectionKey projectionKey =
                    new LoadedNpcIdentityIndex.ProjectionKey(
                            operation.profileId(),
                            target.attemptKey(),
                            TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                            target.childKey(),
                            target.plannedNpcUuid(),
                            1L);
            ProjectionCurrentness currentness = registry.projectionCurrentness(projectionKey);
            if (currentness.evidenceRevision() != sealed.revision()
                    || currentness.status() == ProjectionStatus.UNAVAILABLE) {
                return Decision.blocked("breeding-replay-loaded-projection-evidence-unavailable");
            }
            String fingerprint = CompanionProjectionEvidence.fingerprint(
                    operation.profileId(),
                    target.attemptKey(),
                    TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                    target.childKey(),
                    target.plannedNpcUuid(),
                    1L);
            List<CompanionPopulationEvidenceSet.ProjectionObservation> observations =
                    sealed.evidenceSet().projectionObservations(fingerprint);
            if (observations.isEmpty()) {
                if (currentness.status() == ProjectionStatus.OBSERVED) {
                    return Decision.blocked("breeding-replay-loaded-projection-observed");
                }
                if (hasOrdinaryEvidence(
                        sealed.evidenceSet(), target.plannedNpcUuid())) {
                    return Decision.blocked(
                            "breeding-replay-ordinary-evidence-without-projection-marker");
                }
                return currentness.stableAbsent()
                        ? Decision.allowed(new ReplayToken(
                                sealed.revision(), currentness.loadedIdentityRevision()))
                        : Decision.blocked("breeding-replay-loaded-projection-absence-stale");
            }
            if (observations.size() != 1) {
                return Decision.blocked("breeding-replay-persisted-projection-duplicated");
            }
            Decision persisted = validateExact(
                    operation, target, sealed.evidenceSet(), observations.getFirst());
            if (persisted.status() != Status.COMMITTED_BY_EVIDENCE
                    || currentness.status() != ProjectionStatus.OBSERVED) {
                return persisted;
            }
            return loadedObservationAgrees(
                    currentness, projectionKey, target.plannedNpcUuid())
                    ? persisted
                    : Decision.blocked("breeding-replay-loaded-projection-conflict");
        } catch (RuntimeException exception) {
            return Decision.blocked("breeding-replay-persisted-projection-invalid");
        }
    }

    private boolean hasOrdinaryEvidence(
            CompanionPopulationEvidenceSet evidenceSet,
            UUID plannedNpcUuid) {
        if (!evidenceSet.observations(plannedNpcUuid).isEmpty()
                || evidenceSet.byNpcUuid().containsKey(plannedNpcUuid)) {
            return true;
        }
        for (CompanionPopulationEvidenceSet.Conflict conflict : evidenceSet.conflicts()) {
            if (plannedNpcUuid.equals(conflict.npcUuid())) {
                return true;
            }
        }
        return false;
    }

    private boolean loadedObservationAgrees(
            ProjectionCurrentness currentness,
            LoadedNpcIdentityIndex.ProjectionKey projectionKey,
            UUID plannedNpcUuid) {
        if (currentness.observations().size() != 1) {
            return false;
        }
        LoadedNpcIdentityIndex.LoadedNpcObservation observation =
                currentness.observations().getFirst();
        return plannedNpcUuid.equals(observation.componentUuid())
                && plannedNpcUuid.equals(observation.legacyNpcUuid())
                && projectionKey.equals(observation.projectionKey());
    }

    private Decision validateExact(
            CompanionPopulationOperationRecord operation,
            BreedingPopulationReplayTargetCodec.Target target,
            CompanionPopulationEvidenceSet evidenceSet,
            CompanionPopulationEvidenceSet.ProjectionObservation observation) {
        UUID planned = target.plannedNpcUuid();
        CompanionPopulationEvidence marker = observation.evidence();
        if (!planned.equals(observation.componentUuid())
                || !planned.equals(observation.legacyNpcUuid())
                || !planned.equals(marker.npcUuid())) {
            return Decision.blocked("breeding-replay-persisted-projection-identity-mismatch");
        }
        if (observation.deathObserved()) {
            return Decision.blocked("breeding-replay-persisted-projection-dead");
        }
        UUID expectedOwner = owner(operation.newStateJson());
        if (!marker.ownerObserved() || !Objects.equals(expectedOwner, marker.ownerUuid())) {
            return Decision.blocked("breeding-replay-persisted-projection-owner-mismatch");
        }
        CompanionPopulationEvidenceSet.PhysicalLocation location = location(marker);
        if (location == null || target.worldName() == null
                || target.chunkX() == null || target.chunkZ() == null
                || !normalize(target.worldName()).equals(normalize(location.worldName()))
                || target.chunkX().intValue() != location.chunkX()
                || target.chunkZ().intValue() != location.chunkZ()) {
            return Decision.blocked("breeding-replay-persisted-projection-location-mismatch");
        }
        if (!ordinaryPhysicalEvidenceAgrees(
                evidenceSet, planned, expectedOwner, location, observation.deathObserved())) {
            return Decision.blocked("breeding-replay-persisted-projection-ordinary-conflict");
        }
        // Repair owns ledger/alias convergence from this exact ordinary physical observation.
        // The terminal RETRYABLE row remains a durable tombstone but is no longer replayable.
        return Decision.committed();
    }

    private boolean ordinaryPhysicalEvidenceAgrees(
            CompanionPopulationEvidenceSet evidenceSet,
            UUID planned,
            @Nullable UUID expectedOwner,
            CompanionPopulationEvidenceSet.PhysicalLocation location,
            boolean dead) {
        for (CompanionPopulationEvidenceSet.Conflict conflict : evidenceSet.conflicts()) {
            if (planned.equals(conflict.npcUuid())) {
                return false;
            }
        }
        CompanionPopulationEvidenceSet.ResolvedEvidence ordinary =
                evidenceSet.byNpcUuid().get(planned);
        return ordinary != null && ordinary.physical()
                && ordinary.deathObserved() == dead
                && ordinary.ownerObserved()
                && Objects.equals(expectedOwner, ordinary.observedOwnerUuid())
                && Objects.equals(location, ordinary.physicalLocation());
    }

    @Nullable
    private UUID owner(@Nullable String json) {
        JsonObject state = JsonParser.parseString(
                Objects.requireNonNull(json, "newStateJson")).getAsJsonObject();
        JsonElement value = state.has("ownerUuid") ? state.get("ownerUuid") : state.get("owner");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        String raw = value.getAsString();
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    @Nullable
    private CompanionPopulationEvidenceSet.PhysicalLocation location(
            CompanionPopulationEvidence marker) {
        return marker.physicalWorldName() == null
                || marker.physicalChunkX() == null
                || marker.physicalChunkZ() == null
                ? null : new CompanionPopulationEvidenceSet.PhysicalLocation(
                        marker.physicalWorldName(), marker.physicalChunkX(),
                        marker.physicalChunkZ());
    }

    private String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
    }
}
