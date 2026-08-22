package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureAttemptReplayEvidence;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps one replay-complete durable payload to the public semantic event without state joins. */
public final class CaptureAttemptPublicEventMapper {
    private CaptureAttemptPublicEventMapper() {
    }

    @Nonnull
    public static com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent
    map(
            @Nonnull CaptureAttemptResolvedEvent event,
            long emittedAtMs
    ) {
        if (event == null || !event.replayComplete()) {
            throw new IllegalArgumentException(
                    "Replay-complete resolved capture evidence is required"
            );
        }
        CompanionCaptureRequest request = event.request();
        CaptureAttemptResolution resolution = request.resolution();
        CaptureAttemptFormula formula = resolution.formula();
        CaptureSourceEvidence source = request.source();
        CaptureAttemptReplayEvidence replay = new CaptureAttemptReplayEvidence(
                event.operationIdempotencyKey().toString(),
                request.resultingOwnerId() == null
                        ? null
                        : request.resultingOwnerId().value(),
                resolution.callerNamespace(),
                resolution.callerIdempotencyKey(),
                request.targetWorldKey(),
                displayName(request, resolution),
                request.expectedLifecycleRevision().value(),
                lifecycle(request),
                new CaptureAttemptReplayEvidence.Formula(
                        formula.chanceMode(),
                        formula.baseChance(),
                        formula.chancePerPower(),
                        formula.minimumChance(),
                        formula.maximumChance(),
                        formula.resistance(),
                        formula.chanceMultiplier(),
                        formula.missingHealthBonus(),
                        formula.guaranteedAtPower(),
                        formula.requirementsHash().toString(),
                        formula.requirementGeneration(),
                        resolution.entropy(),
                        resolution.failureCooldownUntilMs(),
                        resolution.sourceConsumption(),
                        resolution.successDisposition(),
                        resolution.currentHealth(),
                        resolution.maximumHealth()
                ),
                new CaptureAttemptReplayEvidence.Source(
                        source.slot(),
                        source.quantity(),
                        source.beforeFingerprint().toString(),
                        source.remainingQuantity(),
                        source.remainingFingerprint() == null
                                ? null
                                : source.remainingFingerprint().toString(),
                        source.receiptKey()
                ),
                snapshot(request),
                request.requestedAtMs()
        );
        return new com.alechilles.alecstamework.api
                .CaptureAttemptResolvedEvent(
                resolution.attemptId(),
                event.operationId().value(),
                source.actorUuid(),
                request.targetAlias().value(),
                request.profileId().toString(),
                resolution.targetRoleId(),
                source.sourceItemId(),
                formula.itemConfigId(),
                formula.itemConfigRevision(),
                formula.policyConfigId(),
                formula.policyConfigId() == null
                        ? -1L
                        : formula.policyConfigRevision(),
                formula.itemPower(),
                formula.minimumPower(),
                resolution.currentHealth(),
                resolution.maximumHealth(),
                resolution.missingHealthFraction(),
                formula.missingHealthBonus(),
                resolution.effectiveChance(),
                resolution.guaranteed(),
                resolution.successful()
                        ? CaptureAttemptOutcome.CAPTURED
                        : CaptureAttemptOutcome.FAILED_ROLL,
                resolution.reason(),
                event.resolvedAtMs(),
                emittedAtMs,
                replay
        );
    }

    /** Publishes the tame activity carried by a committed capture-and-tame event. */
    public static void publishTameActivity(
            @Nonnull CaptureAttemptResolvedEvent event
    ) {
        if (event == null || !event.replayComplete()) {
            return;
        }
        CompanionCaptureRequest request = event.request();
        if (!request.tameAndCommandLink()
                || request.resultingOwnerId() == null
                || event.operationId() == null) {
            return;
        }
        ActivityRuntime.publishTame(
                event.operationId().value(),
                request.tameAndLinkEvidence().targetIdentity().roleId(),
                request.resultingOwnerId().value(),
                request.targetAlias().value()
        );
    }

    private static CaptureAttemptReplayEvidence.Lifecycle lifecycle(
            CompanionCaptureRequest request
    ) {
        if (request.tameAndCommandLink()) {
            return lifecycle(
                    request.tameAndLinkEvidence().finalLifecycle()
            );
        }
        if (request.capturedItem()) {
            return new CaptureAttemptReplayEvidence.Lifecycle(
                    LifecycleState.CAPTURED.name(),
                    LifecycleLocationKind.CAPTURE_ITEM.name(),
                    request.snapshot().snapshotId().toString(),
                    null,
                    request.expectedLifecycleRevision().next().next().value()
            );
        }
        return new CaptureAttemptReplayEvidence.Lifecycle(
                LifecycleState.ACTIVE.name(),
                LifecycleLocationKind.LIVE_ENTITY.name(),
                request.targetAlias().toString(),
                request.targetWorldKey(),
                request.expectedLifecycleRevision().value()
        );
    }

    private static CaptureAttemptReplayEvidence.Lifecycle lifecycle(
            CompanionLifecycle lifecycle
    ) {
        LifecycleLocation location = lifecycle.location();
        return new CaptureAttemptReplayEvidence.Lifecycle(
                lifecycle.state().name(),
                location.kind().name(),
                location.key(),
                location.worldKey(),
                lifecycle.revision().value()
        );
    }

    @Nullable
    private static CaptureAttemptReplayEvidence.Snapshot snapshot(
            CompanionCaptureRequest request
    ) {
        if (!request.capturedItem()) {
            return null;
        }
        CompanionSnapshot snapshot = request.snapshot();
        return new CaptureAttemptReplayEvidence.Snapshot(
                snapshot.snapshotId().value(),
                snapshot.kind().value(),
                snapshot.payloadVersion(),
                snapshot.payloadJson(),
                snapshot.payloadHash().toString(),
                snapshot.sourceLifecycleRevision().value(),
                snapshot.current(),
                snapshot.createdAtMs()
        );
    }

    @Nullable
    private static String displayName(
            CompanionCaptureRequest request,
            CaptureAttemptResolution resolution
    ) {
        if (request.tameAndCommandLink()) {
            return request.tameAndLinkEvidence()
                    .targetIdentity().displayName();
        }
        return resolution.targetDisplayName();
    }
}
