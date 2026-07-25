package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors captured-item-to-coop intake through the shared canonical coop operation.
 *
 * <p>This sibling of {@link DirectLiveCoopAuthor} accepts no Hytale runtime objects. It verifies
 * one current captured profile and snapshot, derives the target snapshot by changing only
 * physical coop placement, and submits the existing receipt-first coop capture operation. Item
 * marking, save barriers, durable state, cleanup, recovery, and publication remain owned by the
 * shared operation boundary.</p>
 */
public final class CapturedItemCoopAuthor {
    private static final String SLOT_REGISTRATION =
            "captured-item-coop-slot:v1";
    private final DirectLiveCoopPersistencePort persistence;
    private final CoopResidentStateSnapshotCodec snapshots =
            new CoopResidentStateSnapshotCodec();
    private final CapturedItemCoopEvidenceFactory evidence =
            new CapturedItemCoopEvidenceFactory();

    /** Creates the production author over the replacement facade bundle. */
    public CapturedItemCoopAuthor(
            @Nonnull PersistenceDomainFacades facades
    ) {
        this(new DirectLiveCoopDomainFacadePort(facades));
    }

    CapturedItemCoopAuthor(DirectLiveCoopPersistencePort persistence) {
        this.persistence = Objects.requireNonNull(
                persistence, "Captured-item coop persistence"
        );
    }

