package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink.RecallFailure;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Builds a restorable Lost snapshot from exact durable companion facts.
 *
 * <p>This repair runs only after an explicit Recall exhausts all read-only
 * relocation probes. It retires the missing alias through the dormant
 * operation. Optional live-only state cannot be reconstructed and remains
 * absent, but the stable profile, role, owner, name, tame state, and command
 * links remain intact.</p>
 */
final class MissingUnloadedRecallRecoveryAuthor {
    private static final String SNAPSHOT =
            "missing-unloaded-recall-snapshot:v1";
    private static final String RECEIPT =
            "missing-unloaded-recall-receipt:v1";
    private static final String UNKNOWN_WORLD = "unknown-recall-source";
    private final FullStateSnapshotCodecAdapter codec =
            new FullStateSnapshotCodecAdapter(
                    TameworkSnapshotCodecs.LOST,
                    2
            );

    @Nullable
    CompanionDormantTransitionRequest author(
            CompanionProfileReadModel profile,
            RecallFailure failure
    ) {
        if (!eligible(profile, failure)) {
            return null;
        }
        LegacyRestorationEvidence.Metadata metadata;
        try {
            metadata = LegacyRestorationEvidence.metadata(
                    profile.identity().metadataJson()
            );
        } catch (LegacyRestorationEvidence.EvidenceException invalid) {
            return null;
        }
        if (Boolean.FALSE.equals(metadata.tamed())) {
            return null;
        }
        String[] toolIds = profile.toolLinks().stream()
                .map(CompanionToolLink::toolId)
                .distinct()
                .sorted()
                .map(UUID::toString)
                .toArray(String[]::new);
        CoopResidentStateSnapshot state = state(
                profile,
                failure,
                metadata,
                toolIds
        );
        String payload = codec.encode(state);
        String[] parts = parts(profile, failure, payload);
        CompanionSnapshot snapshot = new CompanionSnapshot(
                new SnapshotId(StablePersistenceIds.operationId(
                        SNAPSHOT, parts
                ).value()),
                profile.identity().profileId(),
                TameworkSnapshotCodecs.LOST,
                2,
                payload,
                Sha256Hash.ofUtf8(payload),
                profile.lifecycle().revision(),
                true,
                failure.failedAtMs()
        );
        return new CompanionDormantTransitionRequest(
                profile.identity().profileId(),
                profile.lifecycle().revision(),
                snapshot,
                new DormantSourceEvidence(
                        profile.currentAlias().alias(),
                        sourceWorld(profile),
                        DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED,
                        profile.lifecycle().lastReconciledGeneration(),
                        StablePersistenceIds.receipt(RECEIPT, parts),
                        failure.failedAtMs()
                ),
                failure.failedAtMs()
        );
    }

    private boolean eligible(
            CompanionProfileReadModel profile,
            RecallFailure failure
    ) {
        if (profile == null || failure == null) {
            return false;
        }
        CompanionAlias alias = profile.currentAlias();
        return alias != null
                && alias.state() == CompanionAlias.State.CURRENT
                && alias.alias().value().equals(failure.npcUuid())
                && profile.identity().roleId() != null
                && profile.lifecycle().state() == LifecycleState.UNLOADED
                && profile.lifecycle().location().equals(
                        LifecycleLocation.none()
                )
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                        failure.ownerUuid()
                )
                && profile.lifecycle().activeOperationId() == null
                && !profile.lifecycle().quarantined()
                && profile.currentSnapshots().stream().noneMatch(snapshot ->
                snapshot.kind().equals(TameworkSnapshotCodecs.LOST));
    }

    private CoopResidentStateSnapshot state(
            CompanionProfileReadModel profile,
            RecallFailure failure,
            LegacyRestorationEvidence.Metadata metadata,
            String[] toolIds
    ) {
        TameworkNpcNameComponent name = metadata.customName() == null
                ? null
                : new TameworkNpcNameComponent(
                        metadata.customName(),
                        failure.ownerUuid(),
                        failure.failedAtMs(),
                        TameworkNpcNameComponent.NameSource.Player
                );
        return new CoopResidentStateSnapshot(
                failure.npcUuid(),
                null,
                -1,
                profile.identity().roleId(),
                new TameworkCommandLinksComponent(
                        failure.ownerUuid(),
                        Arrays.copyOf(toolIds, toolIds.length)
                ),
                new TameworkOwnerComponent(
                        failure.ownerUuid(),
                        metadata.ownerName()
                ),
                new TameworkTamedComponent(true),
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                failure.failedAtMs()
        );
    }

    private String[] parts(
            CompanionProfileReadModel profile,
            RecallFailure failure,
            String payload
    ) {
        return new String[]{
                profile.identity().profileId().toString(),
                failure.npcUuid().toString(),
                failure.ownerUuid().toString(),
                Long.toString(profile.lifecycle().revision().value()),
                Long.toString(profile.identity().metadataRevision()),
                Long.toString(failure.queuedAtMs()),
                Long.toString(failure.failedAtMs()),
                Sha256Hash.ofUtf8(payload).toString()
        };
    }

    private String sourceWorld(CompanionProfileReadModel profile) {
        String identityWorld = normalize(
                profile.identity().lastKnownWorldKey()
        );
        if (identityWorld != null) {
            return identityWorld;
        }
        String ownerWorld = normalize(profile.lifecycle().ownerWorldKey());
        return ownerWorld == null ? UNKNOWN_WORLD : ownerWorld;
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
