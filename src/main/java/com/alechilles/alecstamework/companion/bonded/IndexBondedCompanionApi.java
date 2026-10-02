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
import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.companion.flow.CompanionBodyLifecycle;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.flow.RosterSummons;
import com.alechilles.alecstamework.companion.flow.StoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.ExtensionEntries;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
public final class IndexBondedCompanionApi
        implements BondedCompanionApi, BondedTalentUpdates.StoredReader, AutoCloseable {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String CLOSED = "bonded-companion-authority-closed";
    static final String NOT_FOUND = "bonded-profile-not-found";
    static final String NOT_OWNER = "bonded-transition-not_owner";
    static final String INVALID_STATE = "bonded-transition-invalid_state";
    static final String REVISION_CONFLICT = "bonded-transition-revision_conflict";
    public static final String ROLE_NOT_ALLOWED = "bonded-transition-role_not_allowed";
    static final String FEATURE_DISABLED = "bonded-transition-feature_disabled";
    static final String COOLDOWN_ACTIVE = "bonded-transition-cooldown_active";
    static final String ACTIVE_CAPACITY = "bonded-transition-active_capacity_reached";
    public static final String OWNED_CAPACITY = "bonded-transition-owned_capacity_reached";
    /** Only {@link #grantByAdmin} reports this; {@link #provision} reports a full family as {@link #OWNED_CAPACITY}. */
    public static final String FAMILY_CAPACITY = "bonded-transition-family_capacity_reached";
    /** Origin key prefix of a companion an operator granted; the origin namespace is Tamework's own. */
    static final String ADMIN_GRANT_KEY_PREFIX = "admin-grant:";
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
    static final String TALENTS_UNAVAILABLE = "bonded-talent-updates-unavailable";
    static final String TALENTS_DISABLED = "bonded-talents-disabled";
    static final String TALENT_LEVEL_DATA_UNAVAILABLE = "bonded-level-data-unavailable";
    static final String TALENT_PURCHASE_REJECTED = "bonded-talent-purchase-rejected";
    static final String TALENT_RESET_REJECTED = "bonded-talent-reset-rejected";
    static final String TALENT_BODY_UNAVAILABLE = "bonded-talent-body-unavailable";
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

    /** The loaded bodies of bonded companions; {@link #bodies(LoadedBodies, Consumer)} in production. */
    @FunctionalInterface
    public interface Bodies {
        /**
         * Unregisters a profile's loaded body. Called under the index lock, so the body's removal
         * is not seen as a loss. Returns the action that removes the body from its world (it must
         * hop to the body's world thread itself), or null when no body is loaded.
         */
        @Nullable
        Runnable unregister(@Nonnull UUID profileId);

        /**
         * Whether a body is registered for the profile. A registry read; safe from any thread.
         * The default suits a source that tracks no bodies at all.
         */
        default boolean loaded(@Nonnull UUID profileId) {
            return false;
        }
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
    @Nullable private volatile BondedTalentUpdates talents;
    @Nullable private volatile CompanionAdmissionGate.Check builtInCaps;

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

    /**
     * Makes {@link #provision} respect the population caps every other way of getting a companion
     * respects, on top of the family's own owned limit: {@code caps} is the gate's check
     * ({@code CompanionAdmissionGate::deny}), called under the index lock in the step that
     * inserts the record. Without this call a provisioned companion counts only against its
     * family's limit.
     */
    public void useBuiltInCaps(@Nonnull CompanionAdmissionGate.Check caps) {
        this.builtInCaps = Objects.requireNonNull(caps, "caps");
    }

    /** The production {@link Bodies}: {@code remove} takes a body out of its world on that world's thread. */
    @Nonnull
    public static <R> Bodies bodies(@Nonnull LoadedBodies<R> loaded, @Nonnull Consumer<R> remove) {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(remove, "remove");
        return new Bodies() {
            @Override
            public Runnable unregister(@Nonnull UUID profileId) {
                R body = loaded.get(profileId);
                if (body == null) {
                    return null;
                }
                loaded.removeIfSame(profileId, body);
                return () -> remove.accept(body);
            }

            @Override
            public boolean loaded(@Nonnull UUID profileId) {
                return loaded.get(profileId) != null;
            }
        };
    }

    @Override
    @Nonnull
    public BondedCompanionAvailability availability() {
        return closed ? BondedCompanionAvailability.unavailable(CLOSED) : BondedCompanionAvailability.availableNow();
    }

    /**
     * Every listable bonded companion of the owner in the roster, in a stable order. Each view's
     * presentation data comes from the record's summary ({@link #presentation}), so a stored or
     * dead companion is listed with the level and vitals it had when it was last snapshotted.
     */
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
                    BondedCompanionProfileView view =
                            BondedRecords.view(record, owned, families, now, presentation(record));
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
     * abandoned the same request makes a new one. The family's owned limit, and the built-in
     * caps when {@link #useBuiltInCaps} gave them, are checked under the index lock in the step
     * that inserts the record (plan 6 R15). Completes once the owner file is written, also for a
     * repeated request; when the first write fails the record is withdrawn and the request can
     * be repeated.
     *
     * <p>A request with no display name names the companion after its species. The request's
     * gender and presentation data are not stored: the view of a companion reports what its
     * body and record hold.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> provision(
            @Nonnull BondedCompanionProvisionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> BondedRecords.publicNamespace(request.callerNamespace())
                ? provision(request, false)
                : done(failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID)));
    }

    /**
     * {@link #provision} for an operator command: one new companion per call, with an origin in
     * Tamework's own namespace that marks it as an admin grant. The role must belong to one family
     * of the roster and both owned limits apply, as for any provision; a full family is reported
     * as {@link #FAMILY_CAPACITY} and a full built-in cap as {@link #OWNED_CAPACITY}. Unlike a
     * public provision it does not need the family's {@code Provision} feature, which only says
     * whether integrations may provision. With no display name the companion has none.
     */
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> grantByAdmin(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId, @Nonnull String roleId,
            @Nullable String displayName) {
        BondedCompanionProvisionRequest request = new BondedCompanionProvisionRequest(
                ExtensionEntries.TAMEWORK_NAMESPACE, ADMIN_GRANT_KEY_PREFIX + UUID.randomUUID(),
                ownerUuid, rosterId, roleId, displayName, null, null, Map.of());
        return guarded(() -> provision(request, true));
    }

    private CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> provision(
            BondedCompanionProvisionRequest request, boolean adminGrant) {
        BondedCompanionPolicy family = families.resolve(request.rosterId(), request.roleId());
        if (family == null || request.familyId() != null && !request.familyId().equals(family.familyId())) {
            return done(failure(BondedCompanionResultCode.POLICY_DENIED, ROLE_NOT_ALLOWED));
        }
        if (!adminGrant && !family.features().provision()) {
            return done(failure(BondedCompanionResultCode.POLICY_DENIED, FEATURE_DISABLED));
        }
        CompanionRecord fresh = CompanionRecord.builder(UUID.randomUUID(), request.roleId(),
                        CompanionLocation.stored(StoredReason.PROVISIONED))
                .ownerUuid(request.ownerUuid())
                .displayName(request.displayName() != null ? request.displayName() : request.species())
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
                return failure(BondedCompanionResultCode.POLICY_DENIED,
                        adminGrant ? FAMILY_CAPACITY : OWNED_CAPACITY);
            }
            CompanionAdmissionGate.Check caps = builtInCaps;
            CompanionAdmissionGate.Denial denied = caps == null ? null
                    : caps.deny(null, fresh, CompanionAdmission.Provided.none());
            if (denied != null) {
                return failure(BondedCompanionResultCode.POLICY_DENIED,
                        denied.refusal() == CompanionAdmission.Refusal.OWNED
                                || denied.refusal() == CompanionAdmission.Refusal.GROUP_OWNED
                                ? OWNED_CAPACITY : POLICY_DENIED);
            }
            CompanionIndex.Mutation inserted = index.insert(fresh);
            return inserted.applied() ? new Provisioned(inserted.after())
                    : failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
        });
        if (outcome instanceof CompanionRecord existing) {
            if (!request.ownerUuid().equals(existing.ownerUuid())
                    || !request.rosterId().equals(existing.rosterId())) {
                // The same key was used for another owner or roster: not this request's companion.
                return done(failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID));
            }
            // The first request's owner file may still be unwritten, so this caller waits for a flush too.
            return flush.apply(request.ownerUuid()).handle((ignored, failure) -> failure == null
                    ? viewResult(existing.profileId())
                    : failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED));
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
     * lost is listed as stored and comes back through a recover, and so does an active one whose
     * body is not loaded; an active one with a loaded body is refused as already live. The session timer comes from the
     * family's {@code SessionDurationSeconds} with the companion's talent modifiers.
     *
     * <p>A provisioned companion has no snapshot until it has been out once (plan 6 R16): the
     * restore then hands the spawner none, and the spawner builds the body from the record's
     * role, stamps it and snapshots it. When no body is added the restore puts the record back
     * as it was, and it can be summoned again.
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
            // An active record with no loaded body (its chunk is unloaded, or a stop left it
            // without one) is brought back as a recover. The new generation fences the old body
            // if it ever loads again.
            boolean bodiless = kind == LocationKind.LIVE && !bodies.loaded(record.profileId());
            if (kind == LocationKind.LIVE && !bodiless) {
                return done(failure(BondedCompanionResultCode.INVALID_STATE, ALREADY_LIVE));
            }
            if (kind != LocationKind.STORED && kind != LocationKind.LOST && !bodiless) {
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
            if (clock.getAsLong() < record.summonCooldownUntilMs()) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, COOLDOWN_ACTIVE));
            }
            // An active record already holds its active place.
            if (!bodiless && !activePlaceFree(record, family)) {
                return done(failure(BondedCompanionResultCode.POLICY_DENIED, ACTIVE_CAPACITY));
            }
            // A lost body has no stored location to summon from; RestoreRules restores it as a recover.
            RestoreRules.Reason reason = kind == LocationKind.STORED
                    ? RestoreRules.Reason.SUMMON : RestoreRules.Reason.RECOVER;
            return restoreTimed(record, family, reason, placement);
        });
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
                UUID profileId = record.profileId();
                CompletableFuture<RestoreFlow.Result> restored;
                try {
                    restored = restoreActive(record, family, RestoreRules.Reason.REVIVE, placement);
                } catch (RuntimeException | LinkageError failure) {
                    restored = CompletableFuture.failedFuture(failure);
                }
                // The charge follows the restore alone: once the companion is back the price is
                // kept, whatever happens while the view is built. A restore that threw is null.
                return restored.handle((result, failure) -> failure == null ? result : null)
                        .thenCompose(result -> settle(receipt, result == RestoreFlow.Result.RESTORED, profileId)
                                .thenApply(ignored -> result == null
                                        ? IndexBondedCompanionApi.<BondedCompanionProfileView>failure(
                                                BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED)
                                        : restoreResult(result, profileId)));
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

    /** Keeps the charge of a revive that brought the companion back and returns the charge of one that did not. */
    private CompletableFuture<Void> settle(BondedCompanionActionContext.ChargeReceipt receipt, boolean restored,
                                           UUID profileId) {
        CompletionStage<Boolean> settled;
        try {
            settled = restored ? receipt.completeAsync() : receipt.refundAsync();
        } catch (RuntimeException | LinkageError failure) {
            settled = CompletableFuture.failedFuture(failure);
        }
        if (settled == null) {
            settled = CompletableFuture.completedFuture(false);
        }
        return settled.toCompletableFuture().handle((ok, failure) -> {
            if (!restored && (failure != null || !Boolean.TRUE.equals(ok))) {
                LOGGER.at(Level.WARNING).withCause(failure).log(
                        "The revive price of bonded companion %s was not refunded after a failed revive", profileId);
            }
            return null;
        });
    }

    /** Gives this API its talent changes. Until then {@link #updateTalents} reports unavailable. */
    public void useTalents(@Nullable BondedTalentUpdates talents) {
        this.talents = talents;
    }

    /**
     * Buys a talent for a companion or resets its talents. An active companion is changed on its
     * body, on that body's world thread; any other companion in its stored snapshot
     * ({@link BondedTalentUpdates}). The record itself does not change, so the view's revision
     * stays the same. The returned view carries the new talents in its presentation data
     * ({@code talentConfigId}, {@code talentSpentPoints}, {@code talentAllocationRevision},
     * {@code talents}, {@code level}, {@code levelingConfigId}), and subscribers get a
     * {@code talents-updated} change with the same old and new state.
     *
     * <p>A change the talent tree, the level or the points do not allow is
     * {@code VALIDATION_FAILED}; so is a companion with no level data yet (one never summoned).
     * An active companion whose body is not loaded is {@code WORLD_UNAVAILABLE}.</p>
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> updateTalents(
            @Nonnull BondedCompanionTalentActionRequest request) {
        Objects.requireNonNull(request, "request");
        return guarded(() -> {
            BondedTalentUpdates updates = talents;
            if (updates == null) {
                return done(BondedCompanionResult.unavailable(TALENTS_UNAVAILABLE));
            }
            CompanionRecord record = record(request.profileId());
            if (record == null || !request.rosterId().equals(record.rosterId())) {
                return done(failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND));
            }
            if (!request.ownerUuid().equals(record.ownerUuid())) {
                return done(failure(BondedCompanionResultCode.NOT_OWNER, NOT_OWNER));
            }
            if (record.generation() != request.expectedRevision()) {
                return done(failure(BondedCompanionResultCode.REVISION_CONFLICT, PROFILE_REVISION_CONFLICT));
            }
            UUID profileId = record.profileId();
            return updates.update(record, request).thenApply(outcome -> switch (outcome.status()) {
                case APPLIED -> talentsUpdated(profileId, outcome);
                case REJECTED -> failure(BondedCompanionResultCode.VALIDATION_FAILED,
                        request.action() == BondedCompanionTalentActionRequest.Action.PURCHASE
                                ? TALENT_PURCHASE_REJECTED : TALENT_RESET_REJECTED);
                case DISABLED -> failure(BondedCompanionResultCode.VALIDATION_FAILED, TALENTS_DISABLED);
                case NO_LEVEL_DATA ->
                        failure(BondedCompanionResultCode.VALIDATION_FAILED, TALENT_LEVEL_DATA_UNAVAILABLE);
                case CONFLICT -> failure(BondedCompanionResultCode.REVISION_CONFLICT, PROFILE_REVISION_CONFLICT);
                case BODY_UNAVAILABLE ->
                        failure(BondedCompanionResultCode.WORLD_UNAVAILABLE, TALENT_BODY_UNAVAILABLE);
                case FAILED -> failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
            });
        });
    }

    /** The view after a talent change, published to subscribers as {@code talents-updated}. */
    private BondedCompanionResult<BondedCompanionProfileView> talentsUpdated(UUID profileId,
                                                                             BondedTalentUpdates.Outcome outcome) {
        CompanionRecord record = index.get(profileId);
        LinkedHashMap<String, String> data = new LinkedHashMap<>(record == null ? Map.of() : presentation(record));
        if (outcome.talents().getConfigId() != null && !outcome.talents().getConfigId().isBlank()) {
            data.put("talentConfigId", outcome.talents().getConfigId());
        }
        data.put("talentSpentPoints", Integer.toString(outcome.talents().getSpentPoints()));
        data.put("talentAllocationRevision", Long.toString(outcome.talents().getAllocationRevision()));
        // An empty list is a value too: it replaces the list a reset cleared.
        data.put("talents", String.join(", ", outcome.talents().getPurchasedTalentIds()));
        data.put("level", Integer.toString(outcome.level()));
        if (outcome.levelingConfigId() != null && !outcome.levelingConfigId().isBlank()) {
            data.put("levelingConfigId", outcome.levelingConfigId());
        }
        BondedCompanionProfileView view = record == null || record.ownerUuid() == null ? null
                : BondedRecords.view(record, index.fileRecords(record.ownerUuid()), families, clock.getAsLong(), data);
        if (view == null) {
            // The change was made, but the companion left the roster before it could be reported.
            return failure(BondedCompanionResultCode.REVISION_CONFLICT, PROFILE_REVISION_CONFLICT);
        }
        publish(new BondedCompanionChangedEvent(view.profileId(), view.ownerUuid(),
                view.rosterId(), view.state(), view.state(), view.revision(), "talents-updated"));
        return success(view);
    }

    /**
     * The level and talents of a companion that is not active, from its stored snapshot, for the
     * talent page (the list's views carry no purchased talent ids). Completes with null when the
     * companion is not this owner's in this roster, is active (its body holds its talents), or
     * has no readable snapshot, as a provisioned companion before its first summon.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedTalentUpdates.Stored> storedTalents(@Nonnull UUID ownerUuid,
                                                                       @Nonnull String rosterId,
                                                                       @Nonnull String profileId) {
        BondedTalentUpdates updates = talents;
        CompanionRecord record = closed || updates == null ? null : record(profileId);
        if (record == null || !ownerUuid.equals(record.ownerUuid()) || !rosterId.equals(record.rosterId())
                || record.location().kind() == LocationKind.LIVE) {
            return CompletableFuture.completedFuture(null);
        }
        return updates.read(record);
    }

    /**
     * Presentation entries of a record's summary, with the keys the 4.x views used: the role the
     * body had, level, health, and happiness and needs when the companion has them. An empty
     * summary (a provisioned companion never summoned) gives no entries. The purchased talent ids
     * are not in a summary; {@link #storedTalents} reads them.
     */
    private static Map<String, String> presentation(CompanionRecord record) {
        CompanionSummary summary = record.summary();
        LinkedHashMap<String, String> data = new LinkedHashMap<>();
        if (summary.roleId() != null && !summary.roleId().isBlank()) {
            data.put("roleId", summary.roleId());
        }
        // A captured companion has no stored name; the panel names it by its role name key.
        if (summary.nameKey() != null && !summary.nameKey().isBlank()) {
            data.put(BondedCompanionNames.NAME_KEY, summary.nameKey());
        }
        if (summary.levelingConfigId() != null) {
            data.put("levelingConfigId", summary.levelingConfigId());
            data.put("level", Integer.toString(summary.level()));
            data.put("currentXp", Double.toString(summary.currentXp()));
        }
        if (summary.healthMax() > 0f) {
            data.put("currentHealth", Double.toString(summary.healthCurrent()));
            data.put("maxHealth", Double.toString(summary.healthMax()));
        }
        if (summary.happinessConfigId() != null) {
            data.put("happiness", Double.toString(summary.happiness()));
        }
        if (summary.needsConfigId() != null) {
            data.put("hunger", Double.toString(summary.hunger()));
            data.put("thirst", Double.toString(summary.thirst()));
        }
        if (summary.talentsConfigId() != null) {
            data.put("talentConfigId", summary.talentsConfigId());
            data.put("talentSpentPoints", Integer.toString(summary.talentPointsSpent()));
        }
        return data;
    }

    private void publish(BondedCompanionChangedEvent event) {
        for (Consumer<BondedCompanionChangedEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.at(Level.WARNING).withCause(failure)
                        .log("A bonded companion subscriber failed for profile %s", event.profileId());
            }
        }
    }

    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>> getExtensionData(
            @Nonnull BondedCompanionExtensionDataKey key) {
        Objects.requireNonNull(key, "key");
        return guarded(() -> {
            if (!ExtensionEntries.publicNamespace(key.namespace())) {
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
     * value has revision 0 and each write adds one. Repeating a request whose value is already
     * the current one succeeds. A payload that is not valid JSON is refused.
     *
     * <p>The returned future is already complete when this method returns, as in 4.x: callers
     * read it at once. The change is in the index then, and it is on disk at the writer's next
     * write-behind flush, not before. {@code profileData().compareAndSet} is the call that waits
     * for the owner file.
     */
    @Override
    @Nonnull
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>> compareAndSetExtensionData(
            @Nonnull BondedCompanionExtensionDataUpdate update) {
        Objects.requireNonNull(update, "update");
        return guarded(() -> {
            BondedCompanionExtensionDataKey key = update.key();
            if (!ExtensionEntries.publicNamespace(key.namespace())
                    || update.expectedRevision() > Long.MAX_VALUE - 2L || !validPayload(update)) {
                return done(failure(BondedCompanionResultCode.VALIDATION_FAILED, REQUEST_INVALID));
            }
            CompanionRecord found = record(key.profileId());
            if (found == null) {
                return done(failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND));
            }
            // A stored entry's revision is the public revision plus one: 0 is "no value" on disk.
            return ExtensionEntries.compareAndSet(index, owner -> CompletableFuture.completedFuture(null),
                    found.profileId(), extensionKey(key),
                    update.expectedRevision() + 1L, update.jsonPayload(),
                    current -> BondedRecords.state(current) != null && key.ownerUuid().equals(current.ownerUuid()))
                    .thenApply(outcome -> switch (outcome.status()) {
                        case WRITTEN -> success(data(key, outcome.entry(), outcome.record()));
                        case NOT_FOUND -> failure(BondedCompanionResultCode.NOT_FOUND, NOT_FOUND);
                        case REVISION_MISMATCH ->
                                failure(BondedCompanionResultCode.REVISION_CONFLICT, EXTENSION_REVISION_CONFLICT);
                        case FLUSH_FAILED -> failure(BondedCompanionResultCode.INTERNAL_FAILURE, OPERATION_FAILED);
                    });
        });
    }

    /**
     * Whether the payload is JSON within the profile data limits. The profile data request owns
     * that rule, so it is built here only for its validation; the payload is stored as sent,
     * because callers compare the stored text with what they wrote.
     */
    private static boolean validPayload(BondedCompanionExtensionDataUpdate update) {
        try {
            new ProfileDataCompareAndSetRequest(update.key().profileId(), update.key().namespace(),
                    BondedRecords.EXTENSION_DATA_KEY, 0L, update.idempotencyKey(), update.jsonPayload());
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
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
                    : CompanionBodyLifecycle.CAUSE_OLD_AGE.equals(cause) ? "old_age" : "released";
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
        publish(new BondedCompanionChangedEvent(after.profileId().toString(), owner,
                rosterId, oldState, newState, after.generation(), reason));
    }

    /** Drops every subscriber; later calls report the API as unavailable. */
    @Override
    public void close() {
        closed = true;
        subscribers.clear();
    }

    /**
     * Restores the companion at the placement as an active companion. The family's session timer,
     * with the companion's talent modifiers, is part of the restore's commit.
     */
    private CompletableFuture<RestoreFlow.Result> restoreActive(
            CompanionRecord record, BondedCompanionPolicy family, RestoreRules.Reason reason,
            BondedCompanionPlacement placement) {
        RestoreFlow.Destination destination = new RestoreFlow.Destination(placement.worldKey(), placement.x(),
                placement.y(), placement.z(), placement.yawRadians(), placement.pitchRadians());
        return adjustedTimers(record, family).thenCompose(adjusted -> {
            long sessionMs = BondedRecords.millis(adjusted.sessionDurationSeconds());
            long untilMs = sessionMs > 0L ? saturatedAdd(clock.getAsLong(), sessionMs) : 0L;
            return restore.apply(RestoreFlow.Request.of(record.profileId(), reason, destination)
                    .withGeneration(record.generation()).withSummonedUntil(untilMs));
        });
    }

    /** {@link #restoreActive} with its result as the companion's new view or the refusal. */
    private CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> restoreTimed(
            CompanionRecord record, BondedCompanionPolicy family, RestoreRules.Reason reason,
            BondedCompanionPlacement placement) {
        return restoreActive(record, family, reason, placement)
                .thenApply(result -> restoreResult(result, record.profileId()));
    }

    private BondedCompanionResult<BondedCompanionProfileView> restoreResult(RestoreFlow.Result result,
                                                                            UUID profileId) {
        return result == RestoreFlow.Result.RESTORED ? viewResult(profileId) : restoreFailure(result);
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
        return ExtensionEntries.key(key.namespace(), BondedRecords.EXTENSION_DATA_KEY);
    }

    /** The public revision of a stored entry; {@code MISSING_REVISION} when there is none. */
    private static long publicRevision(@Nullable ExtensionEntry entry) {
        return ExtensionEntries.storedRevision(entry) - 1L;
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
}
