package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies canonical and source-location fences to full-state Recall. */
public final class ExactCheckpointRecallRecoveryAuthor {
    private static final int CHUNK_SIZE = 32;

    /** Returns a safe recovery plan or null when any evidence is stale. */
    @Nullable
    public RecoveryPlan author(
            @Nullable CompanionProfileReadModel profile,
            @Nullable CompanionEntityCheckpoint checkpoint,
            @Nullable ImportedRecallRecoverySink.RecallFailure failure
    ) {
        if (profile == null || checkpoint == null || failure == null
                || failure.destination() == null) {
            return null;
        }
        CompanionAlias alias = profile.currentAlias();
        if (!eligible(profile, alias, checkpoint, failure)) {
            return null;
        }
        ImportedRecallRecoverySink.RecallSourceSection source =
                sourceSection(checkpoint);
        return new RecoveryPlan(
                profile,
                checkpoint,
                failure.destination(),
                source,
                failure.completedSourceSections().contains(source)
        );
    }

    private static boolean eligible(
            CompanionProfileReadModel profile,
            CompanionAlias alias,
            CompanionEntityCheckpoint checkpoint,
            ImportedRecallRecoverySink.RecallFailure failure
    ) {
        LifecycleState state = profile.lifecycle().state();
        return alias != null
                && alias.state() == CompanionAlias.State.CURRENT
                && alias.alias().value().equals(failure.npcUuid())
                && alias.profileId().equals(checkpoint.profileId())
                && alias.alias().equals(checkpoint.alias())
                && alias.generation() == checkpoint.aliasGeneration()
                && profile.identity().profileId().equals(
                        checkpoint.profileId()
                )
                && profile.lifecycle().profileId().equals(
                        checkpoint.profileId()
                )
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                        failure.ownerUuid()
                )
                && profile.lifecycle().ownerId().equals(
                        checkpoint.ownerId()
                )
                && profile.lifecycle().revision().equals(
                        checkpoint.lifecycleRevision()
                )
                && profile.lifecycle().lastReconciledGeneration().equals(
                        checkpoint.reconciliationGeneration()
                )
                && profile.lifecycle().activeOperationId() == null
                && profile.lifecycle().quarantineIncidentId() == null
                && (state == LifecycleState.ACTIVE
                        || state == LifecycleState.UNLOADED)
                && activeLocationMatches(profile, alias)
                && checkpoint.boundary()
                        != CompanionEntityCheckpoint.CaptureBoundary.LOADED
                && sourceIdentityMatchesBoundary(checkpoint)
                && checkpoint.capturedAtMs() <= failure.failedAtMs();
    }

    private static boolean sourceIdentityMatchesBoundary(
            CompanionEntityCheckpoint checkpoint
    ) {
        boolean returned = checkpoint.boundary()
                == CompanionEntityCheckpoint.CaptureBoundary
                .RETURNED_RETIRED_ORIGINAL;
        return returned
                ? !checkpoint.alias().equals(checkpoint.sourceAlias())
                : checkpoint.alias().equals(checkpoint.sourceAlias());
    }

    private static boolean activeLocationMatches(
            CompanionProfileReadModel profile,
            CompanionAlias alias
    ) {
        if (profile.lifecycle().state() != LifecycleState.ACTIVE) {
            return true;
        }
        return alias.alias().toString().equals(
                profile.lifecycle().location().key()
        );
    }

    @Nonnull
    private static ImportedRecallRecoverySink.RecallSourceSection
    sourceSection(CompanionEntityCheckpoint checkpoint) {
        return new ImportedRecallRecoverySink.RecallSourceSection(
                checkpoint.worldKey(),
                toChunk(checkpoint.x()),
                toChunk(checkpoint.y()),
                toChunk(checkpoint.z())
        );
    }

    private static int toChunk(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CHUNK_SIZE);
    }

    /** Frozen inputs for a source-probed full-holder restoration. */
    public record RecoveryPlan(
            @Nonnull CompanionProfileReadModel profile,
            @Nonnull CompanionEntityCheckpoint checkpoint,
            @Nonnull ImportedRecallRecoverySink.RecallDestination destination,
            @Nonnull ImportedRecallRecoverySink.RecallSourceSection
                    sourceSection,
            boolean sourceAlreadyProbed
    ) {
        public RecoveryPlan {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(sourceSection, "sourceSection");
        }
    }
}
