package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionCaptureEvidenceView;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.flow.RosterSummons;
import com.alechilles.alecstamework.companion.flow.StoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The bonded companion API over the companion index (plan 6 R13 to R20). A bonded companion is a
 * {@link CompanionRecord} with {@code bonded = true} and a roster id; its family comes from the
 * roster config and its role ({@link BondedRecords}). The view's {@code revision}, and every
 * request's {@code expectedRevision}, is the record generation.
 *
 * <p>Reads are lock-free index reads. Summon and revive go through {@link RestoreFlow}, store
 * through {@link StoreFlow}: the record changes under the index lock, the owner file is written,
 * and only then does a body appear or go. The family's active limit is checked before the flow
 * for a clear reason, and again under the index lock by the restore's admission check
 * ({@link BondedAdmission#withFamilyCaps}), so two summons at once cannot both pass it.
 *
 * <p>A companion comes into a roster by {@link #provision} (a record with no body and no
 * snapshot, whose first summon builds the body from its role, plan 6 R16) or by a capture into
 * storage ({@code CaptureFlow} with a bonded target), which leaves the evidence
 * {@link #findCapture} reads.
 *
 * <p>Every method returns at once and may be called from any thread. Futures complete on the
 * thread that finishes the flow (usually the companion writer thread); nothing here touches a
 * live entity. A caller that needs a world thread hops to it itself.
 *
 * <p>Owner: {@code Tamework} builds one per ready companion module and closes it with the public
 * API; {@link #close} drops every subscriber and makes later calls report unavailable.
 */
public final class IndexBondedCompanionApi implements BondedCompanionApi, AutoCloseable {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String CLOSED = "bonded-companion-authority-closed";
    static final String NOT_FOUND = "bonded-profile-not-found";
    static final String NOT_OWNER = "bonded-transition-not_owner";
    static final String INVALID_STATE = "bonded-transition-invalid_state";
    static final String REVISION_CONFLICT = "bonded-transition-revision_conflict";
    static final String ROLE_NOT_ALLOWED = "bonded-transition-role_not_allowed";
    static final String FEATURE_DISABLED = "bonded-transition-feature_disabled";
    static final String COOLDOWN_ACTIVE = "bonded-transition-cooldown_active";
    static final String ACTIVE_CAPACITY = "bonded-transition-active_capacity_reached";
    static final String OWNED_CAPACITY = "bonded-transition-owned_capacity_reached";
    static final String POLICY_DENIED = "bonded-policy-denied";
    static final String PLACEMENT_REQUIRED = "bonded-placement-context-required";
    static final String PLACEMENT_UNAVAILABLE = "bonded-projection-placement-unavailable";
    static final String ALREADY_LIVE = "bonded-summon-already-live";
    static final String SNAPSHOT_INVALID = "bonded-snapshot-invalid";
    static final String STORE_NOT_COMMITTED = "bonded-store-not-committed";
    static final String REQUEST_INVALID = "bonded-request-invalid";
    static final String OPERATION_FAILED = "bonded-operation-failed";
    static final String QUOTE_STALE = "bonded-revive-quote-stale";
    static final String PAYMENT_UNAVAILABLE = "bonded-revive-payment-unavailable";
    static final String PAYMENT_INSUFFICIENT = "bonded-revive-payment-insufficient";
    static final String PROFILE_REVISION_CONFLICT = "bonded-profile-revision-conflict";
    static final String EXTENSION_REVISION_CONFLICT = "bonded-extension-revision-conflict";
    static final String CAPTURE_EVIDENCE_NOT_FOUND = "bonded-capture-evidence-not-found";
    /** Release cause of an abandoned bonded companion's tombstone. */
    public static final String CAUSE_ABANDONED = "ABANDONED";

    /**
     * The family's timers for one companion with its talent modifiers applied (plan 6 R18). The
     * future may complete on any thread and must not fail for a missing snapshot: it then
     * completes with {@code family} unchanged.
     */
    @FunctionalInterface
    public interface Timers {
        @Nonnull
        CompletableFuture<BondedCompanionPolicy> adjusted(@Nonnull CompanionRecord record,
                                                          @Nonnull BondedCompanionPolicy family);
    }

    /**
     * Unregisters a profile's loaded body. Called under the index lock, so the body's removal is
     * not seen as a loss. Returns the action that removes the body from its world (it must hop
     * to the body's world thread itself), or null when no body is loaded.
     */
    @FunctionalInterface
    public interface Bodies {
        @Nullable
        Runnable unregister(@Nonnull UUID profileId);
    }

    private final CompanionIndex index;
    private final BondedRecords.Families families;
    private final Function<RestoreFlow.Request, CompletableFuture<RestoreFlow.Result>> restore;
    private final RosterSummons.Store store;
    private final Function<UUID, CompletableFuture<Void>> flush;
    private final Bodies bodies;
    private final Consumer<UUID> deleteSnapshot;
    private final Timers timers;
    private final LongSupplier clock;
    private final List<Consumer<BondedCompanionChangedEvent>> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /**
     * @param families       the roster family of a record's role; {@link BondedRecords#families}
     * @param restore        {@link RestoreFlow#restore(RestoreFlow.Request)}, built with
     *                       {@link BondedAdmission#withFamilyCaps} as its admission check
     * @param store          {@link StoreFlow#store}
     * @param flush          writes an owner's file to disk; {@code CompanionWriter::flushNow}
     * @param bodies         {@link #bodies(LoadedBodies, Consumer)}
     * @param deleteSnapshot queues a profile's snapshot delete; {@code CompanionWriter::queueSnapshotDelete}
     * @param timers         {@link BondedTalentTimers#fromSnapshots}
     * @param clock          wall clock
     */
    public IndexBondedCompanionApi(@Nonnull CompanionIndex index, @Nonnull BondedRecords.Families families,
                                   @Nonnull Function<RestoreFlow.Request, CompletableFuture<RestoreFlow.Result>> restore,
                                   @Nonnull RosterSummons.Store store,
                                   @Nonnull Function<UUID, CompletableFuture<Void>> flush, @Nonnull Bodies bodies,
                                   @Nonnull Consumer<UUID> deleteSnapshot, @Nonnull Timers timers,
                                   @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.families = Objects.requireNonNull(families, "families");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.store = Objects.requireNonNull(store, "store");
        this.flush = Objects.requireNonNull(flush, "flush");
        this.bodies = Objects.requireNonNull(bodies, "bodies");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The production {@link Bodies}: {@code remove} takes a body out of its world on that world's thread. */
    @Nonnull
    public static <R> Bodies bodies(@Nonnull LoadedBodies<R> loaded, @Nonnull Consumer<R> remove) {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(remove, "remove");
        return profileId -> {
            R body = loaded.get(profileId);
            if (body == null) {
                return null;
            }
            loaded.removeIfSame(profileId, body);
            return () -> remove.accept(body);
        };
    }

    @Override
    @Nonnull
    public BondedCompanionAvailability availability() {
        return closed ? BondedCompanionAvailability.unavailable(CLOSED) : BondedCompanionAvailability.availableNow();
    }

    /** Every listable bonded companion of the owner in the roster, in a stable order. */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>> list(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(rosterId, "rosterId");
        return guarded(() -> {
            String roster = rosterId.trim();
            List<CompanionRecord> owned = index.fileRecords(ownerUuid);
            long now = clock.getAsLong();
            List<BondedCompanionProfileView> views = new ArrayList<>();
            for (CompanionRecord record : owned) {
                if (!record.bonded() || !roster.equals(record.rosterId())) {
                    continue;
                }
                try {
                    BondedCompanionProfileView view = BondedRecords.view(record, owned, families, now, Map.of());
                    if (view != null) {
                        views.add(view);
                    }
                } catch (RuntimeException unlistable) {
                    // One bad record must not hide the owner's other companions.
                    LOGGER.at(Level.WARNING).withCause(unlistable)
                            .log("Bonded companion %s could not be listed", record.profileId());
                }
            }
            views.sort(Comparator.comparing(BondedCompanionProfileView::profileId));
            return done(success(List.copyOf(views)));
        });
    }

    /**
     * The evidence of the capture that stored {@code sourceNpcUuid} in the owner's roster (plan 6
     * R17). It is kept on the companion's record, so it is found for as long as the companion
     * exists and not after it was abandoned.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionCaptureEvidenceView>> findCapture(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId, @Nonnull UUID sourceNpcUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(rosterId, "rosterId");
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        return guarded(() -> {
            String roster = rosterId.trim();
            for (CompanionRecord record : index.fileRecords(ownerUuid)) {
                if (!record.bonded() || !roster.equals(record.rosterId())) {
                    continue;
                }
                BondedCompanionCaptureEvidenceView evidence = BondedCaptureEvidence.read(record);
                if (evidence != null && sourceNpcUuid.equals(evidence.sourceNpcUuid())) {
                    return done(success(evidence));
                }
            }
            return done(failure(BondedCompanionResultCode.NOT_FOUND, CAPTURE_EVIDENCE_NOT_FOUND));
        });
    }

    /**
     * Adds a companion to the owner's roster as {@code STORED(PROVISIONED)}: no body and no
     * snapshot until its first summon builds one from {@code roleId} (plan 6 R16). The request's
     * caller namespace and idempotency key are the record's origin, so a repeated request returns
     * the companion the first one made, for as long as that companion exists; after it was
     * abandoned the same request makes a new one. The family's owned limit is checked under the index lock
     * in the step that inserts the record (plan 6 R15). Completes once the owner file is
     * written; when that write fails the record is withdrawn and the request can be repeated.
     *
     * <p>The request's species, gender and presentation data are not stored: the view of a
     * companion reports what its body and record hold.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> provision(
            @Nonnull BondedCompanionProvisionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> {
            if (!BondedRecords.publicNamespace(request.callerNamespace())) {
                return done(failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID));
            }
            BondedCompanionPolicy family = families.resolve(request.rosterId(), request.roleId());
            if (family == null || request.familyId() != null && !request.familyId().equals(family.familyId())) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ROLE_NOT_ALLOWED));
            }
            if (!family.features().provision()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, FEATURE_DISABLED));
            }
            CompanionRecord fresh = CompanionRecord.builder(UUID.randomUUID(), request.roleId(),
                            CompanionLocation.stored(StoredReason.PROVISIONED))
                    .ownerUuid(request.ownerUuid())
                    .displayName(request.displayName())
                    .bonded(true)
                    .rosterId(request.rosterId())
                    .origin(request.callerNamespace(), request.idempotencyKey())
                    .build();
            Object outcome = index.atomically(() -> {
                CompanionRecord existing = index.byOrigin(request.callerNamespace(), request.idempotencyKey());
                if (existing != null && existing.countsAsOwned()) {
                    return existing;
                }
                // The tombstone of an abandoned or released companion still holds the origin. It
                // gives it up here, so the same request provisions a new companion.
                if (existing != null && !index.update(existing.profileId(), existing.revision(),
                        b -> b.origin(null, null)).applied()) {
                    return failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
                }
                if (BondedAdmission.check(index.fileRecords(request.ownerUuid()), null, fresh, families)
                        == BondedAdmission.Refusal.OWNED_CAPACITY) {
                    return failure(BondedCompanionResultCode.POLICY_DENIED, OWNED_CAPACITY);
                }
                CompanionIndex.Mutation inserted = index.insert(fresh);
                return inserted.applied() ? new Provisioned(inserted.after())
                        : failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
            });
            if (outcome instanceof CompanionRecord existing) {
                return done(repeated(existing, request));
            }
            if (!(outcome instanceof Provisioned provisioned)) {
                @SuppressWarnings("unchecked")
                BondedCompanionResult<BondedCompanionProfileView> refused =
                        (BondedCompanionResult<BondedCompanionProfileView>) outcome;
                return done(refused);
            }
            CompanionRecord inserted = provisioned.record();
            return flush.apply(request.ownerUuid()).handle((ignored, failure) -> {
                if (failure == null) {
                    return viewResult(inserted.profileId());
                }
                LOGGER.at(Level.WARNING).withCause(failure).log(
                        "Provisioned bonded companion %s was not written; it is withdrawn", inserted.profileId());
                withdraw(inserted);
                return failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
            });
        });
    }

    /** The answer to a request whose origin already has a record. */
    private BondedCompanionResult<BondedCompanionProfileView> repeated(CompanionRecord existing,
                                                                       BondedCompanionProvisionRequest request) {
        if (!request.ownerUuid().equals(existing.ownerUuid()) || !request.rosterId().equals(existing.rosterId())) {
            // The same key was used for another owner or roster: not this request's companion.
            return failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID);
        }
        return viewResult(existing.profileId());
    }

    /**
     * Takes back a provisioned record whose owner file was not written. The tombstone gives up the
     * origin, so the same request can be made again. Changes nothing when the record has changed.
     */
    private void withdraw(CompanionRecord inserted) {
        index.atomically(() -> {
            CompanionRecord current = index.get(inserted.profileId());
            if (current != null && current.revision() == inserted.revision()) {
                UnaryOperator<CompanionRecord.Builder> released = CompanionTransitions.released(current);
                index.update(current.profileId(), current.revision(), b -> released.apply(b).origin(null, null));
            }
            return null;
        });
    }

    /**
     * Summons a stored companion at the action context's placement. A companion whose body was
     * lost is listed as stored and comes back through a recover. The session timer comes from the
     * family's {@code SessionDurationSeconds} with the companion's talent modifiers.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> summon(
            @Nonnull BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> {
            BondedCompanionPlacement placement = placement(request);
            if (placement == null) {
                return done(failure(BondedCompanionResultCode.WORLD_UNAVAILABLE, PLACEMENT_REQUIRED));
            }
            CompanionRecord record = record(request.profileId());
            BondedCompanionResult<BondedCompanionProfileView> refusal = identity(record, request);
            if (refusal != null) {
                return done(refusal);
            }
            LocationKind kind = record.location().kind();
            if (kind == LocationKind.LIVE) {
                return done(failure(BondedCompanionResultCode.INVALID_STATE, ALREADY_LIVE));
            }
            if (kind != LocationKind.STORED && kind != LocationKind.LOST) {
                return done(failure(BondedCompanionResultCode.INVALID_STATE, INVALID_STATE));
            }
            if (record.generation() != request.expectedRevision()) {
                return done(failure(BondedCompanionResultCode.REVISION_CONFLICT, REVISION_CONFLICT));
            }
            BondedCompanionPolicy family = BondedRecords.policy(record, families);
            if (family == null) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ROLE_NOT_ALLOWED));
            }
            if (!family.features().summon()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, FEATURE_DISABLED));
            }
            if (record.location().reason() == StoredReason.PROVISIONED) {
                return firstSummon(record, family, request, placement);
            }
            if (clock.getAsLong() < record.summonCooldownUntilMs()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, COOLDOWN_ACTIVE));
            }
            if (!activePlaceFree(record, family)) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ACTIVE_CAPACITY));
            }
            // A lost body has no stored location to summon from; RestoreRules restores it as a recover.
            RestoreRules.Reason reason = kind == LocationKind.LOST
                    ? RestoreRules.Reason.RECOVER : RestoreRules.Reason.SUMMON;
            return restoreTimed(record, family, reason, placement);
        });
    }

    /**
     * A provisioned companion's first summon (plan 6 R16). The record has no snapshot, so the
     * restore hands the spawner none and the spawner builds the body from the record's role,
     * stamps it and snapshots it. When no body is added the restore puts the record back to
     * {@code STORED(PROVISIONED)} at its old generation, and it can be summoned again.
     */
    private CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> firstSummon(
            CompanionRecord record, BondedCompanionPolicy family, BondedCompanionActionRequest request,
            BondedCompanionPlacement placement) {
        if (clock.getAsLong() < record.summonCooldownUntilMs()) {
            return done(failure(BondedCompanionResultCode.POLICY_DENIED, COOLDOWN_ACTIVE));
        }
        if (!activePlaceFree(record, family)) {
            return done(failure(BondedCompanionResultCode.POLICY_DENIED, ACTIVE_CAPACITY));
        }
        return restoreTimed(record, family, RestoreRules.Reason.SUMMON, placement);
    }

    /** Stores an active companion as {@code STORED(BONDED)} with the family's summon cooldown. */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> store(
            @Nonnull BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> {
            CompanionRecord record = record(request.profileId());
            BondedCompanionResult<BondedCompanionProfileView> refusal = identity(record, request);
            if (refusal != null) {
                return done(refusal);
            }
            if (record.location().kind() != LocationKind.LIVE) {
                return done(failure(BondedCompanionResultCode.INVALID_STATE, INVALID_STATE));
            }
            if (record.generation() != request.expectedRevision()) {
                return done(failure(BondedCompanionResultCode.REVISION_CONFLICT, REVISION_CONFLICT));
            }
            BondedCompanionPolicy family = BondedRecords.policy(record, families);
            if (family == null) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ROLE_NOT_ALLOWED));
            }
            if (!family.features().dismiss()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, FEATURE_DISABLED));
            }
            UUID profileId = record.profileId();
            return storeActive(record).thenApply(result -> switch (result) {
                case STORED -> viewResult(profileId);
                case NOT_FOUND -> failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND);
                case NOT_LIVE -> failure(BondedCompanionResultCode.INVALID_STATE, INVALID_STATE);
                case NO_SNAPSHOT -> failure(BondedCompanionResultCode.INTERNAL_FAILURE, SNAPSHOT_INVALID);
                case CONFLICT -> failure(BondedCompanionResultCode.REVISION_CONFLICT, STORE_NOT_COMMITTED);
                case COMMIT_FAILED -> failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
            });
        });
    }

    /**
     * Stores an active bonded companion with no request checks: the summon timer ran out, or its
     * owner logged out or changed world (plan 6 R18). The cooldown is the family's
     * {@code SummonCooldownSeconds} with the companion's talent modifiers; a companion whose role
     * resolves to no family is stored with no cooldown. {@link RosterSummons} routes bonded
     * records here.
     */
    @Nonnull
    public CompletableFuture<StoreFlow.Result> storeActive(@Nonnull CompanionRecord record) {
        Objects.requireNonNull(record, "record");
        BondedCompanionPolicy family = BondedRecords.policy(record, families);
        CompletableFuture<BondedCompanionPolicy> adjusted = family == null
                ? CompletableFuture.completedFuture(null) : adjustedTimers(record, family);
        return adjusted.thenCompose(policy -> {
            long cooldownMs = policy == null ? 0L : BondedRecords.millis(policy.summonCooldownSeconds());
            long cooldownUntilMs = cooldownMs > 0L ? saturatedAdd(clock.getAsLong(), cooldownMs) : 0L;
            return store.store(record.profileId(), StoredReason.BONDED, cooldownUntilMs);
        });
    }

    /**
     * Abandons a companion for good: the record becomes a RELEASED tombstone under the index
     * lock, a loaded body is removed and the snapshot delete is queued. Like an owner release, it
     * does not wait for the owner file; the writer saves the tombstone on its next flush and
     * holds the snapshot delete until then.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<Void>> abandon(@Nonnull BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> {
            Runnable[] removeBody = new Runnable[1];
            BondedCompanionResult<Void> result = index.atomically(() -> {
                CompanionRecord record = record(request.profileId());
                BondedCompanionResult<Void> refusal = identity(record, request);
                if (refusal != null) {
                    return refusal;
                }
                if (record.generation() != request.expectedRevision()) {
                    return failure(BondedCompanionResultCode.REVISION_CONFLICT, PROFILE_REVISION_CONFLICT);
                }
                if (!index.update(record.profileId(), record.revision(),
                        CompanionTransitions.released(record, CAUSE_ABANDONED)).applied()) {
                    return failure(BondedCompanionResultCode.REVISION_CONFLICT, PROFILE_REVISION_CONFLICT);
                }
                removeBody[0] = bodies.unregister(record.profileId());
                return success(null);
            });
            if (result.successful()) {
                deleteSnapshot.accept(UUID.fromString(request.profileId()));
                if (removeBody[0] != null) {
                    removeBody[0].run();
                }
            }
            return done(result);
        });
    }

    /**
     * The revive price and cooldown of a companion. A companion that is not dead, or whose family
     * has revival off, gets a disabled quote with no costs. Owned quantities come from the action
     * context's inventory and are 0 without one.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionReviveQuote>> quoteRevive(
            @Nonnull BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> {
            CompanionRecord record = record(request.profileId());
            BondedCompanionResult<BondedCompanionReviveQuote> refusal = identity(record, request);
            if (refusal != null) {
                return done(refusal);
            }
            BondedCompanionPolicy family = BondedRecords.policy(record, families);
            if (family == null) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, POLICY_DENIED));
            }
            BondedCompanionReviveQuote quote = BondedRecords.reviveQuote(record, family, clock.getAsLong());
            if (quote == null) {
                return done(success(new BondedCompanionReviveQuote(
                        record.profileId().toString(), false, List.of(), 0L, family.revision())));
            }
            BondedCompanionActionContext context = request.actionContext();
            BondedCompanionActionContext.Inventory inventory = context == null ? null : context.inventory();
            if (inventory == null) {
                return done(success(quote));
            }
            List<BondedCompanionReviveQuote.CostLine> lines = new ArrayList<>();
            for (BondedCompanionReviveQuote.CostLine line : quote.costs()) {
                lines.add(new BondedCompanionReviveQuote.CostLine(line.itemId(), line.requiredQuantity(),
                        Math.max(0, inventory.availableQuantity(line.itemId()))));
            }
            return done(success(new BondedCompanionReviveQuote(quote.profileId(), quote.enabled(), lines,
                    quote.cooldownRemainingSeconds(), quote.policyRevision())));
        });
    }

    /**
     * Revives a dead companion at the action context's placement (plan 6 R19): the price is
     * charged through the context's inventory, the restore runs, and the charge is refunded when
     * the restore does not bring the companion back. A family with no price revives for free.
     * The revived companion is active, so it takes an active place and gets a session timer.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> revive(
            @Nonnull BondedCompanionReviveRequest request) {
        Objects.requireNonNull(request, "request");
        BondedCompanionActionRequest action = request.action();
        return guarded(() -> {
            BondedCompanionPlacement placement = placement(action);
            if (placement == null) {
                return done(failure(BondedCompanionResultCode.WORLD_UNAVAILABLE, PLACEMENT_REQUIRED));
            }
            CompanionRecord record = record(action.profileId());
            BondedCompanionResult<BondedCompanionProfileView> refusal = identity(record, action);
            if (refusal != null) {
                return done(refusal);
            }
            if (record.location().kind() != LocationKind.DEAD) {
                return done(failure(BondedCompanionResultCode.INVALID_STATE, INVALID_STATE));
            }
            if (record.generation() != action.expectedRevision()) {
                return done(failure(BondedCompanionResultCode.REVISION_CONFLICT, REVISION_CONFLICT));
            }
            BondedCompanionPolicy family = BondedRecords.policy(record, families);
            if (family == null) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ROLE_NOT_ALLOWED));
            }
            if (!family.features().revive()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, FEATURE_DISABLED));
            }
            if (family.revision() != request.quoteRevision()) {
                return done(failure(BondedCompanionResultCode.REVISION_CONFLICT, QUOTE_STALE));
            }
            if (clock.getAsLong() < record.reviveAvailableAtMs()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, COOLDOWN_ACTIVE));
            }
            if (!activePlaceFree(record, family)) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ACTIVE_CAPACITY));
            }
            BondedCompanionPolicy.RevivePrice price = family.revivePriceFor(record.roleId());
            List<BondedCompanionReviveCost> costs = price == null ? List.of() : price.costs();
            if (costs.isEmpty()) {
                return restoreTimed(record, family, RestoreRules.Reason.REVIVE, placement);
            }
            BondedCompanionActionContext context = action.actionContext();
            BondedCompanionActionContext.Inventory inventory = context == null ? null : context.inventory();
            if (inventory == null) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, PAYMENT_UNAVAILABLE));
            }
            String operationId = action.callerNamespace() + ":" + action.idempotencyKey();
            return charge(inventory, operationId, costs).thenCompose(receipt -> {
                if (receipt == null) {
                    return done(failure(BondedCompanionResultCode.POLICY_DENIED, PAYMENT_INSUFFICIENT));
                }
                return guarded(() -> restoreTimed(record, family, RestoreRules.Reason.REVIVE, placement))
                        .thenCompose(result -> settle(receipt, result, record.profileId()));
            });
        });
    }

    /** The charge, or null when nothing was taken. Never completes exceptionally. */
    private static CompletableFuture<BondedCompanionActionContext.ChargeReceipt> charge(
            BondedCompanionActionContext.Inventory inventory, String operationId, List<BondedCompanionReviveCost> costs) {
        try {
            CompletionStage<BondedCompanionActionContext.ChargeReceipt> charged =
                    inventory.consumeExactAsync(operationId, costs);
            return charged == null ? CompletableFuture.completedFuture(null)
                    : charged.toCompletableFuture().exceptionally(failure -> null);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Keeps the charge of a revive that worked and returns the charge of one that did not. */
    private <T> CompletableFuture<BondedCompanionResult<T>> settle(
            BondedCompanionActionContext.ChargeReceipt receipt, BondedCompanionResult<T> result, UUID profileId) {
        CompletionStage<Boolean> settled;
        try {
            settled = result.successful() ? receipt.completeAsync() : receipt.refundAsync();
        } catch (RuntimeException | LinkageError failure) {
            settled = CompletableFuture.failedFuture(failure);
        }
        if (settled == null) {
            settled = CompletableFuture.completedFuture(false);
        }
        return settled.toCompletableFuture().handle((ok, failure) -> {
            if (!result.successful() && (failure != null || !Boolean.TRUE.equals(ok))) {
                LOGGER.at(Level.WARNING).withCause(failure).log(
                        "The revive price of bonded companion %s was not refunded after a failed revive (%s)",
                        profileId, result.reason());
            }
            return result;
        });
    }

    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>> getExtensionData(
            @Nonnull BondedCompanionExtensionDataKey key) {
        Objects.requireNonNull(key, "key");
        return guarded(() -> {
            if (!BondedRecords.publicNamespace(key.namespace())) {
                return done(failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID));
            }
            CompanionRecord record = record(key.profileId());
            if (record == null || !key.ownerUuid().equals(record.ownerUuid())) {
                return done(failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND));
            }
            ExtensionEntry entry = record.extensions().get(extensionKey(key));
            return done(entry == null ? failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND)
                    : success(data(key, entry, record)));
        });
    }

    /**
     * Sets a caller namespace's one value on a companion when its revision is the expected one.
     * {@link BondedCompanionExtensionDataUpdate#MISSING_REVISION} expects no value; the first
     * value has revision 0 and each write adds one. Completes only after the owner file is
     * written and undoes the change when that write fails. Repeating a request whose value is
     * already the current one succeeds, after the same wait for the owner file.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>> compareAndSetExtensionData(
            @Nonnull BondedCompanionExtensionDataUpdate update) {
        Objects.requireNonNull(update, "update");
        return guarded(() -> {
            BondedCompanionExtensionDataKey key = update.key();
            if (!BondedRecords.publicNamespace(key.namespace())
                    || update.expectedRevision() > Long.MAX_VALUE - 2L) {
                return done(failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID));
            }
            String extensionKey = extensionKey(key);
            // A stored entry's revision is the public revision plus one: 0 is "no value" on disk.
            ExtensionEntry wanted = new ExtensionEntry(update.expectedRevision() + 2L, update.jsonPayload());
            Object outcome = index.atomically(() -> {
                CompanionRecord record = record(key.profileId());
                if (record == null || !key.ownerUuid().equals(record.ownerUuid())) {
                    return failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND);
                }
                ExtensionEntry current = record.extensions().get(extensionKey);
                if (wanted.equals(current)) {
                    // The same request again. Its owner file may still be unwritten, so this
                    // caller waits for a flush too.
                    return new Written(record, null, true);
                }
                if (publicRevision(current) != update.expectedRevision()) {
                    return failure(BondedCompanionResultCode.REVISION_CONFLICT, EXTENSION_REVISION_CONFLICT);
                }
                CompanionIndex.Mutation applied =
                        index.update(record.profileId(), record.revision(), b -> b.extension(extensionKey, wanted));
                return applied.applied() ? new Written(applied.after(), current, false)
                        : failure(BondedCompanionResultCode.REVISION_CONFLICT, EXTENSION_REVISION_CONFLICT);
            });
            if (!(outcome instanceof Written written)) {
                @SuppressWarnings("unchecked")
                BondedCompanionResult<BondedCompanionExtensionData> refused =
                        (BondedCompanionResult<BondedCompanionExtensionData>) outcome;
                return done(refused);
            }
            CompanionRecord after = written.after();
            return flush.apply(after.ownerUuid()).handle((ignored, failure) -> {
                if (failure == null) {
                    return success(data(key, wanted, after));
                }
                if (!written.replay()) {
                    undo(after.profileId(), extensionKey, wanted, written.previous());
                }
                return failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
            });
        });
    }

    /** Puts {@code previous} back unless the value has changed again since {@code written}. */
    private void undo(UUID profileId, String extensionKey, ExtensionEntry written, @Nullable ExtensionEntry previous) {
        index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record != null && written.equals(record.extensions().get(extensionKey))) {
                index.update(profileId, record.revision(), b -> b.extension(extensionKey, previous));
            }
            return null;
        });
    }

    /**
     * Listeners hear about every bonded companion change after the index lock is released, on the
     * thread that made the change (often a world thread), so they must not block. Closing the
     * returned handle, or this API, stops delivery.
     */
    @Override
    @Nonnull
    public AutoCloseable subscribe(@Nonnull Consumer<BondedCompanionChangedEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            return () -> { };
        }
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    /**
     * The index's after-unlock change listener; {@code Tamework} registers it once with
     * {@code CompanionPersistenceModule.addAfterUnlockListener}. Position refreshes of a live
     * body are not reported. Reasons: {@code provisioned}, {@code summoned}, {@code revived},
     * {@code stored}, {@code lost}, {@code died}, {@code abandoned}, {@code old_age},
     * {@code released} and {@code updated}. A companion that enters a roster is {@code provisioned}
     * when it was provisioned and {@code stored} when it was captured into storage.
     */
    public void onChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (subscribers.isEmpty()) {
            return;
        }
        BondedCompanionStateView oldState = before == null ? null : BondedRecords.state(before);
        BondedCompanionStateView newState = BondedRecords.state(after);
        UUID owner = after.ownerUuid() != null ? after.ownerUuid() : before == null ? null : before.ownerUuid();
        String rosterId = after.rosterId() != null ? after.rosterId() : before == null ? null : before.rosterId();
        if (owner == null || rosterId == null || rosterId.isBlank()) {
            return;
        }
        String reason;
        if (newState == null) {
            if (oldState == null || after.location().kind() != LocationKind.RELEASED) {
                return;
            }
            String cause = after.location().cause();
            reason = CAUSE_ABANDONED.equals(cause) ? "abandoned"
                    : cause == null && before.location().kind() == LocationKind.LIVE ? "old_age" : "released";
            newState = oldState;
        } else if (oldState == null) {
            reason = after.location().reason() == StoredReason.PROVISIONED ? "provisioned" : "stored";
        } else if (before.generation() == after.generation() && before.location().kind() == after.location().kind()) {
            if (before.summonedUntilMs() == after.summonedUntilMs()
                    && before.summonCooldownUntilMs() == after.summonCooldownUntilMs()
                    && before.reviveAvailableAtMs() == after.reviveAvailableAtMs()
                    && Objects.equals(before.displayName(), after.displayName())
                    && before.roleId().equals(after.roleId())) {
                return;
            }
            reason = "updated";
        } else {
            reason = switch (after.location().kind()) {
                case LIVE -> before.location().kind() == LocationKind.DEAD ? "revived" : "summoned";
                case STORED -> "stored";
                case LOST -> "lost";
                case DEAD -> "died";
                default -> "updated";
            };
        }
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(after.profileId().toString(), owner,
                rosterId, oldState, newState, after.generation(), reason);
        for (Consumer<BondedCompanionChangedEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.at(Level.WARNING).withCause(failure)
                        .log("A bonded companion subscriber failed for profile %s", after.profileId());
            }
        }
    }

    /** Drops every subscriber; later calls report the API as unavailable. */
    @Override
    public void close() {
        closed = true;
        subscribers.clear();
    }

    /**
     * Restores the companion at the placement as an active companion with the family's session
     * timer. Only a SUMMON restore carries the timer in its commit; after a recover or a revive
     * the timer is written once the companion is back.
     */
    private CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> restoreTimed(
            CompanionRecord record, BondedCompanionPolicy family, RestoreRules.Reason reason,
            BondedCompanionPlacement placement) {
        UUID profileId = record.profileId();
        RestoreFlow.Destination destination = new RestoreFlow.Destination(placement.worldKey(), placement.x(),
                placement.y(), placement.z(), placement.yawRadians(), placement.pitchRadians());
        return adjustedTimers(record, family).thenCompose(adjusted -> {
            long sessionMs = BondedRecords.millis(adjusted.sessionDurationSeconds());
            long untilMs = sessionMs > 0L ? saturatedAdd(clock.getAsLong(), sessionMs) : 0L;
            RestoreFlow.Request request = RestoreFlow.Request.of(profileId, reason, destination)
                    .withGeneration(record.generation()).withSummonedUntil(untilMs);
            return restore.apply(request).thenApply(result -> {
                if (result != RestoreFlow.Result.RESTORED) {
                    return restoreFailure(result);
                }
                if (reason != RestoreRules.Reason.SUMMON && untilMs != 0L) {
                    startSession(profileId, record.generation() + 1L, untilMs);
                }
                return viewResult(profileId);
            });
        });
    }

    /** Sets the session timer of the body just restored, unless the record has moved on. */
    private void startSession(UUID profileId, long generation, long untilMs) {
        index.atomically(() -> {
            CompanionRecord current = index.get(profileId);
            if (current != null && current.location().kind() == LocationKind.LIVE
                    && current.generation() == generation) {
                index.update(profileId, current.revision(), b -> b.summonedUntilMs(untilMs));
            }
            return null;
        });
    }

    private static <T> BondedCompanionResult<T> restoreFailure(RestoreFlow.Result result) {
        return switch (result) {
            case NOT_FOUND -> failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND);
            case NOT_ALLOWED -> failure(BondedCompanionResultCode.INVALID_STATE, INVALID_STATE);
            case COOLDOWN -> failure(BondedCompanionResultCode.POLICY_DENIED, COOLDOWN_ACTIVE);
            case NO_SNAPSHOT -> failure(BondedCompanionResultCode.INTERNAL_FAILURE, SNAPSHOT_INVALID);
            case CONFLICT, STALE -> failure(BondedCompanionResultCode.REVISION_CONFLICT, REVISION_CONFLICT);
            case SPAWN_FAILED -> failure(BondedCompanionResultCode.WORLD_UNAVAILABLE, PLACEMENT_UNAVAILABLE);
            case OWNED_LIMIT -> failure(BondedCompanionResultCode.POLICY_DENIED, OWNED_CAPACITY);
            case GROUP_LIMIT -> failure(BondedCompanionResultCode.POLICY_DENIED, ACTIVE_CAPACITY);
            case COMMIT_FAILED, RESTORED -> failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
            // An admission provider's refusal.
            default -> failure(BondedCompanionResultCode.POLICY_DENIED, POLICY_DENIED);
        };
    }

    private CompletableFuture<BondedCompanionPolicy> adjustedTimers(CompanionRecord record,
                                                                    BondedCompanionPolicy family) {
        CompletableFuture<BondedCompanionPolicy> adjusted;
        try {
            adjusted = timers.adjusted(record, family);
        } catch (RuntimeException | LinkageError failure) {
            adjusted = null;
        }
        return adjusted == null ? CompletableFuture.completedFuture(family)
                : adjusted.handle((policy, failure) -> failure != null || policy == null ? family : policy);
    }

    /** Whether making {@code record} active keeps its family within its active limit. */
    private boolean activePlaceFree(CompanionRecord record, BondedCompanionPolicy family) {
        return family.maximumActive() == 0 || BondedRecords.activeCount(
                index.fileRecords(record.ownerUuid()), family, families) < family.maximumActive();
    }

    /** The placement of an action, or null unless it is in the world the request names. */
    @Nullable
    private static BondedCompanionPlacement placement(BondedCompanionActionRequest request) {
        BondedCompanionActionContext context = request.actionContext();
        BondedCompanionPlacement placement = context == null ? null : context.summonPlacement();
        return placement != null && placement.worldKey().equals(request.worldKey()) ? placement : null;
    }

    /** The listable bonded record of a profile id, or null; an id that is not a UUID is not found. */
    @Nullable
    private CompanionRecord record(String profileId) {
        CompanionRecord record;
        try {
            record = index.get(UUID.fromString(profileId.trim()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return record != null && BondedRecords.state(record) != null ? record : null;
    }

    /** A failure when the record is missing, belongs to someone else or is in another roster; else null. */
    @Nullable
    private static <T> BondedCompanionResult<T> identity(@Nullable CompanionRecord record,
                                                         BondedCompanionActionRequest request) {
        if (record == null || !request.rosterId().equals(record.rosterId())) {
            return failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND);
        }
        if (!request.ownerUuid().equals(record.ownerUuid())) {
            return failure(BondedCompanionResultCode.NOT_OWNER, NOT_OWNER);
        }
        return null;
    }

    private BondedCompanionResult<BondedCompanionProfileView> viewResult(UUID profileId) {
        CompanionRecord record = index.get(profileId);
        BondedCompanionProfileView view = record == null || record.ownerUuid() == null ? null
                : BondedRecords.view(record, index.fileRecords(record.ownerUuid()), families, clock.getAsLong(), Map.of());
        return view == null ? failure(BondedCompanionResultCode.REVISION_CONFLICT, REVISION_CONFLICT) : success(view);
    }

    private static String extensionKey(BondedCompanionExtensionDataKey key) {
        return BondedRecords.extensionKey(key.namespace(), BondedRecords.EXTENSION_DATA_KEY);
    }

    /** The public revision of a stored entry; {@code MISSING_REVISION} when there is none. */
    private static long publicRevision(@Nullable ExtensionEntry entry) {
        return entry == null ? BondedCompanionExtensionDataUpdate.MISSING_REVISION
                : Math.max(1L, entry.revision()) - 1L;
    }

    private static BondedCompanionExtensionData data(BondedCompanionExtensionDataKey key, ExtensionEntry entry,
                                                     CompanionRecord record) {
        return new BondedCompanionExtensionData(key, entry.json(), publicRevision(entry), record.updatedAtMs());
    }

    /** Runs {@code action}; a closed API reports unavailable, and a thrown or failed action an internal failure. */
    private <T> CompletableFuture<BondedCompanionResult<T>> guarded(
            Supplier<CompletableFuture<BondedCompanionResult<T>>> action) {
        if (closed) {
            return done(BondedCompanionResult.unavailable(CLOSED));
        }
        CompletableFuture<BondedCompanionResult<T>> result;
        try {
            result = action.get();
        } catch (RuntimeException | LinkageError failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        return result.exceptionally(failure -> {
            LOGGER.at(Level.WARNING).withCause(failure).log("A bonded companion operation failed");
            return failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
        });
    }

    private static <T> CompletableFuture<BondedCompanionResult<T>> done(BondedCompanionResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    private static <T> BondedCompanionResult<T> success(@Nullable T value) {
        return new BondedCompanionResult<>(BondedCompanionResultCode.SUCCESS, value, null);
    }

    private static <T> BondedCompanionResult<T> failure(BondedCompanionResultCode code, String reason) {
        return new BondedCompanionResult<>(code, null, reason);
    }

    /** {@code deltaMs} is positive; a sum past the end of time stays at the end. */
    private static long saturatedAdd(long nowMs, long deltaMs) {
        return nowMs > Long.MAX_VALUE - deltaMs ? Long.MAX_VALUE : nowMs + deltaMs;
    }

    /** A record this call inserted, as opposed to one an earlier request made. */
    private record Provisioned(CompanionRecord record) {
    }

    /** @param replay the value was already current, so a failed write leaves it to the first caller */
    private record Written(CompanionRecord after, @Nullable ExtensionEntry previous, boolean replay) {
    }
}
