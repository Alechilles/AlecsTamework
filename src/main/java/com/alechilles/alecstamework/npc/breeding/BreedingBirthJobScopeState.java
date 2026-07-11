package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Store-local mutable indexes used only behind {@link BreedingBirthJobRegistry}'s lock. */
final class BreedingBirthJobScopeState {
    private final Map<UUID, BreedingBirthJob> jobsById = new HashMap<>();
    private final Map<BreedingPairKey, UUID> activeJobByPair = new HashMap<>();
    private final Map<UUID, UUID> activeJobByParentUuid = new HashMap<>();
    private final Map<String, UUID> activeJobByProfileId = new HashMap<>();
    private String worldId;
    private boolean closed;

    BreedingBirthJobRegistry.AdmissionResult register(BreedingBirthJob requested) {
        if (closed) {
            return admission(BreedingBirthJobRegistry.AdmissionStatus.SCOPE_CLOSED, null);
        }
        BreedingBirthJob existing = jobsById.get(requested.jobId());
        if (existing != null) {
            BreedingBirthJobRegistry.AdmissionStatus status = existing.hasSameIdentity(requested)
                    ? BreedingBirthJobRegistry.AdmissionStatus.ALREADY_REGISTERED
                    : BreedingBirthJobRegistry.AdmissionStatus.JOB_ID_CONFLICT;
            return admission(status, existing);
        }
        if (worldId != null && !worldId.equals(requested.pairKey().worldId())) {
            return admission(BreedingBirthJobRegistry.AdmissionStatus.WORLD_SCOPE_MISMATCH, null);
        }
        UUID pairJobId = activeJobByPair.get(requested.pairKey());
        if (pairJobId != null) {
            return admission(BreedingBirthJobRegistry.AdmissionStatus.PAIR_BUSY, jobsById.get(pairJobId));
        }
        UUID parentJobId = activeParentJobId(requested);
        if (parentJobId != null) {
            return admission(BreedingBirthJobRegistry.AdmissionStatus.PARENT_BUSY, jobsById.get(parentJobId));
        }
        UUID profileJobId = activeProfileJobId(requested);
        if (profileJobId != null) {
            return admission(BreedingBirthJobRegistry.AdmissionStatus.PROFILE_BUSY, jobsById.get(profileJobId));
        }
        worldId = requested.pairKey().worldId();
        jobsById.put(requested.jobId(), requested);
        index(requested);
        return admission(BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED, requested);
    }

    BreedingBirthJobRegistry.TransitionResult advance(UUID jobId,
                                                      BreedingBirthJobState expectedState,
                                                      BreedingBirthJobState nextState) {
        if (closed) {
            return transition(BreedingBirthJobRegistry.TransitionStatus.SCOPE_CLOSED, null);
        }
        BreedingBirthJob current = jobsById.get(jobId);
        if (current == null) {
            return transition(BreedingBirthJobRegistry.TransitionStatus.NOT_FOUND, null);
        }
        if (current.state().isTerminal()) {
            return transition(BreedingBirthJobRegistry.TransitionStatus.TERMINAL, current);
        }
        if (current.state() != expectedState) {
            return transition(BreedingBirthJobRegistry.TransitionStatus.STATE_MISMATCH, current);
        }
        if (!expectedState.mayAdvanceTo(nextState)) {
            return transition(BreedingBirthJobRegistry.TransitionStatus.INVALID_TRANSITION, current);
        }
        BreedingBirthJob updated = current.withState(nextState);
        jobsById.put(jobId, updated);
        return transition(BreedingBirthJobRegistry.TransitionStatus.APPLIED, updated);
    }

    BreedingBirthJobRegistry.SpawnClaimResult claimSpawn(UUID jobId) {
        if (closed) {
            return spawnClaim(BreedingBirthJobRegistry.SpawnClaimStatus.SCOPE_CLOSED, null);
        }
        BreedingBirthJob current = jobsById.get(jobId);
        if (current == null) {
            return spawnClaim(BreedingBirthJobRegistry.SpawnClaimStatus.NOT_FOUND, null);
        }
        if (current.state() == BreedingBirthJobState.SPAWNING) {
            return spawnClaim(BreedingBirthJobRegistry.SpawnClaimStatus.ALREADY_CLAIMED, current);
        }
        if (current.state().isTerminal()) {
            return spawnClaim(BreedingBirthJobRegistry.SpawnClaimStatus.TERMINAL, current);
        }
        if (current.state() != BreedingBirthJobState.HEARTS_SHOWN) {
            return spawnClaim(BreedingBirthJobRegistry.SpawnClaimStatus.NOT_READY, current);
        }
        BreedingBirthJob updated = current.withState(BreedingBirthJobState.SPAWNING);
        jobsById.put(jobId, updated);
        return spawnClaim(BreedingBirthJobRegistry.SpawnClaimStatus.CLAIMED, updated);
    }

