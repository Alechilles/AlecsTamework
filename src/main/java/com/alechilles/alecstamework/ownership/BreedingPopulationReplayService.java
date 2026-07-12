package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds replay-safe breeding state exclusively from retained operation-journal evidence. */
final class BreedingPopulationReplayService {
    private static final String OPERATION_TYPE = OwnerPopulationOperation.BREEDING.name();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private boolean loaded;

    BreedingPopulationReplayService(@Nonnull List<CompanionPopulationOperationRecord> operations) {
        this(operations, true);
    }

    BreedingPopulationReplayService(@Nonnull List<CompanionPopulationOperationRecord> operations,
                                    boolean loaded) {
        this.loaded = loaded;
        for (CompanionPopulationOperationRecord operation : List.copyOf(operations)) {
            ingest(operation);
        }
    }

    synchronized void replace(@Nonnull List<CompanionPopulationOperationRecord> operations) {
        attempts.clear();
        loaded = true;
        for (CompanionPopulationOperationRecord operation : List.copyOf(operations)) {
            ingest(operation);
        }
    }

    synchronized void markUnavailable() {
        attempts.clear();
        loaded = false;
    }

    @Nonnull
    synchronized BreedingPopulationReplayState state(@Nonnull String attemptKey) {
        String key = requireText(attemptKey, "attemptKey");
        if (!loaded) {
            return new BreedingPopulationReplayState(
                    false, null, Set.of(), "breeding-replay-journal-unavailable"
            );
        }
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return new BreedingPopulationReplayState(
                    true, null, Set.of(), "breeding-replay-empty"
            );
        }
        return attempt.snapshot();
    }

    synchronized boolean rememberPlan(@Nonnull String attemptKey,
                                      @Nonnull BreedingBirthPlanSnapshot plan) {
        if (!loaded) {
            return false;
        }
        String key = requireText(attemptKey, "attemptKey");
        Attempt attempt = attempts.computeIfAbsent(key, ignored -> new Attempt());
        attempt.mergePlan(Objects.requireNonNull(plan, "plan"));
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
        Attempt attempt = attempts.computeIfAbsent(key, ignored -> new Attempt());
        attempt.mergePlan(plan);
        attempt.committedChildren.add(child);
        attempt.validateCommittedChildren();
    }

    private void ingest(@Nullable CompanionPopulationOperationRecord operation) {
        if (operation == null || !OPERATION_TYPE.equalsIgnoreCase(operation.operationType())) {
            return;
        }
        ParsedTarget target = parseTarget(operation.targetContextJson());
        if (target == null) {
            loaded = false;
            return;
        }
        Attempt attempt = attempts.computeIfAbsent(target.attemptKey, ignored -> new Attempt());
        if (!matchesIdentity(
                target.attemptKey,
                target.childKey,
                operation.profileId(),
                target.plannedNpcUuid
        )) {
            attempt.conflicted = true;
            return;
        }
        if (target.planElement != null) {
            BreedingBirthPlanSnapshot plan = BreedingBirthPlanSnapshotJsonCodec.decode(
                    target.planElement
            );
            if (plan == null) {
                attempt.conflicted = true;
            } else {
                attempt.mergePlan(plan);
            }
        } else if (operation.state() == CompanionPopulationOperationRecord.State.COMMITTED) {
            loaded = false;
        }
        if (operation.state() == CompanionPopulationOperationRecord.State.COMMITTED) {
            attempt.committedChildren.add(target.childKey);
        }
        attempt.validateCommittedChildren();
    }

    private static boolean matchesIdentity(String attemptKey,
                                           String childKey,
                                           String profileId,
                                           UUID plannedNpcUuid) {
        return BreedingAdmissionIdentity.profileId(attemptKey, childKey).equals(profileId)
                && BreedingAdmissionIdentity.npcUuid(attemptKey, childKey).equals(plannedNpcUuid);
    }

    @Nullable
    private static ParsedTarget parseTarget(@Nullable String targetContextJson) {
        if (targetContextJson == null || targetContextJson.isBlank()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(targetContextJson).getAsJsonObject();
            String attemptKey = requiredText(json, "idempotencyKey");
            String childKey = requiredText(json, "childKey");
            UUID npcUuid = UUID.fromString(requiredText(json, "plannedNpcUuid"));
            JsonElement plan = json.get("birthPlan");
            return new ParsedTarget(attemptKey, childKey, npcUuid, plan);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String requiredText(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return requireText(element.getAsString(), key);
    }

    @Nonnull
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank.");
        }
        return normalized;
    }

    private final class Attempt {
        private BreedingBirthPlanSnapshot plan;
        private final Set<String> committedChildren = new HashSet<>();
        private boolean conflicted;

        private void mergePlan(BreedingBirthPlanSnapshot candidate) {
            if (plan == null) {
                plan = candidate;
            } else if (!plan.equals(candidate)) {
                conflicted = true;
            }
            validateCommittedChildren();
        }

        private void validateCommittedChildren() {
            if (plan == null) {
                return;
            }
            Set<String> planned = new HashSet<>();
            for (BreedingBirthPlanSnapshot.PlannedChild child : plan.children()) {
                planned.add(child.childKey());
            }
            if (!planned.containsAll(committedChildren)) {
                conflicted = true;
            }
        }

        private BreedingPopulationReplayState snapshot() {
            boolean missingPlanForCommit = plan == null && !committedChildren.isEmpty();
            boolean usable = !conflicted && !missingPlanForCommit;
            String reason = usable
                    ? "breeding-replay-ready"
                    : missingPlanForCommit
                            ? "breeding-replay-plan-missing"
                            : "breeding-replay-plan-conflict";
            return new BreedingPopulationReplayState(
                    usable, plan, committedChildren, reason
            );
        }
    }

    private record ParsedTarget(
            @Nonnull String attemptKey,
            @Nonnull String childKey,
            @Nonnull UUID plannedNpcUuid,
            @Nullable JsonElement planElement
    ) {
    }
}
