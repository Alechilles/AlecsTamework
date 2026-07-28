package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.PublicImportRecoveryProjection;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink.RecallFailure;
import java.util.List;
import java.util.UUID;

/**
 * Authors one exact recovery mutation from initial public-import evidence.
 */
final class ImportedRecallRecoveryAuthor {
    private final FullStateSnapshotCodecAdapter codec =
            new FullStateSnapshotCodecAdapter(
                    PublicImportRecoveryProjection.KIND,
                    PublicImportRecoveryProjection.VERSION
            );

    CompanionProfileMutation.RecoverImportedMissing author(
            CompanionProfileReadModel profile,
            RecallFailure failure
    ) {
        if (!matchesUnloadedProfile(profile, failure)) {
            return null;
        }
        List<CompanionSnapshot> candidates =
                profile.currentSnapshots().stream()
                        .filter(snapshot -> snapshot.kind().equals(
                                PublicImportRecoveryProjection.KIND
                        ))
                        .toList();
        if (candidates.size() != 1) {
            return null;
        }
        CompanionSnapshot source = candidates.getFirst();
        if (!matchesInitialImportLineage(profile, source)) {
            return null;
        }
        CoopResidentStateSnapshot state = codec.decode(source.payloadJson());
        if (!matchesRestorableEvidence(profile, state)) {
            return null;
        }
        return new CompanionProfileMutation.RecoverImportedMissing(
                profile.identity().profileId(),
                profile.lifecycle().revision(),
                profile.identity().metadataRevision(),
                profile.currentAlias().alias(),
                new OwnerId(failure.ownerUuid()),
                source.snapshotId(),
                source.payloadHash(),
                failure.queuedAtMs(),
                failure.failedAtMs()
        );
    }

    private boolean matchesUnloadedProfile(
            CompanionProfileReadModel profile,
            RecallFailure failure
    ) {
        return profile.currentAlias() != null
                && profile.currentAlias().alias().value().equals(
                failure.npcUuid()
        )
                && profile.lifecycle().state() == LifecycleState.UNLOADED
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                failure.ownerUuid()
        );
    }

    private boolean matchesInitialImportLineage(
            CompanionProfileReadModel profile,
            CompanionSnapshot source
    ) {
        return source.payloadVersion()
                == PublicImportRecoveryProjection.VERSION
                && source.payloadHash().matchesUtf8(source.payloadJson())
                && source.sourceLifecycleRevision().next().equals(
                profile.lifecycle().revision()
        )
                && profile.lifecycle().lastReconciledGeneration().equals(
                ReconciliationGeneration.INITIAL.next()
        );
    }

    private boolean matchesRestorableEvidence(
            CompanionProfileReadModel profile,
            CoopResidentStateSnapshot state
    ) {
        if (state.npcUuid() == null
                || state.roleId() == null
                || profile.identity().roleId() == null
                || profile.currentAlias() == null
                || !state.npcUuid().equals(
                profile.currentAlias().alias().value()
        )
                || !state.roleId().equalsIgnoreCase(
                profile.identity().roleId()
        )) {
            return false;
        }
        UUID owner = profile.lifecycle().ownerId().value();
        UUID stateOwner = state.owner() == null
                ? null
                : state.owner().getOwnerId();
        UUID linksOwner = state.commandLinks() == null
                ? null
                : state.commandLinks().getOwnerId();
        return owner.equals(stateOwner) && owner.equals(linksOwner);
    }
}
