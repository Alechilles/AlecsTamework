package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.companion.bonded.*;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticSnapshot;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Functional core API operations over the isolated store and projection services. */
public final class BondedCompanionCoreApiOperations {
    private static final long OPERATION_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private final BondedCompanionStore store;
    private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionPolicyResolver policies;
    private final BondedCompanionTransitionService transitions;
    private final BondedCompanionProjectionService projections;
    private final BondedCompanionChangePublisher changes;
    private final BondedCompanionDiagnosticContributor diagnostics;
    private final LongSupplier clock;
    private final BondedCompanionReviveOperationService revives;
    private final BondedCompanionProvisioningSupport provisioning =
            new BondedCompanionProvisioningSupport();
    private final BondedCompanionExtensionOperations extensions;
    private final BondedCompanionSnapshotCodec snapshots = new BondedCompanionSnapshotCodec();
    private final BondedCompanionViewFactory views = new BondedCompanionViewFactory();

    public BondedCompanionCoreApiOperations(
            BondedCompanionStore store,
            BondedCompanionRosterRegistry rosters,
            BondedCompanionPolicyResolver policies,
            BondedCompanionTransitionService transitions,
            BondedCompanionProjectionService projections,
            BondedCompanionChangePublisher changes,
            BondedCompanionDiagnosticContributor diagnostics,
            LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        extensions = new BondedCompanionExtensionOperations(store, clock);
        revives = new BondedCompanionReviveOperationService(
                store, rosters, policies, transitions, clock,
                new BondedCompanionCoreReviveSupport(this));
    }

    BondedCompanionResult<BondedCompanionProfileView> provision(
            BondedCompanionProvisionRequest request
    ) {
        long now = clock.getAsLong();
        BondedCompanionProvisioningSupport.Prepared prepared =
                provisioning.prepare(request, now);
        String profileId = prepared.profileId();
        BondedCompanionOperation operation = prepared.operation();
        Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>>
                prior = store.findProfileOperationByExactRequest(operation);
        if (prior.isPresent()) {
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> replay =
                    prior.get();
            return replay.code() == BondedCompanionStoreResult.Code.APPLIED
                    && replay.value() != null
                    ? success(view(replay.value()))
                    : storeFailure(replay);
        }
        long policyRevision = rosters.snapshot().revision();
        BondedCompanionPolicyResolver.Resolution resolved =
                policies.resolveForRole(
                        request.rosterId(), request.familyId(), request.roleId(),
                        policyRevision
                );
        BondedCompanionPolicy policy = resolved.policy();
        if (policy == null) return policyDenied();
        BondedCompanionSnapshot snapshot = prepared.snapshot();
        List<BondedCompanionRecord.Profile> current = store.listProfiles(
                request.ownerUuid(), request.rosterId()
        );
        var transition = transitions.createProvisioned(
                new BondedCompanionTransitionService.CreationRequest(
                        operationId(request.callerNamespace(), request.idempotencyKey()),
                        request.ownerUuid(), request.rosterId(), profileId,
                        request.roleId(), snapshot,
                        policyRevision, now, policy.familyId()
                ),
                counts(current, policy.familyId())
        );
        if (!transition.applied()) {
            return transitionFailure(transition.code());
        }
        BondedCompanionRecord.Profile profile = provisioning.storedProfile(
                request, transition.profile(), policy, now
        );
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> saved =
                store.createProfile(operation, profile, policy.maximumOwned());
        if (saved.code() != BondedCompanionStoreResult.Code.APPLIED
                || saved.value() == null) {
            return storeFailure(saved);
        }
        if (!saved.replayed()) {
            publish(saved.value(), null, BondedCompanionState.STORED,
                    "provisioned",
                    BondedCompanionChangePublisher.WorldEffectOutcome.NOT_REQUIRED);
        }
        return success(view(saved.value()));
    }