    BreedingBirthJobRegistry.AdmissionUpdateResult shrinkAdmission(UUID jobId,
                                                                   List<PlannedChild> retainedChildren) {
        if (closed) {
            return admissionUpdate(BreedingBirthJobRegistry.AdmissionUpdateStatus.SCOPE_CLOSED, null);
        }
        BreedingBirthJob current = jobsById.get(jobId);
        if (current == null) {
            return admissionUpdate(BreedingBirthJobRegistry.AdmissionUpdateStatus.NOT_FOUND, null);
        }
        if (current.state().isTerminal()) {
            return admissionUpdate(BreedingBirthJobRegistry.AdmissionUpdateStatus.TERMINAL, current);
        }
        try {
            BreedingBirthJob updated = current.shrinkAdmission(retainedChildren);
            if (updated.activeAdmission().equals(current.activeAdmission())) {
                return admissionUpdate(BreedingBirthJobRegistry.AdmissionUpdateStatus.UNCHANGED, current);
            }
            jobsById.put(jobId, updated);
            return admissionUpdate(BreedingBirthJobRegistry.AdmissionUpdateStatus.APPLIED, updated);
        } catch (IllegalArgumentException exception) {
            return admissionUpdate(BreedingBirthJobRegistry.AdmissionUpdateStatus.INVALID_SHRINK, current);
        }
    }

    BreedingBirthJobRegistry.ReservationReleaseResult releaseChildReservation(UUID jobId,
                                                                               PlannedChild child) {
        if (closed) {
            return reservationRelease(BreedingBirthJobRegistry.ReservationReleaseStatus.SCOPE_CLOSED, null);
        }
        BreedingBirthJob current = jobsById.get(jobId);
        if (current == null) {
            return reservationRelease(BreedingBirthJobRegistry.ReservationReleaseStatus.NOT_FOUND, null);
        }
        if (current.state().isTerminal()) {
            return reservationRelease(BreedingBirthJobRegistry.ReservationReleaseStatus.TERMINAL, current);
        }
        try {
            BreedingBirthJob updated = current.releaseChildReservation(child);
            jobsById.put(jobId, updated);
            return reservationRelease(BreedingBirthJobRegistry.ReservationReleaseStatus.RELEASED, updated);
        } catch (IllegalArgumentException exception) {
            return reservationRelease(BreedingBirthJobRegistry.ReservationReleaseStatus.CHILD_NOT_RESERVED, current);
        }
    }

    BreedingBirthJobRegistry.TerminalResult finish(UUID jobId, BreedingBirthJobState outcome) {
        if (closed) {
            return terminal(BreedingBirthJobRegistry.TerminalStatus.SCOPE_CLOSED, null);
        }
        BreedingBirthJob current = jobsById.get(jobId);
        if (current == null) {
            return terminal(BreedingBirthJobRegistry.TerminalStatus.NOT_FOUND, null);
        }
        if (current.state().isTerminal()) {
            return terminal(BreedingBirthJobRegistry.TerminalStatus.ALREADY_TERMINAL, current);
        }
        if (outcome == BreedingBirthJobState.COMPLETED
                && current.state() != BreedingBirthJobState.SPAWNING) {
            return terminal(BreedingBirthJobRegistry.TerminalStatus.NOT_READY, current);
        }
        BreedingBirthJob updated = current.withTerminalState(outcome);
        jobsById.put(jobId, updated);
        releaseIndexes(current);
        return terminal(BreedingBirthJobRegistry.TerminalStatus.APPLIED, updated);
    }

    BreedingBirthJobRegistry.TerminalResult cancelByParentUuid(UUID parentUuid) {
        if (closed) {
            return terminal(BreedingBirthJobRegistry.TerminalStatus.SCOPE_CLOSED, null);
        }
        UUID jobId = activeJobByParentUuid.get(parentUuid);
        return jobId == null
                ? terminal(BreedingBirthJobRegistry.TerminalStatus.NOT_FOUND, null)
                : finish(jobId, BreedingBirthJobState.CANCELLED);
    }

