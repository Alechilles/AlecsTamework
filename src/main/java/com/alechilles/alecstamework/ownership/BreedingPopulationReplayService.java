package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds exact-attempt and canonical-pair breeding replay state from operation journals. */
final class BreedingPopulationReplayService {
    private static final String OPERATION_TYPE = OwnerPopulationOperation.BREEDING.name();

    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Map<ParentPair, Set<String>> pendingAttemptsByPair = new HashMap<>();
    private final Map<String, Set<String>> pendingAttemptsByParent = new HashMap<>();
    private final Set<String> pendingAttemptsWithoutPairMetadata = new HashSet<>();
    private boolean loaded;

    BreedingPopulationReplayService(@Nonnull List<CompanionPopulationOperationRecord> operations) {
        this(operations, true);
    }

    BreedingPopulationReplayService(@Nonnull List<CompanionPopulationOperationRecord> operations,
                                    boolean loaded) {
        this.loaded = loaded;
        ingestAll(operations);
    }

    synchronized void replace(@Nonnull List<CompanionPopulationOperationRecord> operations) {
        attempts.clear();
        loaded = true;
        ingestAll(operations);
    }

    synchronized void markUnavailable() {
        attempts.clear();
        pendingAttemptsByPair.clear();
        pendingAttemptsByParent.clear();
        pendingAttemptsWithoutPairMetadata.clear();
        loaded = false;
    }

    /** Legacy exact-attempt lookup remains available even when pair metadata is absent. */
    @Nonnull
    synchronized BreedingPopulationReplayState state(@Nonnull String attemptKey) {
        String key = requireText(attemptKey, "attemptKey");
        if (!loaded) {
            return empty(false, "breeding-replay-journal-unavailable");
        }
        Attempt attempt = attempts.get(key);
        return attempt == null
                ? empty(true, "breeding-replay-empty")
                : attempt.snapshot();
    }

    /**
     * Resolves the sole incomplete attempt for a canonical pair in its original world.
     * Legacy pending rows without pair metadata block this lookup rather than being guessed.
     */
    @Nonnull
    synchronized BreedingPopulationReplayState stateForPair(
            @Nonnull String worldName,
            @Nonnull List<String> parentProfileIds
    ) {
        String world = requireText(worldName, "worldName");
        ParentPair pair = ParentPair.from(parentProfileIds);
        if (!loaded) {
            return empty(false, "breeding-replay-journal-unavailable");
        }
        if (!pendingAttemptsWithoutPairMetadata.isEmpty()) {
            return empty(false, "breeding-replay-pair-metadata-missing");
        }
        if (hasDifferentPartnerPending(pair)) {
            return empty(false, "breeding-replay-parent-conflict");
        }
        Set<String> candidates = pendingAttemptsByPair.getOrDefault(pair, Set.of());
        if (candidates.size() > 1) {
            return empty(false, "breeding-replay-pair-conflict");
        }
        if (candidates.isEmpty()) {
            return empty(true, "breeding-replay-pair-empty");
        }
        Attempt attempt = attempts.get(candidates.iterator().next());
        if (attempt == null) {
            return empty(false, "breeding-replay-pair-index-conflict");
        }
        if (!world.equals(attempt.worldName)) {
            return attempt.snapshot(false, "breeding-replay-world-mismatch");
        }
        return attempt.snapshot();
    }

    synchronized boolean accepts(@Nonnull BreedingPopulationAdmissionRequest request) {
        if (!loaded) {
            return false;
        }
        String attemptKey = requireText(request.idempotencyKey(), "attemptKey");
        if (request.hasCanonicalParentPair()) {
            ParentPair pair = ParentPair.from(request.parentProfileIds());
            Set<String> pairAttempts = pendingAttemptsByPair.getOrDefault(pair, Set.of());
            if (!pendingAttemptsWithoutPairMetadata.isEmpty()
                    || hasDifferentPartnerPending(pair)
                    || pairAttempts.size() > 1
                    || (pairAttempts.size() == 1 && !pairAttempts.contains(attemptKey))) {
                return false;
            }
        }
        Attempt attempt = attempts.get(attemptKey);
        return attempt == null || attempt.compatibleWith(request);
    }

    /** Records only the exact stable prefix that the shared authority successfully admitted. */
    synchronized boolean recordPrepared(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull List<PreparedBreedingPopulationBatch.ReservedChild> admittedChildren
    ) {
        if (!loaded) {
            return false;
        }
        Attempt attempt = attempts.computeIfAbsent(
                requireText(request.idempotencyKey(), "attemptKey"), Attempt::new
        );
        attempt.mergeRequest(request);
        for (PreparedBreedingPopulationBatch.ReservedChild child : List.copyOf(admittedChildren)) {
            if (child == null || !matchesIdentity(
                    attempt.attemptKey,
                    child.childKey(),
                    child.profileId(),
                    child.plannedNpcUuid()
            ) || !attempt.addCurrentPreparation(child.childKey())) {
                attempt.conflicted = true;
            }
        }
        attempt.validateChildren();
        rebuildPairIndex();
        return !attempt.conflicted;
    }

