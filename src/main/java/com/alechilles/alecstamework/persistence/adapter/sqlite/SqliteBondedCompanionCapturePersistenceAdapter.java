package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProfile;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.BondedCompanionCaptureAuthor;
import com.alechilles.alecstamework.items.BondedCompanionCaptureIntent;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * SQLite boundary for the explicit bonded-capture author.
 *
 * <p>Keeps adapter-specific durability out of gameplay orchestration while
 * preserving one atomic profile-and-cleanup transaction.</p>
 */
public final class SqliteBondedCompanionCapturePersistenceAdapter {
    private static final long OPERATION_RETENTION_MS = 2_592_000_000L;
    private static final long CLEANUP_RETENTION_MS = 300_000L;

    private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionTransitionService transitions;
    private final BondedCompanionStore profiles;
    private final SqliteBondedCompanionDatabase database;
    private final SqliteBondedCompanionProjectionDurability durability;
    private final BondedCompanionProjectionCleanupService cleanup;
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
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.database = Objects.requireNonNull(database, "database");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
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
        BondedCompanionRosterRegistry.RosterDefinition roster =
                rosters.resolve(intent.rosterId()).orElse(null);
        if (roster == null || intent.snapshot() == null) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        long nowMs = intent.snapshot().fullState().capturedAtMs();
        BondedCompanionRecord.Profile profile = profiles.findProfile(
                intent.actorUuid(), intent.rosterId(), intent.profileId()
        ).orElseGet(() -> newProfile(intent, nowMs));
        if (profile == null) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> result =
                database.createCapturedProfile(
                        operation(intent, nowMs), profile,
                        cleanupRecord(intent, nowMs), roster.maximumOwned()
                );
        if (result.code() != BondedCompanionStoreResult.Code.APPLIED) {
            return BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;
        }
        return result.replayed()
                ? BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED
                : BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED;
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
        int owned = profiles.listProfiles(
                intent.actorUuid(), intent.rosterId()
        ).size();
        return transitions.createCaptured(
                new BondedCompanionTransitionService.CreationRequest(
                        intent.callerNamespace() + ":" + intent.idempotencyKey(),
                        intent.actorUuid(), intent.rosterId(), intent.profileId(),
                        intent.roleId(), snapshot, intent.rosterRevision(),
                        snapshot.fullState().capturedAtMs()
                ),
                new BondedCompanionTransitionService.RosterCounts(owned, 0)
        );
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
        String canonical = intent.actorUuid() + "\0" + intent.rosterId()
                + "\0" + intent.roleId() + "\0" + intent.sourceNpcUuid()
                + "\0" + snapshots.encode(requestIdentitySnapshot(intent));
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
