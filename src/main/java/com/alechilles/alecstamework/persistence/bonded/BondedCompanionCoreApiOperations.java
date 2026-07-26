package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.companion.bonded.*;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticSnapshot;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
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
    }

    BondedCompanionResult<BondedCompanionProfileView> provision(
            BondedCompanionProvisionRequest request
    ) {
        long now = clock.getAsLong();
        String profileId = stableProfileId(request);
        BondedCompanionSnapshot snapshot = provisionedSnapshot(
                request, profileId, now
        );
        List<BondedCompanionRecord.Profile> current = store.listProfiles(
                request.ownerUuid(), request.rosterId()
        );
        var transition = transitions.createProvisioned(
                new BondedCompanionTransitionService.CreationRequest(
                        operationId(request.callerNamespace(), request.idempotencyKey()),
                        request.ownerUuid(), request.rosterId(), profileId,
                        request.roleId(), snapshot,
                        request.expectedRosterRevision(), now
                ),
                counts(current)
        );
        if (!transition.applied()) {
            return transitionFailure(transition.code());
        }
        BondedCompanionPolicy policy = policies.resolve(
                request.rosterId(), request.expectedRosterRevision()
        ).policy();
        BondedCompanionRecord.Profile profile = storedProfile(
                request, transition.profile(), policy, now
        );
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> saved =
                store.createProfile(operation(request, profileId, now), profile);
        if (saved.code() != BondedCompanionStoreResult.Code.APPLIED
                || saved.value() == null) {
            return storeFailure(saved);
        }
        if (!saved.replayed()) {
            publish(saved.value(), null, BondedCompanionState.STORED,
                    "provisioned",
                    BondedCompanionChangePublisher.WorldEffectOutcome.NOT_REQUIRED);
        }
        return success(views.view(saved.value(), null));
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
        BondedCompanionSnapshot snapshot = decode(profile);
        if (snapshot == null) return internal("bonded-snapshot-invalid");
        long now = clock.getAsLong();
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), rosters.snapshot().revision()
        );
        if (resolved.policy() == null) return policyDenied();
        var validation = transitions.summon(
                mutation(request, now), domain(profile, snapshot), counts(
                        store.listProfiles(request.ownerUuid(), request.rosterId())
                ), "validation-lease", request.worldKey()
        );
        if (!validation.applied()) return transitionFailure(validation.code());
        BondedCompanionLease lease = validation.profile().activeLease();
        var result = projections.summon(new BondedCompanionProjectionService.SummonRequest(
                request.ownerUuid(), request.rosterId(), request.profileId(),
                request.expectedRevision(), profile.roleId(), snapshot,
                request.worldKey(), placement, now, lease.expiresAtMs()
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
        BondedCompanionRecord.Profile profile = profile(request);
        if (profile == null) return notFound();
        BondedCompanionRecord.Lease lease = lease(request);
        if (lease == null || request.worldKey() == null
                || !request.worldKey().equals(lease.worldKey())) {
            return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    "bonded-world-context-unavailable");
        }
        var result = projections.store(new BondedCompanionProjectionService.StoreRequest(
                expectation(profile, lease), request.expectedRevision(), clock.getAsLong()
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
            return success(views.view(refreshed, null));
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
                profile.rosterId(), revision
        );
        if (resolved.policy() == null) return policyDenied();
        BondedCompanionPolicy policy = resolved.policy();
        BondedCompanionPolicy.RevivePrice price = policy.revivePrice();
        long remaining = cooldownRemaining(profile.reviveCooldownUntilMs(), clock.getAsLong());
        boolean affordable = price != null && hasPayment(
                request.actionContext(), price);
        return success(new BondedCompanionReviveQuote(
                profile.profileId(), policy.features().revive(),
                price == null ? null : price.itemId(),
                price == null ? 0 : price.quantity(), affordable, remaining,
                revision
        ));
    }

    BondedCompanionResult<BondedCompanionProfileView> revive(
            BondedCompanionReviveRequest request
    ) {
        BondedCompanionActionRequest action = request.action();
        BondedCompanionRecord.Profile profile = profile(action);
        if (profile == null) return notFound();
        if (request.quoteRevision() != rosters.snapshot().revision()) {
            return failure(BondedCompanionResultCode.REVISION_CONFLICT,
                    "bonded-revive-quote-stale");
        }
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), request.quoteRevision());
        BondedCompanionPolicy policy = resolved.policy();
        if (policy == null || policy.revivePrice() == null) {
            return policyDenied();
        }
        if (cooldownRemaining(profile.reviveCooldownUntilMs(),
                clock.getAsLong()) > 0L) {
            return failure(BondedCompanionResultCode.POLICY_DENIED,
                    "bonded-revive-cooldown-active");
        }
        BondedCompanionPolicy.RevivePrice price = policy.revivePrice();
        BondedCompanionSnapshot snapshot = decode(profile);
        if (snapshot == null) return internal("bonded-snapshot-invalid");
        var validation = transitions.revive(
                mutation(action, clock.getAsLong()), domain(profile, snapshot),
                new BondedCompanionTransitionService.RevivePayment(
                        price.itemId(), price.quantity()));
        if (!validation.applied()) {
            return transitionFailure(validation.code());
        }
        BondedCompanionActionContext.Inventory inventory =
                inventory(action.actionContext());
        String reviveOperationId = operationId(
                action.callerNamespace(), action.idempotencyKey());
        BondedCompanionActionContext.ChargeReceipt charge = inventory == null
                ? null : safeConsume(inventory, reviveOperationId,
                        price.itemId(), price.quantity());
        if (charge == null) {
            return failure(BondedCompanionResultCode.POLICY_DENIED,
                    "bonded-revive-payment-unavailable");
        }
        if (!safeChargeMatches(charge, reviveOperationId)) {
            if (!safeRefund(charge)) {
                return internal("bonded-revive-payment-compensation-failed");
            }
            return internal("bonded-revive-payment-receipt-invalid");
        }
        long now = clock.getAsLong();
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> saved =
                store.reviveProfile(operation(
                        action.callerNamespace(), action.idempotencyKey(),
                        action.ownerUuid(), action.rosterId(), action.profileId(),
                        BondedCompanionOperation.Type.REVIVE,
                        price.itemId() + ":" + price.quantity(), now),
                        action.expectedRevision(), now);
        if (saved.code() != BondedCompanionStoreResult.Code.APPLIED
                || saved.value() == null) {
            if (!safeRefund(charge)) {
                return internal("bonded-revive-payment-compensation-failed");
            }
            return storeFailure(saved);
        }
        if (saved.replayed() && !safeRefund(charge)) {
            return internal("bonded-revive-payment-compensation-failed");
        }
        if (!saved.replayed()) {
            publish(saved.value(), BondedCompanionState.DEAD,
                    BondedCompanionState.STORED, "revived",
                    BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED);
        }
        return success(view(saved.value()));
    }

    BondedCompanionProfileView view(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease,
            List<BondedCompanionRecord.Profile> rosterProfiles
    ) {
        BondedCompanionPolicyResolver.Resolution resolved = policies.resolve(
                profile.rosterId(), rosters.snapshot().revision());
        BondedCompanionPolicy policy = resolved.policy();
        boolean matches = policy != null
                && policy.rosterId().equals(profile.rosterId())
                && policy.familyId().equals(profile.familyId())
                && policy.allowedRoles().contains(profile.roleId());
        int active = (int) rosterProfiles.stream().filter(candidate ->
                candidate.state() == BondedCompanionState.ACTIVE).count();
        return views.view(profile, lease,
                matches && profile.state() == BondedCompanionState.STORED
                        && policy.features().summon()
                        && active < policy.maximumActive(),
                matches && profile.state() == BondedCompanionState.ACTIVE
                        && policy.features().dismiss(),
                matches && profile.state() == BondedCompanionState.DEAD
                        && policy.features().revive());
    }

    BondedCompanionResult<BondedCompanionExtensionData> extension(
            BondedCompanionExtensionDataKey key
    ) {
        BondedCompanionRecord.Profile profile = profile(key.ownerUuid(), key.profileId());
        if (profile == null) return notFound();
        return store.findExtensionData(key.ownerUuid(), profile.rosterId(),
                        key.profileId(), key.namespace())
                .map(value -> extensionView(value, key.ownerUuid()))
                .map(this::success).orElseGet(this::notFound);
    }

    BondedCompanionResult<BondedCompanionExtensionData> updateExtension(
            BondedCompanionExtensionDataUpdate update
    ) {
        JsonParser.parseString(update.jsonPayload());
        BondedCompanionRecord.Profile profile = profile(
                update.key().ownerUuid(), update.key().profileId()
        );
        if (profile == null) return notFound();
        Optional<BondedCompanionRecord.ExtensionData> current =
                store.findExtensionData(update.key().ownerUuid(), profile.rosterId(),
                        profile.profileId(), update.key().namespace());
        long expected = current.isEmpty() && update.expectedRevision() == 0L
                ? -1L : update.expectedRevision();
        long revision = current.isEmpty() ? 0L : expected + 1L;
        long now = clock.getAsLong();
        BondedCompanionRecord.ExtensionData extension = new BondedCompanionRecord.ExtensionData(
                profile.profileId(), update.key().namespace(),
                BondedCompanionPayload.of(update.jsonPayload().getBytes(StandardCharsets.UTF_8)),
                revision, now
        );
        BondedCompanionStoreResult<BondedCompanionRecord.ExtensionData> saved =
                store.compareAndSetExtensionData(operation(
                        "extension", update.key().namespace() + ":" + revision,
                        update.key().ownerUuid(), profile.rosterId(), profile.profileId(),
                        BondedCompanionOperation.Type.STORE, update.jsonPayload(), now
                ), extension, expected);
        return saved.code() == BondedCompanionStoreResult.Code.APPLIED
                && saved.value() != null ? success(extensionView(
                        saved.value(), update.key().ownerUuid()))
                : storeFailure(saved);
    }

    private BondedCompanionRecord.Profile storedProfile(BondedCompanionProvisionRequest request,
            BondedCompanionProfile domain, BondedCompanionPolicy policy, long now) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("policyRevision", Long.toString(policy.revision()));
        request.snapshotPresentationData().forEach(
                (key, value) -> metadata.put("presentation:" + key, value)
        );
        return new BondedCompanionRecord.Profile(domain.profileId(), domain.ownerUuid(),
                domain.rosterId(), domain.familyId(), domain.roleId(), domain.state(),
                domain.revision(), payload(domain.snapshot()), now, now, metadata,
                request.displayName(), request.species(), request.gender(), null,
                0L, 0L, null, null);
    }

    private BondedCompanionSnapshot provisionedSnapshot(BondedCompanionProvisionRequest request,
            String profileId, long now) {
        UUID source = UUID.nameUUIDFromBytes(("bonded:" + profileId)
                .getBytes(StandardCharsets.UTF_8));
        TameworkNpcNameComponent name = request.displayName() == null ? null
                : new TameworkNpcNameComponent(request.displayName(), request.ownerUuid(), now,
                TameworkNpcNameComponent.NameSource.System);
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                source, null, -1, request.roleId(), null,
                new TameworkOwnerComponent(request.ownerUuid(), null),
                new TameworkTamedComponent(true), name, null, null, null,
                null, null, null, null, null, null, now
        ), Map.of());
    }

    private BondedCompanionPayload payload(BondedCompanionSnapshot snapshot) {
        return BondedCompanionPayload.of(snapshots.encode(snapshot)
                .getBytes(StandardCharsets.UTF_8));
    }

    private BondedCompanionSnapshot decode(BondedCompanionRecord.Profile profile) {
        var decoded = snapshots.decode(new String(profile.snapshot().bytes(), StandardCharsets.UTF_8));
        return decoded.status() == BondedCompanionSnapshotCodec.Status.FOUND
                ? decoded.snapshot() : null;
    }

    private BondedCompanionProfile domain(BondedCompanionRecord.Profile profile,
            BondedCompanionSnapshot snapshot) {
        BondedCompanionRecord.Lease lease = lease(profile.ownerUuid(), profile.rosterId(), profile.profileId());
        BondedCompanionLease active = lease == null ? null : new BondedCompanionLease(
                lease.leaseToken(), lease.worldKey(), lease.startedAtMs(), lease.expiresAtMs());
        return new BondedCompanionProfile(profile.profileId(), profile.ownerUuid(),
                profile.rosterId(), profile.familyId(), profile.roleId(), profile.state(),
                profile.revision(), snapshot, active, profile.reviveCooldownUntilMs(),
                profile.diedAtMs(), profile.reviveCount(), BondedCompanionOperationLedger.empty());
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

    private BondedCompanionTransitionService.MutationRequest mutation(
            BondedCompanionActionRequest request, long now) {
        return new BondedCompanionTransitionService.MutationRequest(
                operationId(request.callerNamespace(), request.idempotencyKey()),
                request.ownerUuid(), request.expectedRevision(),
                rosters.snapshot().revision(), now);
    }

    private BondedCompanionTransitionService.RosterCounts counts(
            List<BondedCompanionRecord.Profile> profiles) {
        return new BondedCompanionTransitionService.RosterCounts(profiles.size(),
                (int) profiles.stream().filter(profile -> profile.state()
                        == BondedCompanionState.ACTIVE).count());
    }

    private BondedCompanionRecord.Profile profile(BondedCompanionActionRequest request) {
        return store.findProfile(request.ownerUuid(), request.rosterId(), request.profileId())
                .orElse(null);
    }

    private BondedCompanionRecord.Profile profile(UUID owner, String profileId) {
        for (String roster : rosters.snapshot().byRosterId().keySet()) {
            Optional<BondedCompanionRecord.Profile> found = store.findProfile(owner, roster, profileId);
            if (found.isPresent()) return found.get();
        }
        return null;
    }

    private BondedCompanionRecord.Lease lease(BondedCompanionActionRequest request) {
        return lease(request.ownerUuid(), request.rosterId(), request.profileId());
    }

    private BondedCompanionRecord.Lease lease(UUID owner, String roster, String profileId) {
        return store.findActiveLeases(owner, roster).stream()
                .filter(candidate -> profileId.equals(candidate.profileId()))
                .findFirst().orElse(null);
    }

    private BondedCompanionProfileView view(BondedCompanionRecord.Profile profile) {
        return view(profile,
                lease(profile.ownerUuid(), profile.rosterId(), profile.profileId()),
                store.listProfiles(profile.ownerUuid(), profile.rosterId()));
    }

    private boolean hasPayment(
            BondedCompanionActionContext context,
            BondedCompanionPolicy.RevivePrice price
    ) {
        BondedCompanionActionContext.Inventory inventory = inventory(context);
        if (inventory == null) return false;
        try {
            return inventory.availableQuantity(price.itemId())
                    >= price.quantity();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private BondedCompanionActionContext.Inventory inventory(
            BondedCompanionActionContext context
    ) {
        return context == null ? null : context.inventory();
    }

    private BondedCompanionActionContext.ChargeReceipt safeConsume(
            BondedCompanionActionContext.Inventory inventory,
            String operationId, String itemId,
            int quantity
    ) {
        try {
            return inventory.consumeExact(operationId, itemId, quantity);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private boolean safeRefund(
            BondedCompanionActionContext.ChargeReceipt receipt
    ) {
        try {
            return receipt.refund();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean safeChargeMatches(
            BondedCompanionActionContext.ChargeReceipt receipt,
            String operationId
    ) {
        try {
            return operationId.equals(receipt.operationId());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private String stableProfileId(BondedCompanionProvisionRequest request) {
        return UUID.nameUUIDFromBytes((request.callerNamespace() + "\0"
                + request.ownerUuid() + "\0" + request.rosterId() + "\0"
                + request.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private BondedCompanionOperation operation(BondedCompanionProvisionRequest request,
            String profileId, long now) {
        return operation(request.callerNamespace(), request.idempotencyKey(),
                request.ownerUuid(), request.rosterId(), profileId,
                BondedCompanionOperation.Type.PROVISION,
                provisionPayload(request), now);
    }

    private String provisionPayload(BondedCompanionProvisionRequest request) {
        StringBuilder payload = new StringBuilder();
        appendField(payload, request.ownerUuid().toString());
        appendField(payload, request.rosterId());
        appendField(payload, request.roleId());
        appendField(payload, request.displayName());
        appendField(payload, request.species());
        appendField(payload, request.gender());
        appendField(payload, Long.toString(request.expectedRosterRevision()));
        new TreeMap<>(request.snapshotPresentationData()).forEach((key, value) -> {
            appendField(payload, key);
            appendField(payload, value);
        });
        return payload.toString();
    }

    private void appendField(StringBuilder target, @Nullable String value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        target.append(value.length()).append(':').append(value);
    }

    private BondedCompanionOperation operation(String namespace, String key, UUID owner,
            String roster, String profileId, BondedCompanionOperation.Type type,
            String payload, long now) {
        return new BondedCompanionOperation(namespace, key, sha256(payload), owner,
                roster, profileId, type, now, safeAdd(now, OPERATION_RETENTION_MS));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long safeAdd(long value, long increment) {
        try { return Math.addExact(value, increment); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private long cooldownRemaining(long until, long now) {
        if (until == 0L || now >= until) return 0L;
        long delta;
        try { delta = Math.subtractExact(until, now); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        return Math.max(1L, (delta + 999L) / 1000L);
    }

    private BondedCompanionExtensionData extensionView(
            BondedCompanionRecord.ExtensionData value,
            UUID ownerUuid
    ) {
        return new BondedCompanionExtensionData(new BondedCompanionExtensionDataKey(
                ownerUuid, value.profileId(), value.namespace()),
                new String(value.payload().bytes(), StandardCharsets.UTF_8),
                value.revision(), value.updatedAtMs());
    }

    private void publish(BondedCompanionRecord.Profile profile,
            @Nullable BondedCompanionState oldState, BondedCompanionState newState,
            String reason, BondedCompanionChangePublisher.WorldEffectOutcome outcome) {
        changes.publishCommitted(new BondedCompanionChangedEvent(profile.profileId(),
                profile.ownerUuid(), profile.rosterId(), oldState, newState,
                profile.revision(), reason), outcome);
    }

    private <T> BondedCompanionResult<T> success(T value) {
        return new BondedCompanionResult<>(BondedCompanionResultCode.SUCCESS, value, null);
    }
    private <T> BondedCompanionResult<T> failure(BondedCompanionResultCode code, String reason) {
        return new BondedCompanionResult<>(code, null, reason);
    }
    private <T> BondedCompanionResult<T> notFound() {
        return failure(BondedCompanionResultCode.NOT_FOUND, "bonded-profile-not-found");
    }
    private <T> BondedCompanionResult<T> policyDenied() {
        return failure(BondedCompanionResultCode.POLICY_DENIED, "bonded-policy-denied");
    }
    private <T> BondedCompanionResult<T> internal(String reason) {
        diagnostics.recordFailure(BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE);
        return failure(BondedCompanionResultCode.INTERNAL_FAILURE, reason);
    }
    private <T> BondedCompanionResult<T> transitionFailure(
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
    private <T> BondedCompanionResult<T> storeFailure(BondedCompanionStoreResult<?> result) {
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
