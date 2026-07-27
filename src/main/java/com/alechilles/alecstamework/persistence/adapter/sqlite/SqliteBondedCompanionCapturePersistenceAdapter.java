package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProfile;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.BondedCompanionCaptureAuthor;
import com.alechilles.alecstamework.items.BondedCompanionCaptureAttemptEvidence;
import com.alechilles.alecstamework.items.BondedCompanionCaptureIntent;
import com.alechilles.alecstamework.items.BondedCompanionCaptureReplayGateway;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperationProbe;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEventPublisher;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStore;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * SQLite boundary for the explicit bonded-capture author.
 *
 * <p>Keeps adapter-specific durability out of gameplay orchestration while
 * preserving one atomic profile-and-cleanup transaction.</p>
 */
public final class SqliteBondedCompanionCapturePersistenceAdapter
        implements BondedCompanionCaptureReplayGateway {
    private static final long OPERATION_RETENTION_MS = 2_592_000_000L;
    private static final long CLEANUP_RETENTION_MS = 300_000L;

    private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionTransitionService transitions;
    private final BondedCompanionStore profiles;
    private final SqliteBondedCompanionDatabase database;
    private final SqliteBondedCompanionProjectionDurability durability;
    private final BondedCompanionProjectionCleanupService cleanup;
    @Nullable
    private final BondedCompanionCaptureEventPublisher captureEvents;
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    public SqliteBondedCompanionCapturePersistenceAdapter(
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nonnull BondedCompanionTransitionService transitions,
            @Nonnull BondedCompanionStore profiles,
            @Nonnull SqliteBondedCompanionDatabase database,
            @Nonnull SqliteBondedCompanionProjectionDurability durability,
            @Nonnull BondedCompanionProjectionCleanupService cleanup
    ) {
        this(
                rosters, transitions, profiles, database, durability, cleanup,
                null
        );
    }

    public SqliteBondedCompanionCapturePersistenceAdapter(
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nonnull BondedCompanionTransitionService transitions,
            @Nonnull BondedCompanionStore profiles,
            @Nonnull SqliteBondedCompanionDatabase database,
            @Nonnull SqliteBondedCompanionProjectionDurability durability,
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nullable BondedCompanionCaptureEventPublisher captureEvents
    ) {
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.database = Objects.requireNonNull(database, "database");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.captureEvents = captureEvents;
    }

    /** Validates current policy and capacity without mutating durable state. */
    @Nonnull
    public BondedCompanionCaptureAuthor.PolicyDecision validate(
            @Nonnull BondedCompanionCaptureIntent intent
    ) {
        if (profiles.findProfile(intent.actorUuid(), intent.rosterId(),
                intent.profileId()).isPresent()) {
            return BondedCompanionCaptureAuthor.PolicyDecision.ALLOWED;
        }
        BondedCompanionTransitionService.TransitionResult result =
                transition(intent);
        return switch (result.code()) {
            case APPLIED -> BondedCompanionCaptureAuthor.PolicyDecision.ALLOWED;
            case ROLE_NOT_ALLOWED -> BondedCompanionCaptureAuthor.PolicyDecision
                    .ROLE_REJECTED;
            case OWNED_CAPACITY_REACHED -> BondedCompanionCaptureAuthor
                    .PolicyDecision.CAPACITY_REJECTED;
            default -> BondedCompanionCaptureAuthor.PolicyDecision.REJECTED;
        };
    }

    /** Commits the complete stored profile and exact cleanup atomically. */
    @Nonnull
    public BondedCompanionCaptureAuthor.PersistenceOutcome store(
            @Nonnull BondedCompanionCaptureIntent intent
    ) {
        if (intent.snapshot() == null) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        ExactResult replay = probeExact(intent);
        if (replay.status() == ExactStatus.REPLAYED) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED;
        }
        if (replay.status() != ExactStatus.ABSENT) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        long nowMs = intent.snapshot().fullState().capturedAtMs();
        BondedCompanionOperation operation = operation(intent, nowMs);
        BondedCompanionRosterRegistry.RosterDefinition family =
                family(intent);
        if (family == null) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        BondedCompanionRecord.Profile profile = profiles.findProfile(
                intent.actorUuid(), intent.rosterId(), intent.profileId()
        ).orElseGet(() -> newProfile(intent, nowMs));
        if (profile == null) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> result =
                database.createCapturedProfile(
                        operation, profile, cleanupRecord(intent, nowMs),
                        family.maximumOwned(),
                        captureEvidence(intent, profile, operation, nowMs)
                );
        if (result.code() != BondedCompanionStoreResult.Code.APPLIED) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        publishCaptureEvents();
        return result.replayed()
                ? BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED
                : BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED;
    }

    /** Finds immutable committed evidence without consulting current policy. */
    @Override
    @Nonnull
    public LookupResult lookup(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        try {
            var captured = database.findCaptureEvidenceBySource(
                    request.sourceNpcUuid());
            if (captured.isEmpty()) return LookupResult.absent();
            if (captured.size() != 1) return LookupResult.conflict();
            BondedCompanionCaptureEvidence evidence = captured.get(0);
            if (!matches(request, evidence)) return LookupResult.conflict();

            var prior = profiles.findProfileOperationByIdentity(
                    new BondedCompanionOperationProbe(
                            evidence.callerNamespace(),
                            evidence.idempotencyKey(), evidence.ownerUuid(),
                            evidence.rosterId(), evidence.profileId(),
                            BondedCompanionOperation.Type.CAPTURE));
            if (prior.isEmpty()) return LookupResult.failed();
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> result =
                    prior.get();
            if (result.code()
                    == BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT) {
                return LookupResult.conflict();
            }
            if (result.code() != BondedCompanionStoreResult.Code.APPLIED
                    || result.value() == null || !result.replayed()) {
                return LookupResult.failed();
            }
            BondedCompanionSnapshot snapshot = decode(result.value());
            if (snapshot == null) return LookupResult.failed();
            return LookupResult.matched(new Evidence(
                    attemptEvidence(evidence), evidence.roleId(),
                    evidence.familyId(), evidence.sourceWorldKey(), snapshot));
        } catch (RuntimeException failure) {
            return LookupResult.failed();
        }
    }

    /** Probes the full request hash without resolving a current family. */
    @Override
    @Nonnull
    public ExactResult probeExact(@Nonnull BondedCompanionCaptureIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent.snapshot() == null) return ExactResult.absent();
        try {
            long nowMs = intent.snapshot().fullState().capturedAtMs();
            var prior = profiles.findProfileOperationByExactRequest(
                    operation(intent, nowMs));
            if (prior.isEmpty()) return ExactResult.absent();
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> result =
                    prior.get();
            if (result.code()
                    == BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT) {
                return ExactResult.conflict();
            }
            if (result.code() != BondedCompanionStoreResult.Code.APPLIED
                    || result.value() == null || !result.replayed()) {
                return ExactResult.failed();
            }
            publishCaptureEvents();
            return ExactResult.replayed();
        } catch (RuntimeException failure) {
            return ExactResult.failed();
        }
    }

    private boolean matches(
            Request request,
            BondedCompanionCaptureEvidence evidence
    ) {
        return request.callerNamespace().equals(evidence.callerNamespace())
                && request.idempotencyKey().equals(evidence.idempotencyKey())
                && request.actorUuid().equals(evidence.ownerUuid())
                && request.rosterId().equals(evidence.rosterId())
                && request.sourceNpcUuid().equals(evidence.sourceNpcUuid())
                && request.sourceWorldKey().equals(evidence.sourceWorldKey())
                && request.sourceItemId().equals(evidence.sourceItemId())
                && request.roleId().equals(evidence.roleId())
                && evidence.outcome()
                == com.alechilles.alecstamework.api.CaptureAttemptOutcome.CAPTURED
                && evidence.successDisposition()
                == com.alechilles.alecstamework.api.CaptureSuccessDisposition
                .STORE_BONDED_COMPANION;
    }

    @Nullable
    private BondedCompanionSnapshot decode(
            BondedCompanionRecord.Profile profile
    ) {
        String encoded = new String(
                profile.snapshot().bytes(), StandardCharsets.UTF_8);
        BondedCompanionSnapshotCodec.DecodeResult decoded = snapshots.decode(
                encoded);
        return decoded.status() == BondedCompanionSnapshotCodec.Status.FOUND
                ? decoded.snapshot() : null;
    }

    private BondedCompanionCaptureAttemptEvidence attemptEvidence(
            BondedCompanionCaptureEvidence evidence
    ) {
        return new BondedCompanionCaptureAttemptEvidence(
                evidence.attemptId(), evidence.sourceItemId(),
                evidence.spawnerConfigId(), evidence.spawnerConfigRevision(),
                evidence.capturePolicyConfigId(),
                evidence.capturePolicyConfigRevision(),
                evidence.sourceConsumption(), evidence.successDisposition(),
                evidence.outcome(), evidence.reason());
    }

    private void publishCaptureEvents() {
        if (captureEvents == null) return;
        try {
            captureEvents.publishPending(64);
        } catch (RuntimeException | LinkageError ignored) {
            // The committed tombstone remains pending for maintenance replay.
        }
    }

    /** Attempts only the durable exact-source cleanup and records its outcome. */
    @Nonnull
    public BondedCompanionCaptureAuthor.CleanupOutcome cleanup(
            @Nonnull BondedCompanionCaptureIntent intent
    ) {
        long nowMs = intent.snapshot().fullState().capturedAtMs();
        BondedCompanionProjectionCleanupService.Outcome outcome =
                durability.attemptCleanup(
                        cleanup, cleanupIntent(intent, nowMs), nowMs
                );
        return switch (outcome) {
            case REMOVED -> BondedCompanionCaptureAuthor.CleanupOutcome.REMOVED;
            case ALREADY_MISSING -> BondedCompanionCaptureAuthor.CleanupOutcome
                    .ALREADY_MISSING;
            default -> BondedCompanionCaptureAuthor.CleanupOutcome.RETRY_PENDING;
        };
    }

    @Nullable
    private BondedCompanionRecord.Profile newProfile(
            BondedCompanionCaptureIntent intent,
            long nowMs
    ) {
        BondedCompanionTransitionService.TransitionResult result =
                transition(intent);
        return result.applied() && result.profile() != null
                ? profile(intent, result.profile(), nowMs) : null;
    }

    private BondedCompanionTransitionService.TransitionResult transition(
            BondedCompanionCaptureIntent intent
    ) {
        BondedCompanionSnapshot snapshot = claimed(intent);
        BondedCompanionRosterRegistry.RosterDefinition family = family(intent);
        int owned = family == null ? 0 : (int) profiles.listProfiles(
                intent.actorUuid(), intent.rosterId()).stream()
                .filter(profile -> family.familyId().equals(profile.familyId()))
                .count();
        return transitions.createCaptured(
                new BondedCompanionTransitionService.CreationRequest(
                        intent.callerNamespace() + ":" + intent.idempotencyKey(),
                        intent.actorUuid(), intent.rosterId(), intent.profileId(),
                        intent.roleId(), snapshot, intent.rosterRevision(),
                        snapshot.fullState().capturedAtMs(), intent.familyId()
                ),
                new BondedCompanionTransitionService.RosterCounts(owned, 0)
        );
    }

    @Nullable
    private BondedCompanionRosterRegistry.RosterDefinition family(
            BondedCompanionCaptureIntent intent
    ) {
        if (intent.familyId() != null) {
            return rosters.resolve(intent.rosterId(), intent.familyId())
                    .orElse(null);
        }
        BondedCompanionRosterRegistry.FamilyResolution selected =
                rosters.resolveForRole(intent.rosterId(), intent.roleId());
        return selected.status()
                == BondedCompanionRosterRegistry.FamilyResolutionStatus.FOUND
                ? selected.definition() : null;
    }

    private BondedCompanionSnapshot claimed(BondedCompanionCaptureIntent intent) {
        CoopResidentStateSnapshot source = intent.snapshot().fullState();
        CoopResidentStateSnapshot claimed = new CoopResidentStateSnapshot(
                source.npcUuid(), source.coopId(), source.residentSlot(),
                source.roleId(), source.commandLinks(),
                new TameworkOwnerComponent(intent.actorUuid(), null),
                new TameworkTamedComponent(true), source.npcName(),
                source.happiness(), source.needs(), source.breeding(),
                source.leveling(), source.traits(), source.talents(),
                source.lifeStage(), source.attachments(), source.healthPercent(),
                source.capturedAtMs()
        );
        return BondedCompanionSnapshot.of(
                claimed, intent.snapshot().extensionData()
        );
    }

    private BondedCompanionRecord.Profile profile(
            BondedCompanionCaptureIntent intent,
            BondedCompanionProfile profile,
            long nowMs
    ) {
        CoopResidentStateSnapshot full = profile.snapshot().fullState();
        String displayName = full.npcName() == null
                ? null : full.npcName().getName();
        String gender = full.lifeStage() == null
                ? null : full.lifeStage().getGender();
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("policyRevision", Long.toString(intent.rosterRevision()));
        return new BondedCompanionRecord.Profile(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                profile.familyId(), profile.roleId(), BondedCompanionState.STORED,
                0L, BondedCompanionPayload.of(snapshots.encode(profile.snapshot())
                        .getBytes(StandardCharsets.UTF_8)), nowMs, nowMs, policy,
                displayName, intent.species(), gender, null,
                0L, 0L, null, null
        );
    }

    private BondedCompanionOperation operation(
            BondedCompanionCaptureIntent intent,
            long nowMs
    ) {
        return new BondedCompanionOperation(
                intent.callerNamespace(), intent.idempotencyKey(), hash(intent),
                intent.actorUuid(), intent.rosterId(), intent.profileId(),
                BondedCompanionOperation.Type.CAPTURE, nowMs,
                safeAdd(nowMs, OPERATION_RETENTION_MS)
        );
    }

    private BondedCompanionCaptureEvidence captureEvidence(
            BondedCompanionCaptureIntent intent,
            BondedCompanionRecord.Profile profile,
            BondedCompanionOperation operation,
            long committedAtMs
    ) {
        var attempt = intent.attemptEvidence();
        return new BondedCompanionCaptureEvidence(
                stableOperationId(operation), attempt.attemptId(),
                intent.actorUuid(), intent.rosterId(), profile.familyId(),
                intent.sourceNpcUuid(), intent.profileId(), intent.roleId(),
                operation.callerNamespace(), operation.idempotencyKey(),
                attempt.sourceItemId(), attempt.spawnerConfigId(),
                attempt.spawnerConfigRevision(),
                attempt.capturePolicyConfigId(),
                attempt.capturePolicyConfigRevision(),
                attempt.sourceConsumption(), attempt.successDisposition(),
                attempt.outcome(), attempt.reason(), intent.worldKey(),
                committedAtMs
        );
    }

    private UUID stableOperationId(BondedCompanionOperation operation) {
        return UUID.nameUUIDFromBytes((
                "tamework:bonded-capture-operation:v1\0"
                        + operation.callerNamespace() + "\0"
                        + operation.idempotencyKey()
        ).getBytes(StandardCharsets.UTF_8));
    }

    private BondedCompanionRecord.Cleanup cleanupRecord(
            BondedCompanionCaptureIntent intent,
            long nowMs
    ) {
        BondedCompanionProjectionCleanupService.CleanupIntent cleanup =
                cleanupIntent(intent, nowMs);
        return new BondedCompanionRecord.Cleanup(
                cleanup.cleanupId(), cleanup.ownerUuid(), cleanup.rosterId(),
                cleanup.profileId(), null,
                BondedCompanionRecord.CleanupTarget.SOURCE,
                cleanup.targetNpcUuid(), cleanup.worldKey(), cleanup.reason(),
                BondedCompanionRecord.CleanupState.PENDING, 0, nowMs, nowMs,
                cleanup.retainedUntilMs()
        );
    }

    private BondedCompanionProjectionCleanupService.CleanupIntent cleanupIntent(
            BondedCompanionCaptureIntent intent,
            long nowMs
    ) {
        return new BondedCompanionProjectionCleanupService.CleanupIntent(
                intent.profileId() + ":capture-source", intent.actorUuid(),
                intent.rosterId(), intent.profileId(), null,
                BondedCompanionProjectionCleanupService.Target.SOURCE,
                intent.sourceNpcUuid(), intent.worldKey(), "capture", nowMs,
                safeAdd(nowMs, CLEANUP_RETENTION_MS)
        );
    }

    private String hash(BondedCompanionCaptureIntent intent) {
        var attempt = intent.attemptEvidence();
        String canonical = intent.actorUuid() + "\0" + intent.rosterId()
                + "\0" + intent.roleId() + "\0" + intent.sourceNpcUuid()
                + "\0" + attempt.attemptId()
                + "\0" + attempt.sourceItemId()
                + "\0" + attempt.spawnerConfigId()
                + "\0" + attempt.spawnerConfigRevision()
                + "\0" + attempt.capturePolicyConfigId()
                + "\0" + attempt.capturePolicyConfigRevision()
                + "\0" + attempt.sourceConsumption()
                + "\0" + attempt.successDisposition()
                + "\0" + attempt.outcome()
                + "\0" + attempt.reason()
                + "\0" + snapshots.encode(requestIdentitySnapshot(intent));
        if (intent.familySelection()
                == BondedCompanionCaptureIntent.FamilySelection.EXPLICIT) {
            canonical += "\0family:" + intent.familyId();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Excludes only the volatile observation timestamp from retry identity. */
    private BondedCompanionSnapshot requestIdentitySnapshot(
            BondedCompanionCaptureIntent intent
    ) {
        BondedCompanionSnapshot claimed = claimed(intent);
        CoopResidentStateSnapshot state = claimed.fullState();
        CoopResidentStateSnapshot stable = new CoopResidentStateSnapshot(
                state.npcUuid(), state.coopId(), state.residentSlot(),
                state.roleId(), state.commandLinks(), state.owner(), state.tamed(),
                state.npcName(), state.happiness(), state.needs(), state.breeding(),
                state.leveling(), state.traits(), state.talents(), state.lifeStage(),
                state.attachments(), state.healthPercent(), 0L
        );
        return BondedCompanionSnapshot.of(stable, claimed.extensionData());
    }

    private long safeAdd(long value, long amount) {
        try {
            return Math.addExact(value, amount);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