    synchronized void recordCommitted(@Nonnull String attemptKey,
                                      @Nonnull String childKey,
                                      @Nonnull String profileId,
                                      @Nonnull UUID plannedNpcUuid,
                                      @Nonnull BreedingBirthPlanSnapshot plan) {
        if (!loaded) {
            return;
        }
        String key = requireText(attemptKey, "attemptKey");
        String child = requireText(childKey, "childKey");
        if (!matchesIdentity(key, child, profileId, plannedNpcUuid)) {
            return;
        }
        Attempt attempt = attempts.computeIfAbsent(key, Attempt::new);
        attempt.mergePlan(plan);
        attempt.commit(child);
        attempt.validateChildren();
        rebuildPairIndex();
    }

    synchronized void recordAborted(@Nonnull String attemptKey,
                                    @Nonnull String childKey,
                                    @Nonnull BreedingBirthPlanSnapshot plan) {
        if (!loaded) {
            return;
        }
        String key = requireText(attemptKey, "attemptKey");
        Attempt attempt = attempts.computeIfAbsent(key, Attempt::new);
        attempt.mergePlan(plan);
        attempt.abort(requireText(childKey, "childKey"));
        attempt.validateChildren();
        rebuildPairIndex();
    }

    private void ingestAll(@Nonnull List<CompanionPopulationOperationRecord> operations) {
        for (CompanionPopulationOperationRecord operation : List.copyOf(operations)) {
            ingest(operation);
        }
        rebuildPairIndex();
    }

    private void ingest(@Nullable CompanionPopulationOperationRecord operation) {
        if (operation == null || !OPERATION_TYPE.equalsIgnoreCase(operation.operationType())) {
            return;
        }
        BreedingPopulationReplayTargetCodec.Target target =
                BreedingPopulationReplayTargetCodec.decode(operation.targetContextJson());
        if (target == null) {
            loaded = false;
            return;
        }
        Attempt attempt = attempts.computeIfAbsent(target.attemptKey(), Attempt::new);
        if (!matchesIdentity(
                target.attemptKey(),
                target.childKey(),
                operation.profileId(),
                target.plannedNpcUuid()
        )) {
            attempt.conflicted = true;
            return;
        }
        attempt.mergeMetadata(target.parentProfileIds(), target.worldName());
        mergePersistedPlan(attempt, target, operation.state());
        if (operation.state() == CompanionPopulationOperationRecord.State.COMMITTED) {
            attempt.commit(target.childKey());
        } else if (operation.state() == CompanionPopulationOperationRecord.State.FAILED) {
            attempt.abort(target.childKey());
        } else {
            attempt.addJournalPending(target.childKey());
        }
        attempt.validateChildren();
    }

    private void mergePersistedPlan(Attempt attempt,
                                    BreedingPopulationReplayTargetCodec.Target target,
                                    CompanionPopulationOperationRecord.State state) {
        if (target.planElement() == null) {
            if (state == CompanionPopulationOperationRecord.State.COMMITTED) {
                loaded = false;
            }
            return;
        }
        BreedingBirthPlanSnapshot plan = BreedingBirthPlanSnapshotJsonCodec.decode(
                target.planElement()
        );
        if (plan == null) {
            attempt.conflicted = true;
        } else {
            attempt.mergePlan(plan);
        }
    }

    private void rebuildPairIndex() {
        pendingAttemptsByPair.clear();
        pendingAttemptsByParent.clear();
        pendingAttemptsWithoutPairMetadata.clear();
        for (Attempt attempt : attempts.values()) {
            if (!attempt.hasPendingChildren()) {
                continue;
            }
            if (attempt.parentPair == null || attempt.worldName == null) {
                pendingAttemptsWithoutPairMetadata.add(attempt.attemptKey);
                continue;
            }
            pendingAttemptsByPair.computeIfAbsent(
                    attempt.parentPair, ignored -> new HashSet<>()
            ).add(attempt.attemptKey);
            for (String parentProfileId : attempt.parentPair.profileIds()) {
                pendingAttemptsByParent.computeIfAbsent(
                        parentProfileId, ignored -> new HashSet<>()
                ).add(attempt.attemptKey);
            }
        }
    }