    /**
     * Submits one exact captured artifact into the first projected-free configured slot.
     */
    @Nonnull
    public CompletionStage<Outcome> capture(
            @Nullable Source source,
            @Nullable CapturedItemCoopTarget target
    ) {
        if (source == null || target == null
                || !source.sourceWorldKey().equals(target.worldKey())) {
            return completed(Outcome.INVALID_EVIDENCE);
        }
        CapturedItemCoopArtifactClaim claim =
                CapturedItemCoopArtifactClaim.parse(source.sourceArtifact());
        if (claim == null || source.sourceArtifact().quantity() != 1) {
            return completed(Outcome.UNMANAGED_ARTIFACT);
        }
        CoopSlotKey slot = availableSlot(target);
        if (slot == null) {
            return completed(Outcome.FULL);
        }
        CompletionStage<Outcome> authored;
        try {
            authored = ensureRegistered(slot).thenCompose(registration -> {
                if (registration != Outcome.REGISTERED
                        && registration != Outcome.ALREADY_REGISTERED) {
                    return completed(registration);
                }
                return persistence.findProfile(claim.profileId())
                        .thenCompose(read -> prepareAndSubmit(
                                source, target, slot, claim, read
                        ));
            });
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.AUTHOR_FAILED);
        }
        return authored.handle((outcome, failure) ->
                failure == null && outcome != null
                        ? outcome : Outcome.AUTHOR_FAILED
        );
    }

    private CompletionStage<Outcome> prepareAndSubmit(
            Source source,
            CapturedItemCoopTarget target,
            CoopSlotKey slot,
            CapturedItemCoopArtifactClaim claim,
            PersistenceReadResult<CompanionProfileReadModel> read
    ) {
        if (!(read instanceof PersistenceReadResult.Found<
                CompanionProfileReadModel> found)) {
            return completed(
                    read instanceof PersistenceReadResult.Failed<?>
                            ? Outcome.READ_FAILED
                            : Outcome.PROFILE_UNAVAILABLE
            );
        }
        CapturedItemCoopEvidenceFactory.Prepared prepared = prepare(
                source, target, slot, claim, found.value()
        );
        if (prepared == null) {
            return completed(Outcome.PROFILE_CONFLICT);
        }
        PublicOperationSubmission submission;
        try {
            submission = persistence.captureToCoop(
                    prepared.operationId(),
                    prepared.idempotencyKey(),
                    prepared.request()
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(Outcome.CAPTURE_FAILED);
        }
        return completionOutcome(
                submission,
                Outcome.CAPTURE_SUBMITTED,
                Outcome.CAPTURE_FAILED
        );
    }

    @Nullable
    private CapturedItemCoopEvidenceFactory.Prepared prepare(
            Source source,
            CapturedItemCoopTarget target,
            CoopSlotKey slot,
            CapturedItemCoopArtifactClaim claim,
            CompanionProfileReadModel profile
    ) {
        CompanionSnapshot captureSnapshot =
                exactCaptureSnapshot(profile, claim);
        CoopResidentStateSnapshot decoded =
                decodeCaptureSnapshot(captureSnapshot);
        if (!exactCapturedProfile(profile, claim, captureSnapshot, decoded)
                || !policyAllows(source, target, profile, decoded)) {
            return null;
        }
        return evidence.create(
                source, slot, claim, profile, captureSnapshot
        );
    }

    @Nullable
    private CompanionSnapshot exactCaptureSnapshot(
            CompanionProfileReadModel profile,
            CapturedItemCoopArtifactClaim claim
    ) {
        if (profile == null
                || !claim.profileId().equals(
                profile.identity().profileId()
        )) {
            return null;
        }
        CompanionSnapshot found = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            if (!claim.captureSnapshotId().equals(snapshot.snapshotId())) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = snapshot;
        }
        return found;
    }

    private boolean exactCapturedProfile(
            CompanionProfileReadModel profile,
            CapturedItemCoopArtifactClaim claim,
            @Nullable CompanionSnapshot captureSnapshot,
            @Nullable CoopResidentStateSnapshot decoded
    ) {
        CompanionAlias alias = profile.currentAlias();
        LifecycleLocation expectedLocation = captureSnapshot == null
                ? null
                : LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        captureSnapshot.snapshotId().toString()
                );
        return captureSnapshot != null
                && decoded != null
                && profile.lifecycle().state() == LifecycleState.CAPTURED
                && expectedLocation.equals(profile.lifecycle().location())
                && profile.lifecycle().activeOperationId() == null
                && !profile.lifecycle().quarantined()
                && alias != null
                && alias.state() == CompanionAlias.State.CURRENT
                && alias.alias().equals(claim.sourceAlias())
                && captureSnapshot.kind().equals(
                CompanionCaptureRequest.SNAPSHOT_KIND
        )
                && captureSnapshot.payloadVersion()
                == CompanionCaptureRequest.SNAPSHOT_VERSION
                && captureSnapshot.current()
                && captureSnapshot.sourceLifecycleRevision().next()
                .equals(profile.lifecycle().revision())
                && claim.sourceAlias().value().equals(decoded.npcUuid())
                && exactRole(profile.identity().roleId(), decoded.roleId());
    }

    private boolean policyAllows(
            Source source,
            CapturedItemCoopTarget target,
            CompanionProfileReadModel profile,
            CoopResidentStateSnapshot decoded
    ) {
        if (!target.acceptsRole(profile.identity().roleId())) {
            return false;
        }
        if (target.requireTamed()
                && (decoded.tamed() == null
                || !decoded.tamed().isTamed())) {
            return false;
        }
        OwnerId owner = profile.lifecycle().ownerId();
        if (target.requireOwner() && owner == null) {
            return false;
        }
        return !target.ownerRestricted()
                || owner != null
                && owner.value().equals(source.actorUuid());
    }

    @Nullable
    private CoopResidentStateSnapshot decodeCaptureSnapshot(
            @Nullable CompanionSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        CoopResidentStateSnapshotCodec.DecodeResult decoded =
                snapshots.decode(snapshot.payloadJson());
        return decoded.status()
                == CoopResidentStateSnapshotCodec.Status.FOUND
                ? decoded.snapshotOrNull()
                : null;
    }

    @Nullable
    private CoopSlotKey availableSlot(CapturedItemCoopTarget target) {
        Map<CoopSlotKey, CoopOccupancy> occupied =
                persistence.projectedCoopSnapshot();
        for (CoopSlotKey slot : target.slots()) {
            if (!occupied.containsKey(slot)) {
                return slot;
            }
        }
        return null;
    }

    private CompletionStage<Outcome> ensureRegistered(CoopSlotKey slot) {
        if (persistence.projectedCoopSnapshot().containsKey(slot)) {
            return completed(Outcome.FULL);
        }
        return persistence.findCoopSlot(slot).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<CoopSlot>) {
                return completed(Outcome.ALREADY_REGISTERED);
            }
            if (read instanceof PersistenceReadResult.Failed<CoopSlot>) {
                return completed(Outcome.READ_FAILED);
            }
            String[] parts = {slot.toString()};
            PublicOperationSubmission submission;
            try {
                submission = persistence.registerCoopSlot(
                        StablePersistenceIds.operationId(
                                SLOT_REGISTRATION, parts
                        ),
                        StablePersistenceIds.idempotencyKey(
                                SLOT_REGISTRATION, parts
                        ),
                        new CoopSlotRegistration(
                                CoopSlot.unoccupied(slot), 0L
                        )
                );
            } catch (RuntimeException | LinkageError failure) {
                return completed(Outcome.REGISTRATION_FAILED);
            }
            return completionOutcome(
                    submission,
                    Outcome.REGISTERED,
                    Outcome.REGISTRATION_FAILED
            );
        });
    }

    private CompletionStage<Outcome> completionOutcome(
            @Nullable PublicOperationSubmission submission,
            Outcome success,
            Outcome failure
    ) {
        if (submission == null || !submission.accepted()) {
            return completed(failure);
        }
        return submission.completion().handle(
                (result, completionFailure) ->
                        completionFailure == null && published(result)
                                ? success : failure
        );
    }

    private boolean exactRole(
            @Nullable String identityRole,
            @Nullable String snapshotRole
    ) {
        return identityRole != null && snapshotRole != null
                && identityRole.equalsIgnoreCase(snapshotRole);
    }

    private boolean published(@Nullable OperationWorkflowResult result) {
        return result != null
                && result.status()
                == OperationWorkflowResult.Status.PUBLISHED;
    }

    private CompletionStage<Outcome> completed(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    /**
     * Exact player-inventory evidence frozen on the owning world thread.
     *
     * <p>The position is relative to its named HOTBAR, STORAGE, or BACKPACK section. Combined
     * inventory offsets are deliberately not accepted.</p>
     */
    public record Source(
            @Nonnull java.util.UUID actorUuid,
            @Nonnull String sourceWorldKey,
            @Nonnull CoopCapturedItemInventoryPosition inventoryPosition,
            @Nonnull com.alechilles.alecstamework.companion.capture
                    .CapturedArtifact sourceArtifact
    ) {
        public Source {
            if (actorUuid == null || inventoryPosition == null
                    || sourceArtifact == null || sourceWorldKey == null
                    || sourceWorldKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Complete captured-item coop source is required"
                );
            }
            sourceWorldKey = sourceWorldKey.trim();
        }
    }

    /** Stable gameplay-facing outcomes for one captured-item authoring attempt. */
    public enum Outcome {
        CAPTURE_SUBMITTED,
        UNMANAGED_ARTIFACT,
        INVALID_EVIDENCE,
        FULL,
        REGISTERED,
        ALREADY_REGISTERED,
        REGISTRATION_FAILED,
        PROFILE_UNAVAILABLE,
        PROFILE_CONFLICT,
        READ_FAILED,
        CAPTURE_FAILED,
        AUTHOR_FAILED
    }

}
