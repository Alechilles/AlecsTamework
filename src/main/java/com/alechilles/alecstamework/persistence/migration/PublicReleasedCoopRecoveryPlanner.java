package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.companion.snapshot.PublicImportRecoveryProjection;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.FullStateSnapshotCodecAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.deterministicId;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.sha256;
import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.validJsonObject;

/**
 * Preserves one exact released-coop payload as source-neutral migration recovery evidence.
 *
 * <p>The projection is not lifecycle authority. It remains dormant unless the player explicitly
 * recalls the matching imported companion and the normal relocation search is exhausted.</p>
 */
final class PublicReleasedCoopRecoveryPlanner {
    private final FullStateSnapshotCodecAdapter codec =
            new FullStateSnapshotCodecAdapter(
                    PublicImportRecoveryProjection.KIND,
                    PublicImportRecoveryProjection.VERSION
            );

    void addRecoveryProjections(
            @Nonnull LegacyPublicData source,
            @Nonnull Map<String, PublicImportPlanningModel.ProfileDraft> profiles,
            @Nonnull Map<String, LegacyPublicData.ProfileState> states,
            @Nonnull List<PublicImportPlanningModel.SnapshotDraft> snapshots,
            @Nonnull LegacySourceFingerprint fingerprint
    ) throws Exception {
        for (PublicImportPlanningModel.ProfileDraft profile : profiles.values()) {
            LegacyPublicData.ProfileState state =
                    states.get(profile.source().profileId());
            if (!isUnresolvedLiveState(state)
                    || profile.source().currentNpcUuid() == null
                    || profile.hasConflicts()) {
                continue;
            }
            List<LegacyPublicData.CoopSlot> candidates =
                    releasedCandidates(source, profile);
            if (candidates.size() != 1) {
                continue;
            }
            LegacyPublicData.CoopSlot candidate = candidates.getFirst();
            String payload = recoveryPayload(source, profile, candidate);
            if (payload == null) {
                continue;
            }
            snapshots.add(new PublicImportPlanningModel.SnapshotDraft(
                    deterministicId(
                            fingerprint.snapshotSha256(),
                            "released-coop-recovery:" + candidate.coopKey()
                    ),
                    profile.source().profileId(),
                    PublicImportRecoveryProjection.KIND.toString(),
                    PublicImportRecoveryProjection.VERSION,
                    payload,
                    sha256(payload),
                    true,
                    candidate.updatedAtMs()
            ));
        }
    }

    private List<LegacyPublicData.CoopSlot> releasedCandidates(
            LegacyPublicData source,
            PublicImportPlanningModel.ProfileDraft profile
    ) {
        ArrayList<LegacyPublicData.CoopSlot> candidates = new ArrayList<>();
        for (LegacyPublicData.CoopSlot slot : source.coopSlots()) {
            if (profile.source().profileId().equals(slot.profileId())
                    && slot.housedNpcUuid() == null
                    && profile.source().currentNpcUuid().equals(
                    slot.lastReleasedNpcUuid()
            )
                    && slot.stateSnapshotJson() != null) {
                candidates.add(slot);
            }
        }
        return List.copyOf(candidates);
    }

    private String recoveryPayload(
            LegacyPublicData source,
            PublicImportPlanningModel.ProfileDraft profile,
            LegacyPublicData.CoopSlot slot
    ) {
        try {
            if (!validJsonObject(slot.stateSnapshotJson())) {
                return null;
            }
            CoopResidentStateSnapshot state =
                    codec.decode(slot.stateSnapshotJson());
            if (state.npcUuid() == null
                    || state.roleId() == null
                    || state.roleId().isBlank()
                    || !state.roleId().trim().equalsIgnoreCase(
                    profile.source().roleId()
            )) {
                return null;
            }
            String sourceAlias = state.npcUuid().toString();
            boolean sameProfileAlias = source.aliases().stream().anyMatch(
                    alias -> alias.profileId().equals(
                            profile.source().profileId()
                    ) && alias.npcUuid().equals(sourceAlias)
            );
            if (!sameProfileAlias) {
                return null;
            }
            UUID owner = profile.source().ownerUuid() == null
                    ? null
                    : UUID.fromString(profile.source().ownerUuid());
            UUID stateOwner = state.owner() == null
                    ? null
                    : state.owner().getOwnerId();
            UUID linksOwner = state.commandLinks() == null
                    ? null
                    : state.commandLinks().getOwnerId();
            if (owner == null
                    || !Objects.equals(owner, stateOwner)
                    || !Objects.equals(owner, linksOwner)) {
                return null;
            }
            return codec.encode(withNpcUuid(
                    state,
                    UUID.fromString(profile.source().currentNpcUuid())
            ));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CoopResidentStateSnapshot withNpcUuid(
            CoopResidentStateSnapshot source,
            UUID npcUuid
    ) {
        return new CoopResidentStateSnapshot(
                npcUuid,
                source.coopId(),
                source.residentSlot(),
                source.roleId(),
                source.commandLinks(),
                source.owner(),
                source.tamed(),
                source.npcName(),
                source.happiness(),
                source.needs(),
                source.breeding(),
                source.leveling(),
                source.traits(),
                source.talents(),
                source.lifeStage(),
                source.attachments(),
                source.healthPercent(),
                source.capturedAtMs()
        );
    }

    private boolean isUnresolvedLiveState(
            LegacyPublicData.ProfileState state
    ) {
        return state != null
                && state.captureActive() == 0
                && state.deathActive() == 0
                && state.lostActive() == 0
                && state.inCoop() == 0;
    }
}