    BondedCompanionResult<BondedCompanionProfileView> summon(
            BondedCompanionActionRequest request
    ) {
        var context = request.actionContext();
        var placement = context == null ? null : context.summonPlacement();
        if (request.worldKey() == null || placement == null
                || !request.worldKey().equals(placement.worldKey())) {
            return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    "bonded-placement-context-required");
        }
        BondedCompanionRecord.Profile profile = profile(request);
        if (profile == null) return notFound();
        BondedCompanionRecord.Lease existingLease = lease(request);
        if (profile.state() == BondedCompanionState.ACTIVE
                && existingLease != null) {
            String reason = existingLease.projectionState()
                    == BondedCompanionRecord.ProjectionState.PENDING
                    ? "bonded-summon-in-progress"
                    : "bonded-summon-already-live";
            return failure(BondedCompanionResultCode.INVALID_STATE, reason);
        }
        BondedCompanionSnapshot snapshot = decode(profile);
        if (snapshot == null) return internal("bonded-snapshot-invalid");
        long now = clock.getAsLong();
        long policyRevision = rosters.snapshot().revision();
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), profile.familyId(),
                policyRevision
        );
        if (resolved.policy() == null) return policyDenied();
        var validation = transitions.summon(
                mutation(request, now, policyRevision), domain(profile, snapshot), counts(
                        store.listProfiles(request.ownerUuid(), request.rosterId()),
                        profile.familyId()
                ), "validation-lease", request.worldKey()
        );
        if (!validation.applied()) return transitionFailure(validation.code());
        BondedCompanionLease lease = validation.profile().activeLease();
        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                request.ownerUuid(), request.rosterId(), request.profileId(),
                request.expectedRevision(), profile.roleId(), snapshot,
                request.worldKey(), placement(placement), now, lease.expiresAtMs(),
                new BondedCompanionActiveCapacity(
                        profile.familyId(), resolved.policy().maximumActive()
                )
        ));
        BondedCompanionRecord.Profile refreshed = profile(request);
        if (result.status() == BondedCompanionProjectionService.SummonStatus.ACTIVE
                && refreshed != null) {
            publish(refreshed, BondedCompanionState.STORED,
                    BondedCompanionState.ACTIVE, "summoned",
                    BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED);
            return success(view(refreshed));
        }
        if (refreshed != null && refreshed.revision() != profile.revision()) {
            publish(refreshed, BondedCompanionState.STORED, refreshed.state(),
                    "summon-deferred",
                    BondedCompanionChangePublisher.WorldEffectOutcome.DEFERRED);
        }
        return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                "bonded-projection-placement-unavailable");
    }

    BondedCompanionResult<BondedCompanionProfileView> store(
            BondedCompanionActionRequest request
    ) {
        if (request.worldKey() == null) {
            return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    "bonded-world-context-unavailable");
        }
        long now = clock.getAsLong();
        BondedCompanionOperation operation = BondedCompanionStoreOperationFactory
                .create(
                        request.callerNamespace(), request.idempotencyKey(),
                        request.ownerUuid(), request.rosterId(),
                        request.profileId(), request.expectedRevision(),
                        request.worldKey(), now,
                        safeAdd(now, OPERATION_RETENTION_MS));
        Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>> prior =
                store.findProfileOperationByExactRequest(operation);
        if (prior.isPresent()) {
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> replay =
                    prior.get();
            return replay.code() == BondedCompanionStoreResult.Code.APPLIED
                    && replay.value() != null
                    ? success(view(replay.value()))
                    : storeFailure(replay);
        }
        BondedCompanionRecord.Profile profile = profile(request);
        if (profile == null) return notFound();
        BondedCompanionRecord.Lease lease = lease(request);
        if (lease == null || !request.worldKey().equals(lease.worldKey())) {
            return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    "bonded-world-context-unavailable");
        }
        var result = projections.store(new BondedCompanionProjectionService.StoreRequest(
                expectation(profile, lease), request.expectedRevision(), now,
                operation
        ));
        BondedCompanionRecord.Profile refreshed = profile(request);
        if ((result.status() == BondedCompanionProjectionService.StoreStatus.STORED
                || result.status() == BondedCompanionProjectionService.StoreStatus.STORED_CLEANUP_PENDING)
                && refreshed != null) {
            publish(refreshed, BondedCompanionState.ACTIVE,
                    BondedCompanionState.STORED, "stored",
                    result.status() == BondedCompanionProjectionService.StoreStatus.STORED
                            ? BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
                            : BondedCompanionChangePublisher.WorldEffectOutcome.DEFERRED);
            return success(view(refreshed));
        }
        return failure(result.status() == BondedCompanionProjectionService.StoreStatus.PROJECTION_NOT_FOUND
                ? BondedCompanionResultCode.WORLD_UNAVAILABLE
                : BondedCompanionResultCode.REVISION_CONFLICT,
                "bonded-store-not-committed");
    }

    BondedCompanionResult<BondedCompanionReviveQuote> quoteRevive(
            BondedCompanionActionRequest request
    ) {
        BondedCompanionRecord.Profile profile = profile(request);
        if (profile == null) return notFound();
        long revision = rosters.snapshot().revision();
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), profile.familyId(), revision
        );
        if (resolved.policy() == null) return policyDenied();
        BondedCompanionPolicy policy = resolved.policy();
        BondedCompanionPolicy.RevivePrice price = policy.revivePrice();
        List<BondedCompanionReviveQuote.CostLine> costs = price == null
                ? List.of() : quoteCosts(request, price);
        return success(new BondedCompanionReviveQuote(
                profile.profileId(), policy.features().revive(),
                costs, 0L,
                revision
        ));
    }

    CompletionStage<BondedCompanionResult<BondedCompanionProfileView>> reviveAsync(
            BondedCompanionReviveRequest request
    ) {
        return revives.revive(request);
    }

    BondedCompanionProfileView view(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease,
            List<BondedCompanionRecord.Profile> rosterProfiles
    ) {
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), profile.familyId(),
                rosters.snapshot().revision());
        BondedCompanionPolicy policy = resolved.policy();
        boolean matches = policy != null
                && policy.rosterId().equals(profile.rosterId())
                && policy.familyId().equals(profile.familyId())
                && policy.allowedRoles().contains(profile.roleId());
        int active = counts(rosterProfiles, profile.familyId()).active();
        Map<String, String> extensions = new LinkedHashMap<>();
        List<BondedCompanionRecord.ExtensionData> storedExtensions =
                store.listExtensionData(
                        profile.ownerUuid(), profile.rosterId(),
                        profile.profileId());
        if (storedExtensions != null) {
            storedExtensions.forEach(extension -> extensions.put(
                    extension.namespace(),
                    new String(extension.payload().bytes(),
                            StandardCharsets.UTF_8)));
        }
        return views.view(profile, lease,
                matches && profile.state() == BondedCompanionState.STORED
                        && policy.features().summon()
                        && cooldownRemaining(
                                profile.summonCooldownUntilMs(),
                                clock.getAsLong()) == 0L
                        && BondedCompanionFamilyScope.hasActiveCapacity(
                                active, policy.maximumActive()),
                matches && profile.state() == BondedCompanionState.ACTIVE
                        && policy.features().dismiss(),
                matches && profile.state() == BondedCompanionState.DEAD
                        && policy.features().revive(), extensions);
    }

    BondedCompanionResult<BondedCompanionExtensionData> extension(
            BondedCompanionExtensionDataKey key
    ) {
        return extensions.find(key).map(this::success)
                .orElseGet(this::notFound);
    }

    BondedCompanionResult<BondedCompanionExtensionData> updateExtension(
            BondedCompanionExtensionDataUpdate update
    ) {
        BondedCompanionStoreResult<BondedCompanionExtensionData> saved =
                extensions.update(update);
        return saved.code() == BondedCompanionStoreResult.Code.APPLIED
                && saved.value() != null ? success(saved.value())
                : storeFailure(saved);
    }

    BondedCompanionSnapshot decode(BondedCompanionRecord.Profile profile) {
        var decoded = snapshots.decode(new String(profile.snapshot().bytes(), StandardCharsets.UTF_8));
        return decoded.status() == BondedCompanionSnapshotCodec.Status.FOUND
                ? decoded.snapshot() : null;
    }

    BondedCompanionProfile domain(BondedCompanionRecord.Profile profile,
            BondedCompanionSnapshot snapshot) {
        BondedCompanionRecord.Lease lease = lease(profile.ownerUuid(), profile.rosterId(), profile.profileId());
        BondedCompanionLease active = lease == null ? null : new BondedCompanionLease(
                lease.leaseToken(), lease.worldKey(), lease.startedAtMs(), lease.expiresAtMs());
        return new BondedCompanionProfile(profile.profileId(), profile.ownerUuid(),
                profile.rosterId(), profile.familyId(), profile.roleId(), profile.state(),
                profile.revision(), snapshot, active, profile.summonCooldownUntilMs(),
                profile.diedAtMs(), profile.reviveCount());
    }

    private BondedCompanionProjectionValidator.LeaseExpectation expectation(
            BondedCompanionRecord.Profile profile, BondedCompanionRecord.Lease lease) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                profile.ownerUuid(), profile.rosterId(), profile.profileId(),
                lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey(),
                lease.startedAtMs(), lease.expiresAtMs(),
                BondedCompanionProjectionValidator.LeasePhase.valueOf(
                        lease.projectionState().name()));
    }

    BondedCompanionTransitionService.MutationRequest mutation(
            BondedCompanionActionRequest request,
            long now,
            long policyRevision
    ) {
        return new BondedCompanionTransitionService.MutationRequest(
                operationId(request.callerNamespace(), request.idempotencyKey()),
                request.ownerUuid(), request.expectedRevision(),
                policyRevision, now);
    }

    private BondedCompanionTransitionService.RosterCounts counts(
            List<BondedCompanionRecord.Profile> profiles,
            String familyId) {
        return BondedCompanionFamilyScope.counts(profiles, familyId);
    }

    BondedCompanionRecord.Profile profile(BondedCompanionActionRequest request) {
        return store.findProfile(request.ownerUuid(), request.rosterId(), request.profileId())
                .orElse(null);
    }

    private BondedCompanionRecord.Lease lease(BondedCompanionActionRequest request) {
        return lease(request.ownerUuid(), request.rosterId(), request.profileId());
    }

    private BondedCompanionRecord.Lease lease(UUID owner, String roster, String profileId) {
        return store.findActiveLeases(owner, roster).stream()
                .filter(candidate -> profileId.equals(candidate.profileId()))
                .findFirst().orElse(null);
    }

    BondedCompanionProfileView view(BondedCompanionRecord.Profile profile) {
        return view(profile,
                lease(profile.ownerUuid(), profile.rosterId(), profile.profileId()),
                store.listProfiles(profile.ownerUuid(), profile.rosterId()));
    }

    private List<BondedCompanionReviveQuote.CostLine> quoteCosts(
            BondedCompanionActionRequest request,
            BondedCompanionPolicy.RevivePrice price
    ) {
        BondedCompanionActionContext.Inventory inventory = inventory(
                request.actionContext());
        if (inventory == null) return unavailableCosts(price);
        ArrayList<BondedCompanionReviveQuote.CostLine> lines = new ArrayList<>();
        try {
            String operationId = BondedCompanionPaymentOperationId.create(
                    request.callerNamespace(), request.idempotencyKey(),
                    request.ownerUuid(), request.rosterId(), request.profileId(),
                    request.expectedRevision());
            List<Integer> owned = inventory.availableQuantities(operationId,
                    price.costs());
            for (int index = 0; index < price.costs().size(); index++) {
                BondedCompanionReviveCost cost = price.costs().get(index);
                lines.add(new BondedCompanionReviveQuote.CostLine(
                        cost.itemId(), cost.quantity(), Math.max(0,
                        owned.get(index))));
            }
            return List.copyOf(lines);
        } catch (RuntimeException | LinkageError failure) {
            return unavailableCosts(price);
        }
    }

    private List<BondedCompanionReviveQuote.CostLine> unavailableCosts(
            BondedCompanionPolicy.RevivePrice price
    ) {
        return price.costs().stream().map(cost ->
                new BondedCompanionReviveQuote.CostLine(
                        cost.itemId(), cost.quantity(), 0)).toList();
    }

    private BondedCompanionActionContext.Inventory inventory(
            BondedCompanionActionContext context
    ) {
        return context == null ? null : context.inventory();
    }

    BondedCompanionOperation reviveOperation(
            BondedCompanionActionRequest action,
            BondedCompanionPolicy.RevivePrice price,
            long now
    ) {
        return BondedCompanionRevivePaymentProof.operation(
                action.callerNamespace(), action.idempotencyKey(),
                action.ownerUuid(), action.rosterId(), action.profileId(),
                price.costs(), now,
                safeAdd(now, OPERATION_RETENTION_MS));
    }

    private long safeAdd(long value, long increment) {
        try { return Math.addExact(value, increment); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    long cooldownRemaining(long until, long now) {
        if (until == 0L || now >= until) return 0L;
        long delta;
        try { delta = Math.subtractExact(until, now); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        return Math.max(1L, (delta + 999L) / 1000L);
    }

    void publish(BondedCompanionRecord.Profile profile,
            @Nullable BondedCompanionState oldState, BondedCompanionState newState,
            String reason, BondedCompanionChangePublisher.WorldEffectOutcome outcome) {
        changes.publishCommitted(new BondedCompanionChangedEvent(profile.profileId(),
                profile.ownerUuid(), profile.rosterId(), state(oldState),
                state(newState),
                profile.revision(), reason), outcome);
    }

    private com.alechilles.alecstamework.companion.placement
            .CompanionSpawnPlacement placement(
                    BondedCompanionPlacement placement) {
        return new com.alechilles.alecstamework.companion.placement
                .CompanionSpawnPlacement(
                placement.worldKey(), placement.x(), placement.y(), placement.z(),
                placement.pitchRadians(), placement.yawRadians(),
                placement.rollRadians());
    }

    private BondedCompanionStateView state(
            @Nullable BondedCompanionState state) {
        return state == null ? null : BondedCompanionStateView.valueOf(
                state.name());
    }

    <T> BondedCompanionResult<T> success(T value) {
        return new BondedCompanionResult<>(BondedCompanionResultCode.SUCCESS, value, null);
    }
    <T> BondedCompanionResult<T> failure(BondedCompanionResultCode code, String reason) {
        return new BondedCompanionResult<>(code, null, reason);
    }
    <T> BondedCompanionResult<T> notFound() {
        return failure(BondedCompanionResultCode.NOT_FOUND, "bonded-profile-not-found");
    }
    <T> BondedCompanionResult<T> policyDenied() {
        return failure(BondedCompanionResultCode.POLICY_DENIED, "bonded-policy-denied");
    }
    <T> BondedCompanionResult<T> internal(String reason) {
        diagnostics.recordFailure(BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE);
        return failure(BondedCompanionResultCode.INTERNAL_FAILURE, reason);
    }
    <T> BondedCompanionResult<T> transitionFailure(
            BondedCompanionTransitionService.ResultCode code) {
        BondedCompanionResultCode mapped = switch (code) {
            case NOT_OWNER -> BondedCompanionResultCode.NOT_OWNER;
            case REVISION_CONFLICT, POLICY_REVISION_CONFLICT -> BondedCompanionResultCode.REVISION_CONFLICT;
            case INVALID_STATE -> BondedCompanionResultCode.INVALID_STATE;
            case VALIDATION_FAILED, SNAPSHOT_OWNER_MISMATCH, SNAPSHOT_ROLE_MISMATCH -> BondedCompanionResultCode.VALIDATION_FAILED;
            default -> BondedCompanionResultCode.POLICY_DENIED;
        };
        return failure(mapped, "bonded-transition-" + code.name().toLowerCase(Locale.ROOT));
    }
    <T> BondedCompanionResult<T> storeFailure(BondedCompanionStoreResult<?> result) {
        BondedCompanionResultCode mapped = switch (result.code()) {
            case NOT_FOUND -> BondedCompanionResultCode.NOT_FOUND;
            case NOT_OWNER -> BondedCompanionResultCode.NOT_OWNER;
            case REVISION_CONFLICT, IDEMPOTENCY_CONFLICT, CONFLICT -> BondedCompanionResultCode.REVISION_CONFLICT;
            case INVALID_STATE -> BondedCompanionResultCode.INVALID_STATE;
            case VALIDATION_FAILED -> BondedCompanionResultCode.VALIDATION_FAILED;
            case STORAGE_FAILURE -> BondedCompanionResultCode.INTERNAL_FAILURE;
            case APPLIED -> BondedCompanionResultCode.INTERNAL_FAILURE;
        };
        return failure(mapped, result.reason() == null ? "bonded-storage-mutation-failed" : result.reason());
    }

    private static String operationId(String namespace, String key) {
        return namespace + ":" + key;
    }
}
