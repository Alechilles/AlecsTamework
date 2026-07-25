package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTerminalPlan;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.CompanionSnapshotEvidence;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;

/** Builds one canonical capture request from frozen Hytale-free evidence. */
final class SpawnerCaptureRequestFactory {
    private final SpawnerTameAndLinkEvidenceAuthor tameAndLink =
            new SpawnerTameAndLinkEvidenceAuthor();

    CompanionCaptureRequest create(
            SpawnerCaptureContext context,
            SpawnerCaptureEvidenceFreezer.FrozenCapture frozen,
            CompanionProfileReadModel profile
    ) {
        CompanionLifecycle lifecycle = profile.lifecycle();
        CaptureSourceEvidence source = new CaptureSourceEvidence(
                context.actorUuid(),
                context.worldKey(),
                context.sourceSlot(),
                frozen.source().itemId(),
                frozen.source().quantity(),
                frozen.source().artifactHash(),
                frozen.remainder() == null
                        ? 0
                        : frozen.remainder().quantity(),
                frozen.remainder() == null
                        ? null
                        : frozen.remainder().artifactHash(),
                frozen.resolution().attemptId().toString()
        );
        if (!frozen.resolution().successful()) {
            return new CompanionCaptureRequest(
                    context.profileId(),
                    lifecycle.revision(),
                    null,
                    context.sourceAlias(),
                    context.worldKey(),
                    new CaptureTerminalPlan.FailedAttempt(
                            frozen.resolution()
                    ),
                    source,
                    frozen.requestedAt()
            );
        }
        if (frozen.resolution().successDisposition()
                == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            CaptureTameAndLinkEvidence evidence = tameAndLink.author(
                    new SpawnerTameAndLinkEvidenceInput(
                            frozen.operationId(),
                            frozen.requestedAt(),
                            profile.identity(),
                            lifecycle,
                            frozen.tameAndLinkEvidence()
                    )
            );
            return new CompanionCaptureRequest(
                    context.profileId(),
                    lifecycle.revision(),
                    context.resultingOwnerId(),
                    context.sourceAlias(),
                    context.worldKey(),
                    new CaptureTerminalPlan.TameAndCommandLink(
                            frozen.resolution(), evidence
                    ),
                    source,
                    frozen.requestedAt()
            );
        }
        CompanionSnapshot snapshot = new CompanionSnapshot(
                frozen.snapshotId(),
                context.profileId(),
                CompanionCaptureRequest.SNAPSHOT_KIND,
                frozen.encoded().payloadVersion(),
                frozen.encoded().payloadJson(),
                frozen.encoded().payloadHash(),
                lifecycle.revision().next(),
                true,
                frozen.requestedAt()
        );
        return new CompanionCaptureRequest(
                context.profileId(),
                lifecycle.revision(),
                context.resultingOwnerId(),
                context.sourceAlias(),
                context.worldKey(),
                new CaptureTerminalPlan.CapturedItem(
                        frozen.resolution(),
                        new CompanionSnapshotEvidence(
                                snapshot, frozen.artifact()
                        )
                ),
                source,
                frozen.requestedAt()
        );
    }
}