    BreedingBirthJobRegistry.TerminalResult cancelByProfileId(String profileId) {
        if (closed) {
            return terminal(BreedingBirthJobRegistry.TerminalStatus.SCOPE_CLOSED, null);
        }
        UUID jobId = activeJobByProfileId.get(profileId);
        return jobId == null
                ? terminal(BreedingBirthJobRegistry.TerminalStatus.NOT_FOUND, null)
                : finish(jobId, BreedingBirthJobState.CANCELLED);
    }

    Optional<BreedingBirthJob> find(UUID jobId) {
        return closed ? Optional.empty() : Optional.ofNullable(jobsById.get(jobId));
    }

    int activeJobCount() {
        return closed ? 0 : activeJobByPair.size();
    }

    List<BreedingActiveReservation> activeReservations() {
        if (closed) {
            return List.of();
        }
        ArrayList<BreedingActiveReservation> reservations = new ArrayList<>();
        for (UUID jobId : activeJobByPair.values()) {
            BreedingBirthJob job = jobsById.get(jobId);
            if (job != null && !job.reservation().isEmpty()) {
                reservations.add(job.activeReservationSnapshot());
            }
        }
        reservations.sort(null);
        return List.copyOf(reservations);
    }

    void closeAndClear() {
        closed = true;
        jobsById.clear();
        activeJobByPair.clear();
        activeJobByParentUuid.clear();
        activeJobByProfileId.clear();
        worldId = null;
    }

    private UUID activeParentJobId(BreedingBirthJob job) {
        UUID jobId = activeJobByParentUuid.get(job.firstParent().entityUuid());
        return jobId != null ? jobId : activeJobByParentUuid.get(job.secondParent().entityUuid());
    }

    private UUID activeProfileJobId(BreedingBirthJob job) {
        UUID jobId = activeJobByProfileId.get(job.firstParent().profileId());
        return jobId != null ? jobId : activeJobByProfileId.get(job.secondParent().profileId());
    }

    private void index(BreedingBirthJob job) {
        activeJobByPair.put(job.pairKey(), job.jobId());
        activeJobByParentUuid.put(job.firstParent().entityUuid(), job.jobId());
        activeJobByParentUuid.put(job.secondParent().entityUuid(), job.jobId());
        activeJobByProfileId.put(job.firstParent().profileId(), job.jobId());
        activeJobByProfileId.put(job.secondParent().profileId(), job.jobId());
    }

    private void releaseIndexes(BreedingBirthJob job) {
        activeJobByPair.remove(job.pairKey(), job.jobId());
        activeJobByParentUuid.remove(job.firstParent().entityUuid(), job.jobId());
        activeJobByParentUuid.remove(job.secondParent().entityUuid(), job.jobId());
        activeJobByProfileId.remove(job.firstParent().profileId(), job.jobId());
        activeJobByProfileId.remove(job.secondParent().profileId(), job.jobId());
    }

    private static BreedingBirthJobRegistry.AdmissionResult admission(
            BreedingBirthJobRegistry.AdmissionStatus status,
            BreedingBirthJob job) {
        return new BreedingBirthJobRegistry.AdmissionResult(status, Optional.ofNullable(job));
    }

    private static BreedingBirthJobRegistry.TransitionResult transition(
            BreedingBirthJobRegistry.TransitionStatus status,
            BreedingBirthJob job) {
        return new BreedingBirthJobRegistry.TransitionResult(status, Optional.ofNullable(job));
    }

    private static BreedingBirthJobRegistry.SpawnClaimResult spawnClaim(
            BreedingBirthJobRegistry.SpawnClaimStatus status,
            BreedingBirthJob job) {
        return new BreedingBirthJobRegistry.SpawnClaimResult(status, Optional.ofNullable(job));
    }

    private static BreedingBirthJobRegistry.AdmissionUpdateResult admissionUpdate(
            BreedingBirthJobRegistry.AdmissionUpdateStatus status,
            BreedingBirthJob job) {
        return new BreedingBirthJobRegistry.AdmissionUpdateResult(status, Optional.ofNullable(job));
    }

    private static BreedingBirthJobRegistry.ReservationReleaseResult reservationRelease(
            BreedingBirthJobRegistry.ReservationReleaseStatus status,
            BreedingBirthJob job) {
        return new BreedingBirthJobRegistry.ReservationReleaseResult(status, Optional.ofNullable(job));
    }

    private static BreedingBirthJobRegistry.TerminalResult terminal(
            BreedingBirthJobRegistry.TerminalStatus status,
            BreedingBirthJob job) {
        return new BreedingBirthJobRegistry.TerminalResult(status, Optional.ofNullable(job));
    }
}
