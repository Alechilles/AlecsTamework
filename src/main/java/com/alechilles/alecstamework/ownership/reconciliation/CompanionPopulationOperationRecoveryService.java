package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.ProfileOwnerMutation;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationRecoveryDecisionService.Context;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationRecoveryDecisionService.Decision;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationRecoveryDecisionService.JournalState;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationRecoveryDecisionService.ObservedState;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationRecoveryDecisionService.PhysicalExpectation;

/**
 * Resolves crash-interrupted journals from actual physical/dormant evidence, never profile rows.
 */
public final class CompanionPopulationOperationRecoveryService {
    private final CompanionPopulationRepository repository;

    public CompanionPopulationOperationRecoveryService(@Nonnull CompanionPopulationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Nonnull
    public CompletableFuture<RecoveryResult> recoverAsync(
            @Nonnull List<CompanionPopulationOperationRecord> operations,
            @Nonnull CompanionPopulationEvidenceSet evidenceSet
    ) {
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(evidenceSet, "evidenceSet");
        return loadBaselines().thenCompose(baselines -> {
            MutableResult result = new MutableResult();
            return recoverNext(
                    List.copyOf(operations),
                    evidenceSet.byNpcUuid(),
                    baselines,
                    0,
                    result
            ).thenApply(ignored -> result.freeze());
        });
    }

    @Nonnull
    private CompletableFuture<Map<String, CompanionPopulationStateRecord>> loadBaselines() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, CompanionPopulationStateRecord> result = new HashMap<>();
                for (CompanionPopulationStateRecord state : repository.loadAllStates()) {
                    result.put(state.profileId(), state);
                }
                return Map.copyOf(result);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    @Nonnull
    private CompletableFuture<Void> recoverNext(
            @Nonnull List<CompanionPopulationOperationRecord> operations,
            @Nonnull Map<UUID, CompanionPopulationEvidenceSet.ResolvedEvidence> evidence,
            @Nonnull Map<String, CompanionPopulationStateRecord> baselines,
            int index,
            @Nonnull MutableResult result
    ) {
        if (index >= operations.size()) {
            return CompletableFuture.completedFuture(null);
        }
        CompanionPopulationOperationRecord operation = operations.get(index);
        return recoverOne(operation, evidence, baselines.get(operation.profileId()), result)
                .thenCompose(ignored -> recoverNext(operations, evidence, baselines, index + 1, result));
    }

    @Nonnull
    private CompletableFuture<Void> recoverOne(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull Map<UUID, CompanionPopulationEvidenceSet.ResolvedEvidence> evidence,
            @Nullable CompanionPopulationStateRecord baseline,
            @Nonnull MutableResult result
    ) {
        if (operation.state() == CompanionPopulationOperationRecord.State.PREPARED) {
            return failOperation(operation, "startup-recovery-prepared-not-applied", result);
        }
        CompanionSpawnSourceFinalizationContext.Descriptor sourceFinalization;
        try {
            sourceFinalization = CompanionSpawnSourceFinalizationContext.descriptor(
                    operation.targetContextJson()
            );
        } catch (RuntimeException invalidContext) {
            result.ambiguous(operation, "operation-recovery-source-finalization-json-invalid");
            return CompletableFuture.completedFuture(null);
        }
        if (operation.state() == CompanionPopulationOperationRecord.State.APPLIED
                && sourceFinalization != null) {
            result.ambiguous(
                    operation,
                    "operation-recovery-source-finalization-pending:"
                            + sourceFinalization.kind().name().toLowerCase(Locale.ROOT)
            );
            return CompletableFuture.completedFuture(null);
        }
        ParsedOperation parsed;
        try {
            parsed = parse(operation, baseline);
        } catch (RuntimeException exception) {
            result.ambiguous(operation, "operation-recovery-json-invalid");
            return CompletableFuture.completedFuture(null);
        }

        ObservedState observed = observedState(evidence.get(parsed.npcUuid()));
        ObservedState previousObserved = parsed.previousNpcUuid() == null
                || parsed.previousNpcUuid().equals(parsed.npcUuid())
                ? null
                : observedState(evidence.get(parsed.previousNpcUuid()));
        Decision decision = CompanionPopulationRecoveryDecisionService.decide(
                operation.state(), parsed.decisionContext(), observed, previousObserved
        );
        return switch (decision.outcome()) {
            case COMMIT -> commitOperation(operation, parsed, observed, result);
            case CLOSE -> failOperation(operation, decision.reason(), result);
            case AMBIGUOUS -> {
                result.ambiguous(operation, decision.reason());
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    @Nonnull
    private CompletableFuture<Void> commitOperation(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull ParsedOperation parsed,
            @Nullable ObservedState observed,
            @Nonnull MutableResult result
    ) {
        boolean permanentRelease = parsed.permanentRelease();
        String ownershipWorld = parsed.newState().worldSpecified()
                ? parsed.newState().worldName()
                : firstNonBlank(observed == null ? null : observed.worldName(), parsed.targetWorldName());
        String lifecycleState = permanentRelease
                ? Objects.requireNonNull(parsed.newState().lifecycleState()).name()
                : Objects.requireNonNull(observed).lifecycleState().name();
        boolean persistPhysical = observed != null && observed.physical() && !permanentRelease;
        PopulationPersistenceTransition.Commit commit = new PopulationPersistenceTransition.Commit(
                operation.operationId(),
                operation.profileId(),
                operation.expectedRevision(),
                ownerMutation(parsed.oldState().ownerUuid(), parsed.newState().ownerUuid()),
                parsed.npcUuid(),
                ownershipWorld,
                lifecycleState,
                persistPhysical ? observed.worldName() : null,
                persistPhysical ? observed.chunkX() : null,
                persistPhysical ? observed.chunkZ() : null,
                "startup-operation-recovery"
        );
        return repository.commitAsync(commit).completion().thenAccept(outcome -> {
            PopulationPersistenceTransition.Result value = outcome.value();
            if (outcome.isCommitted() && value != null
                    && value.status() == PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING) {
                result.ambiguous(operation, "operation-recovery-source-finalization-pending");
            } else if (outcome.isCommitted() && value != null && value.isSuccess()) {
                result.committed++;
            } else {
                result.ambiguous(operation, "operation-recovery-commit-failed");
            }
        });
    }

    @Nonnull
    private CompletableFuture<Void> failOperation(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull String reason,
            @Nonnull MutableResult result
    ) {
        PersistenceWriteQueue.WriteSubmission<Boolean> submission = repository.advanceOperationAsync(
                operation.operationId(),
                operation.state(),
                CompanionPopulationOperationRecord.State.FAILED,
                reason
        );
        return submission.completion().thenAccept(outcome -> {
            if (outcome.isCommitted() && Boolean.TRUE.equals(outcome.value())) {
                result.canceled++;
            } else {
                result.ambiguous(operation, "operation-recovery-close-failed");
            }
        });
    }

    @Nonnull
    private static ParsedOperation parse(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nullable CompanionPopulationStateRecord baseline
    ) {
        JsonObject context = parseObject(operation.targetContextJson());
        String npcRaw = firstNonBlank(nullableString(context, "npcUuid"),
                nullableString(context, "plannedNpcUuid"));
        if (npcRaw == null) {
            throw new IllegalArgumentException("Missing operation NPC UUID.");
        }
        OwnerPopulationOperation operationType = OwnerPopulationOperation.valueOf(
                operation.operationType().trim().toUpperCase(Locale.ROOT)
        );
        JournalState oldState = withFallback(parseState(operation.oldStateJson()), baseline);
        JournalState newState = parseState(operation.newStateJson());
        boolean permanentRelease = booleanValue(context, "permanentRelease");
        boolean permanentDeath = booleanValue(context, "permanentDeath");
        validatePermanentRelease(permanentRelease, permanentDeath, operationType, oldState, newState);
        String targetWorld = nullableString(context, "world");
        PhysicalExpectation targetPhysical = physicalExpectation(context, targetWorld);
        PhysicalExpectation oldPhysical = baseline == null
                || baseline.physicalWorldName() == null
                || baseline.physicalChunkX() == null
                || baseline.physicalChunkZ() == null
                ? null
                : new PhysicalExpectation(
                        baseline.physicalWorldName(),
                        baseline.physicalChunkX(),
                        baseline.physicalChunkZ()
                );
        String previousNpcRaw = nullableString(context, "previousNpcUuid");
        UUID previousNpcUuid = previousNpcRaw == null
                ? baseline == null ? null : baseline.currentNpcUuid()
                : UUID.fromString(previousNpcRaw);
        return new ParsedOperation(
                UUID.fromString(npcRaw),
                previousNpcUuid,
                operationType,
                oldState,
                newState,
                targetWorld,
                targetPhysical,
                oldPhysical,
                permanentRelease,
                permanentDeath
        );
    }

    @Nonnull
    private static JournalState parseState(@Nonnull String json) {
        JsonObject object = parseObject(json);
        boolean lifecycleSpecified = object.has("lifecycleState");
        String lifecycleRaw = nullableString(object, "lifecycleState");
        CompanionLifecycleState lifecycle = lifecycleRaw == null
                ? null
                : CompanionLifecycleState.valueOf(lifecycleRaw.trim().toUpperCase(Locale.ROOT));
        boolean worldSpecified = object.has("ownershipWorldName");
        return new JournalState(
                parseOwner(object),
                lifecycle,
                lifecycleSpecified,
                nullableString(object, "ownershipWorldName"),
                worldSpecified
        );
    }

    @Nonnull
    private static JournalState withFallback(
            @Nonnull JournalState state,
            @Nullable CompanionPopulationStateRecord baseline
    ) {
        if (baseline == null) {
            return state;
        }
        CompanionLifecycleState lifecycle = state.lifecycleSpecified()
                ? state.lifecycleState()
                : CompanionLifecycleState.valueOf(baseline.lifecycleState());
        String world = state.worldSpecified() ? state.worldName() : firstNonBlank(
                baseline.ownershipWorldName(), baseline.profileLastWorldName()
        );
        return new JournalState(state.ownerUuid(), lifecycle, true, world, true);
    }

    @Nullable
    private static ObservedState observedState(
            @Nullable CompanionPopulationEvidenceSet.ResolvedEvidence evidence
    ) {
        if (evidence == null
                || evidence.lifecycleKind() == CompanionPopulationEvidence.Kind.PROFILE_RECORD) {
            return null;
        }
        CompanionLifecycleState lifecycle = switch (evidence.lifecycleKind()) {
            case PHYSICAL_ENTITY -> CompanionLifecycleState.UNLOADED;
            case PHYSICAL_DEAD_ENTITY -> CompanionLifecycleState.DEAD_REVIVABLE;
            case CAPTURED_SNAPSHOT, CAPTURED_ITEM -> CompanionLifecycleState.CAPTURED;
            case DEATH_SNAPSHOT -> CompanionLifecycleState.DEAD_REVIVABLE;
            case LOST_SNAPSHOT -> CompanionLifecycleState.LOST;
            case COOP_SNAPSHOT -> CompanionLifecycleState.COOP;
            case PROFILE_RECORD -> throw new IllegalStateException("Profile rows are not apply evidence.");
        };
        CompanionPopulationEvidenceSet.PhysicalLocation physical = evidence.physicalLocation();
        return new ObservedState(
                evidence.observedOwnerUuid(),
                evidence.ownerObserved(),
                lifecycle,
                firstNonBlank(physical == null ? null : physical.worldName(), evidence.ownershipWorldName()),
                physical != null,
                evidence.deathObserved(),
                physical == null ? null : physical.chunkX(),
                physical == null ? null : physical.chunkZ()
        );
    }

    @Nullable
    private static UUID parseOwner(@Nonnull JsonObject object) {
        if (!object.has("ownerUuid") && !object.has("owner")) {
            throw new IllegalArgumentException("Missing population operation owner state.");
        }
        JsonElement value = object.has("ownerUuid") ? object.get("ownerUuid") : object.get("owner");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        String raw = value.getAsString();
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    @Nonnull
    private static JsonObject parseObject(@Nullable String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Missing population operation JSON.");
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Nullable
    private static PhysicalExpectation physicalExpectation(
            @Nonnull JsonObject context,
            @Nullable String world
    ) {
        if (world == null || !context.has("chunkX") || !context.has("chunkZ")
                || context.get("chunkX").isJsonNull() || context.get("chunkZ").isJsonNull()) {
            return null;
        }
        return new PhysicalExpectation(
                world,
                context.get("chunkX").getAsInt(),
                context.get("chunkZ").getAsInt()
        );
    }

    @Nullable
    private static String nullableString(@Nonnull JsonObject object, @Nonnull String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        String raw = value.getAsString();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static boolean booleanValue(@Nonnull JsonObject object, @Nonnull String field) {
        JsonElement value = object.get(field);
        if (value == null) {
            return false;
        }
        if (value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid operation context boolean: " + field);
        }
        return value.getAsBoolean();
    }

    private static void validatePermanentRelease(boolean permanentRelease,
                                                 boolean permanentDeath,
                                                 OwnerPopulationOperation operation,
                                                 JournalState oldState,
                                                 JournalState newState) {
        if (permanentDeath && !permanentRelease) {
            throw new IllegalArgumentException("Permanent death requires a permanent-release operation.");
        }
        if (!permanentRelease) {
            return;
        }
        if (operation != OwnerPopulationOperation.OWNER_CLEAR
                || oldState.ownerUuid() == null
                || newState.ownerUuid() != null
                || !newState.lifecycleSpecified()
                || newState.lifecycleState() != CompanionLifecycleState.RELEASED) {
            throw new IllegalArgumentException("Invalid permanent-release operation context.");
        }
    }

    @Nonnull
    private static ProfileOwnerMutation ownerMutation(@Nullable UUID oldOwner, @Nullable UUID newOwner) {
        if (Objects.equals(oldOwner, newOwner)) {
            return ProfileOwnerMutation.unchanged();
        }
        return newOwner == null ? ProfileOwnerMutation.clear() : ProfileOwnerMutation.set(newOwner);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    public record RecoveryResult(int committed,
                                 int canceled,
                                 @Nonnull List<AmbiguousOperation> ambiguous) {
        public boolean complete() {
            return ambiguous.isEmpty();
        }
    }

    public record AmbiguousOperation(@Nonnull String operationId,
                                     @Nonnull String profileId,
                                     @Nonnull String reason) {
    }

    private record ParsedOperation(@Nonnull UUID npcUuid,
                                   @Nullable UUID previousNpcUuid,
                                   @Nonnull OwnerPopulationOperation operation,
                                   @Nonnull JournalState oldState,
                                   @Nonnull JournalState newState,
                                   @Nullable String targetWorldName,
                                   @Nullable PhysicalExpectation targetPhysical,
                                   @Nullable PhysicalExpectation oldPhysical,
                                   boolean permanentRelease,
                                   boolean permanentDeath) {
        @Nonnull
        private Context decisionContext() {
            return new Context(
                    operation,
                    oldState,
                    newState,
                    targetPhysical,
                    oldPhysical,
                    permanentRelease,
                    permanentDeath
            );
        }
    }

    private static final class MutableResult {
        private final List<AmbiguousOperation> ambiguous = new ArrayList<>();
        private int committed;
        private int canceled;

        private void ambiguous(@Nonnull CompanionPopulationOperationRecord operation,
                               @Nonnull String reason) {
            ambiguous.add(new AmbiguousOperation(operation.operationId(), operation.profileId(), reason));
        }

        @Nonnull
        private RecoveryResult freeze() {
            return new RecoveryResult(committed, canceled, List.copyOf(ambiguous));
        }
    }
}