    private boolean hasDifferentPartnerPending(@Nonnull ParentPair requestedPair) {
        for (String parentProfileId : requestedPair.profileIds()) {
            for (String attemptKey : pendingAttemptsByParent.getOrDefault(
                    parentProfileId, Set.of()
            )) {
                Attempt attempt = attempts.get(attemptKey);
                if (attempt == null || !requestedPair.equals(attempt.parentPair)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesIdentity(String attemptKey,
                                           String childKey,
                                           String profileId,
                                           UUID plannedNpcUuid) {
        return BreedingAdmissionIdentity.profileId(attemptKey, childKey).equals(profileId)
                && BreedingAdmissionIdentity.npcUuid(attemptKey, childKey).equals(plannedNpcUuid);
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank.");
        }
        return normalized;
    }

    @Nonnull
    private static BreedingPopulationReplayState empty(boolean usable, @Nonnull String reason) {
        return new BreedingPopulationReplayState(
                usable, null, null, Set.of(), Set.of(), reason
        );
    }

    private static final class Attempt {
        private final String attemptKey;
        @Nullable
        private BreedingBirthPlanSnapshot plan;
        @Nullable
        private ParentPair parentPair;
        @Nullable
        private String worldName;
        private final Set<String> pendingChildren = new HashSet<>();
        private final Set<String> committedChildren = new HashSet<>();
        private final Set<String> abortedChildren = new HashSet<>();
        private boolean conflicted;

        private Attempt(@Nonnull String attemptKey) {
            this.attemptKey = requireText(attemptKey, "attemptKey");
        }

        private boolean compatibleWith(BreedingPopulationAdmissionRequest request) {
            if (conflicted || (plan != null && !plan.equals(request.birthPlan()))) {
                return false;
            }
            if (request.hasCanonicalParentPair()) {
                ParentPair candidatePair = ParentPair.from(request.parentProfileIds());
                if ((parentPair != null && !parentPair.equals(candidatePair))
                        || (worldName != null && !worldName.equals(request.worldName()))) {
                    return false;
                }
            }
            Set<String> requestedChildren = new HashSet<>();
            for (BreedingPopulationAdmissionRequest.PlannedChild child : request.plannedChildren()) {
                requestedChildren.add(child.childKey());
            }
            return !requestedChildren.isEmpty() && pendingChildren.containsAll(requestedChildren);
        }

        private void mergeRequest(BreedingPopulationAdmissionRequest request) {
            mergePlan(request.birthPlan());
            mergeMetadata(request.parentProfileIds(), request.worldName());
        }

        private void mergePlan(BreedingBirthPlanSnapshot candidate) {
            Objects.requireNonNull(candidate, "plan");
            if (plan == null) {
                plan = candidate;
            } else if (!plan.equals(candidate)) {
                conflicted = true;
            }
        }

        private void mergeMetadata(List<String> parentProfileIds, @Nullable String candidateWorld) {
            if (!parentProfileIds.isEmpty()) {
                ParentPair candidatePair = ParentPair.from(parentProfileIds);
                if (parentPair == null) {
                    parentPair = candidatePair;
                } else if (!parentPair.equals(candidatePair)) {
                    conflicted = true;
                }
            }
            if (candidateWorld != null) {
                String normalizedWorld = requireText(candidateWorld, "worldName");
                if (worldName == null) {
                    worldName = normalizedWorld;
                } else if (!worldName.equals(normalizedWorld)) {
                    conflicted = true;
                }
            }
        }

        private boolean addCurrentPreparation(String childKey) {
            String child = requireText(childKey, "childKey");
            if (committedChildren.contains(child) || abortedChildren.contains(child)) {
                return false;
            }
            pendingChildren.add(child);
            return true;
        }

        private void addJournalPending(String childKey) {
            String child = requireText(childKey, "childKey");
            if (!committedChildren.contains(child) && !abortedChildren.contains(child)) {
                pendingChildren.add(child);
            }
        }

        private void commit(String childKey) {
            String child = requireText(childKey, "childKey");
            pendingChildren.remove(child);
            abortedChildren.remove(child);
            committedChildren.add(child);
        }

        private void abort(String childKey) {
            String child = requireText(childKey, "childKey");
            if (committedChildren.contains(child)) {
                return;
            }
            pendingChildren.remove(child);
            abortedChildren.add(child);
        }

        private boolean hasPendingChildren() {
            return !pendingChildren.isEmpty();
        }

        private void validateChildren() {
            if (plan == null) {
                return;
            }
            Set<String> planned = new HashSet<>();
            for (BreedingBirthPlanSnapshot.PlannedChild child : plan.children()) {
                planned.add(child.childKey());
            }
            if (!planned.containsAll(pendingChildren)
                    || !planned.containsAll(committedChildren)
                    || !planned.containsAll(abortedChildren)) {
                conflicted = true;
            }
        }

        private BreedingPopulationReplayState snapshot() {
            boolean missingPlan = plan == null
                    && (!pendingChildren.isEmpty() || !committedChildren.isEmpty());
            boolean usable = !conflicted && !missingPlan;
            String reason = usable
                    ? pendingChildren.isEmpty()
                            ? "breeding-replay-terminal"
                            : "breeding-replay-ready"
                    : missingPlan
                            ? "breeding-replay-plan-missing"
                            : "breeding-replay-plan-conflict";
            return snapshot(usable, reason);
        }

        private BreedingPopulationReplayState snapshot(boolean usable, String reason) {
            return new BreedingPopulationReplayState(
                    usable,
                    attemptKey,
                    plan,
                    pendingChildren,
                    committedChildren,
                    reason
            );
        }
    }

    private record ParentPair(@Nonnull List<String> profileIds) {
        private ParentPair {
            profileIds = BreedingPopulationAdmissionRequest.normalizeParentProfileIds(profileIds);
            if (profileIds.isEmpty()) {
                throw new IllegalArgumentException("Pair metadata is required.");
            }
        }

        private static ParentPair from(List<String> profileIds) {
            return new ParentPair(profileIds);
        }
    }

}
