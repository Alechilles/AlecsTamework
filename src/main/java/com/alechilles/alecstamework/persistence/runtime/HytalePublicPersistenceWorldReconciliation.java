package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.items.LoadedNpcIdentityBootstrapService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Reconciles imported unresolved profiles from sealed loaded-world identity evidence.
 *
 * <p>The Hytale bootstrap performs world-thread scans. This participant retains
 * only immutable observations and submits each canonical resolution through the
 * existing shared profile operation; it never scans worlds or blocks a thread.</p>
 */
public final class HytalePublicPersistenceWorldReconciliation
        implements PublicPersistenceWorldReconciliation {
    private static final String IDEMPOTENCY_PREFIX = "world-reconcile-v1:";

    private final LoadedNpcIdentityIndex identityIndex;
    private final WorldEvidenceSource evidenceSource;
    private final Access access;
    private final LongSupplier clock;
    private final Object stateLock = new Object();
    private boolean quiesced;
    private Evidence evidence;

    HytalePublicPersistenceWorldReconciliation(
            LoadedNpcIdentityIndex identityIndex,
            WorldEvidenceSource evidenceSource,
            Access access,
            LongSupplier clock
    ) {
        this.identityIndex = Objects.requireNonNull(
                identityIndex,
                "identityIndex"
        );
        this.evidenceSource = Objects.requireNonNull(
                evidenceSource,
                "evidenceSource"
        );
        this.access = Objects.requireNonNull(access, "access");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates the production factory without constructing persistence facades early.
     */
    @Nonnull
    public static PublicPersistenceWorldReconciliationFactory factory(
            @Nonnull LoadedNpcIdentityBootstrapService bootstrap,
            @Nonnull LoadedNpcIdentityIndex identityIndex,
            @Nonnull LongSupplier clock
    ) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(identityIndex, "identityIndex");
        Objects.requireNonNull(clock, "clock");
        return facades -> new HytalePublicPersistenceWorldReconciliation(
                identityIndex,
                bootstrap::awaitCurrentBootstrap,
                new FacadePublicPersistenceWorldReconciliationAccess(facades),
                clock
        );
    }

    @Override
    @Nonnull
    public CompletionStage<Result> awaitEvidence() {
        if (isQuiesced()) {
            return failed("world_reconciliation_quiesced");
        }
        clearEvidence();
        CompletionStage<PersistenceReadResult<List<CompanionLifecycle>>> read =
                access.findAllLifecycles();
        if (read == null) {
            return failed("world_reconciliation_lifecycle_read_null");
        }
        return read.thenCompose(this::awaitSealedEvidence);
    }

    @Override
    @Nonnull
    public CompletionStage<Result> reconcile() {
        Evidence current = currentEvidence();
        if (current == null) {
            return CompletableFuture.completedFuture(Result.DEFERRED);
        }
        if (current.plans().isEmpty()) {
            clearEvidence();
            return CompletableFuture.completedFuture(Result.COMPLETE);
        }
        if (!identityIndex.isMutationRevisionCurrent(
                current.mutationRevision()
        )) {
            clearEvidence();
            return CompletableFuture.completedFuture(Result.DEFERRED);
        }
        return reconcilePlans(current, 0).thenApply(result -> {
            clearEvidence();
            if (result == Result.COMPLETE
                    && !identityIndex.isMutationRevisionCurrent(
                    current.mutationRevision()
            )) {
                return Result.DEFERRED;
            }
            return result;
        });
    }

    @Override
    public void quiesce() {
        synchronized (stateLock) {
            quiesced = true;
            evidence = null;
        }
    }

    private CompletionStage<Result> awaitSealedEvidence(
            PersistenceReadResult<List<CompanionLifecycle>> read
    ) {
        List<CompanionLifecycle> unresolved = unresolved(read);
        if (unresolved.isEmpty()) {
            storeEvidence(new Evidence(
                    identityIndex.snapshot().mutationRevision(),
                    List.of()
            ));
            return CompletableFuture.completedFuture(Result.COMPLETE);
        }
        CompletionStage<LoadedNpcIdentitySnapshot> stage =
                evidenceSource.awaitSealedEvidence();
        if (stage == null) {
            return failed("world_reconciliation_evidence_stage_null");
        }
        if (!stage.toCompletableFuture().isDone()) {
            return CompletableFuture.completedFuture(Result.DEFERRED);
        }
        return stage.thenApply(snapshot -> plan(unresolved, snapshot));
    }

    private List<CompanionLifecycle> unresolved(
            PersistenceReadResult<List<CompanionLifecycle>> read
    ) {
        if (read instanceof PersistenceReadResult.Failed<?> failed) {
            throw new IllegalStateException(
                    "world_reconciliation_lifecycle_read_failed",
                    failed.failure().cause()
            );
        }
        if (!(read instanceof
                PersistenceReadResult.Found<List<CompanionLifecycle>> found)) {
            throw new IllegalStateException(
                    "world_reconciliation_lifecycle_read_absent"
            );
        }
        return found.value().stream()
                .filter(lifecycle ->
                        lifecycle.state() == LifecycleState.UNRESOLVED
                                && !lifecycle.quarantined())
                .sorted(Comparator.comparing(
                        lifecycle -> lifecycle.profileId().toString()
                ))
                .toList();
    }

    private Result plan(
            List<CompanionLifecycle> unresolved,
            LoadedNpcIdentitySnapshot snapshot
    ) {
        requireActiveAndSealed(snapshot);
        Map<ProfileId, CompanionProfileProjectionState> profiles =
                access.projectedProfiles();
        Map<NpcAlias, ProfileId> currentAliases = currentAliases(profiles);
        Set<LoadedNpcIdentityIndex.LoadedNpcObservation> claimed =
                new HashSet<>();
        ArrayList<Plan> plans = new ArrayList<>();
        for (CompanionLifecycle lifecycle : unresolved) {
            Plan next = planProfile(
                    lifecycle,
                    profiles,
                    currentAliases,
                    snapshot,
                    claimed
            );
            if (next == null) {
                return Result.DEFERRED;
            }
            plans.add(next);
        }
        if (!identityIndex.isMutationRevisionCurrent(
                snapshot.mutationRevision()
        )) {
            return Result.DEFERRED;
        }
        storeEvidence(new Evidence(snapshot.mutationRevision(), plans));
        return Result.COMPLETE;
    }

    private Plan planProfile(
            CompanionLifecycle lifecycle,
            Map<ProfileId, CompanionProfileProjectionState> profiles,
            Map<NpcAlias, ProfileId> currentAliases,
            LoadedNpcIdentitySnapshot snapshot,
            Set<LoadedNpcIdentityIndex.LoadedNpcObservation> claimed
    ) {
        CompanionProfileProjectionState profile =
                profiles.get(lifecycle.profileId());
        if (profile == null || profile.currentAlias() == null) {
            return null;
        }
        List<LoadedNpcIdentityIndex.LoadedNpcObservation> matches =
                matching(snapshot, profile.currentAlias());
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1 || !claimed.add(matches.getFirst())) {
            throw new IllegalStateException(
                    "world_reconciliation_duplicate_live_identity"
            );
        }
        LoadedNpcIdentityIndex.LoadedNpcObservation observation =
                matches.getFirst();
        validateMarker(lifecycle.profileId(), observation);
        NpcAlias observedAlias = observedAlias(observation);
        ProfileId existing = currentAliases.get(observedAlias);
        if (existing != null && !existing.equals(lifecycle.profileId())) {
            throw new IllegalStateException(
                    "world_reconciliation_observed_alias_conflict"
            );
        }
        return new Plan(
                lifecycle,
                profile.currentAlias(),
                observedAlias,
                observation.location().worldName(),
                clock.getAsLong()
        );
    }

    private List<LoadedNpcIdentityIndex.LoadedNpcObservation> matching(
            LoadedNpcIdentitySnapshot snapshot,
            NpcAlias alias
    ) {
        UUID expected = alias.value();
        return snapshot.observations().stream()
                .filter(observation ->
                        expected.equals(observation.componentUuid())
                                || expected.equals(observation.legacyNpcUuid()))
                .toList();
    }

    private Map<NpcAlias, ProfileId> currentAliases(
            Map<ProfileId, CompanionProfileProjectionState> profiles
    ) {
        HashMap<NpcAlias, ProfileId> aliases = new HashMap<>();
        for (CompanionProfileProjectionState profile : profiles.values()) {
            if (profile.currentAlias() != null
                    && aliases.put(profile.currentAlias(), profile.profileId())
                    != null) {
                throw new IllegalStateException(
                        "world_reconciliation_projection_alias_conflict"
                );
            }
        }
        return Map.copyOf(aliases);
    }

    private void validateMarker(
            ProfileId profileId,
            LoadedNpcIdentityIndex.LoadedNpcObservation observation
    ) {
        if (observation.projectionKey() != null
                && !profileId.toString().equals(
                observation.projectionKey().profileId()
        )) {
            throw new IllegalStateException(
                    "world_reconciliation_projection_marker_conflict"
            );
        }
    }

    private NpcAlias observedAlias(
            LoadedNpcIdentityIndex.LoadedNpcObservation observation
    ) {
        UUID value = observation.componentUuid() != null
                ? observation.componentUuid()
                : observation.legacyNpcUuid();
        return new NpcAlias(Objects.requireNonNull(value));
    }

    private CompletionStage<Result> reconcilePlans(
            Evidence current,
            int index
    ) {
        if (index >= current.plans().size()) {
            return CompletableFuture.completedFuture(Result.COMPLETE);
        }
        if (isQuiesced()) {
            return failed("world_reconciliation_quiesced");
        }
        if (!identityIndex.isMutationRevisionCurrent(
                current.mutationRevision()
        )) {
            return CompletableFuture.completedFuture(Result.DEFERRED);
        }
        Plan plan = current.plans().get(index);
        OperationIdentity identity = operationIdentity(plan);
        CompletionStage<Void> submitted = submitIfActive(plan, identity);
        if (submitted == null) {
            return failed("world_reconciliation_submission_stage_null");
        }
        return submitted.thenCompose(ignored ->
                reconcilePlans(current, index + 1));
    }

    private CompletionStage<Void> submitIfActive(
            Plan plan,
            OperationIdentity identity
    ) {
        synchronized (stateLock) {
            if (quiesced) {
                return failed("world_reconciliation_quiesced");
            }
            return access.reconcileLoaded(
                    identity.operationId(),
                    identity.idempotencyKey(),
                    plan.mutation()
            );
        }
    }

    private OperationIdentity operationIdentity(Plan plan) {
        String material = String.join(
                "|",
                IDEMPOTENCY_PREFIX,
                plan.lifecycle().profileId().toString(),
                Long.toString(plan.lifecycle().revision().value()),
                Long.toString(
                        plan.lifecycle().lastReconciledGeneration().value()
                ),
                plan.expectedAlias().toString(),
                plan.observedAlias().toString(),
                plan.worldKey(),
                Long.toString(plan.requestedAtMs())
        );
        return new OperationIdentity(
                new OperationId(UUID.nameUUIDFromBytes(
                        material.getBytes(StandardCharsets.UTF_8)
                )),
                new IdempotencyKey(
                        IDEMPOTENCY_PREFIX + Sha256Hash.ofUtf8(material)
                )
        );
    }

    private void requireActiveAndSealed(LoadedNpcIdentitySnapshot snapshot) {
        if (isQuiesced()) {
            throw new IllegalStateException("world_reconciliation_quiesced");
        }
        if (snapshot == null || !snapshot.initializationComplete()) {
            throw new IllegalStateException(
                    "world_reconciliation_evidence_not_sealed"
            );
        }
    }

    private Evidence currentEvidence() {
        synchronized (stateLock) {
            return quiesced ? null : evidence;
        }
    }

    private void storeEvidence(Evidence next) {
        synchronized (stateLock) {
            if (!quiesced) {
                evidence = next;
            }
        }
    }

    private void clearEvidence() {
        synchronized (stateLock) {
            evidence = null;
        }
    }

    private boolean isQuiesced() {
        synchronized (stateLock) {
            return quiesced;
        }
    }

    private static <T> CompletionStage<T> failed(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }

    @FunctionalInterface
    interface WorldEvidenceSource {
        CompletionStage<LoadedNpcIdentitySnapshot> awaitSealedEvidence();
    }

    interface Access {
        CompletionStage<PersistenceReadResult<List<CompanionLifecycle>>>
        findAllLifecycles();

        Map<ProfileId, CompanionProfileProjectionState> projectedProfiles();

        CompletionStage<Void> reconcileLoaded(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation.ReconcileLoaded reconciliation
        );
    }

    private record Plan(
            CompanionLifecycle lifecycle,
            NpcAlias expectedAlias,
            NpcAlias observedAlias,
            String worldKey,
            long requestedAtMs
    ) {
        private CompanionProfileMutation.ReconcileLoaded mutation() {
            return new CompanionProfileMutation.ReconcileLoaded(
                    lifecycle.profileId(),
                    lifecycle.revision(),
                    lifecycle.lastReconciledGeneration(),
                    expectedAlias,
                    observedAlias,
                    worldKey,
                    requestedAtMs
            );
        }
    }

    private record Evidence(long mutationRevision, List<Plan> plans) {
        private Evidence {
            plans = List.copyOf(plans);
        }
    }

    private record OperationIdentity(
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
    }
}
