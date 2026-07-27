package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.items.BondedCompanionCaptureIntent;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Builds versioned immutable identities for exact bonded capture replay. */
final class BondedCompanionCaptureRequestIdentity {
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    String current(
            BondedCompanionCaptureIntent intent,
            BondedCompanionSnapshot claimed
    ) {
        return digest(canonical(intent, claimed, true));
    }

    boolean matches(
            String stored,
            BondedCompanionCaptureIntent intent,
            BondedCompanionSnapshot claimed
    ) {
        return Objects.equals(stored, current(intent, claimed))
                || Objects.equals(stored,
                digest(canonical(intent, claimed, false)));
    }

    private String canonical(
            BondedCompanionCaptureIntent intent,
            BondedCompanionSnapshot claimed,
            boolean includeWorld
    ) {
        var attempt = intent.attemptEvidence();
        StringBuilder canonical = new StringBuilder()
                .append(intent.actorUuid()).append('\0')
                .append(intent.rosterId()).append('\0')
                .append(intent.roleId()).append('\0')
                .append(intent.sourceNpcUuid()).append('\0');
        if (includeWorld) {
            canonical.append("world:").append(intent.worldKey()).append('\0');
        }
        canonical.append(attempt.attemptId()).append('\0')
                .append(attempt.sourceItemId()).append('\0')
                .append(attempt.spawnerConfigId()).append('\0')
                .append(attempt.spawnerConfigRevision()).append('\0')
                .append(attempt.capturePolicyConfigId()).append('\0')
                .append(attempt.capturePolicyConfigRevision()).append('\0')
                .append(attempt.sourceConsumption()).append('\0')
                .append(attempt.successDisposition()).append('\0')
                .append(attempt.outcome()).append('\0')
                .append(attempt.reason()).append('\0')
                .append(snapshots.encode(stableSnapshot(claimed)));
        if (intent.familySelection()
                == BondedCompanionCaptureIntent.FamilySelection.EXPLICIT) {
            canonical.append("\0family:").append(intent.familyId());
        }
        return canonical.toString();
    }

    private BondedCompanionSnapshot stableSnapshot(
            BondedCompanionSnapshot claimed
    ) {
        CoopResidentStateSnapshot state = claimed.fullState();
        CoopResidentStateSnapshot stable = new CoopResidentStateSnapshot(
                state.npcUuid(), state.coopId(), state.residentSlot(),
                state.roleId(), state.commandLinks(), state.owner(), state.tamed(),
                state.npcName(), state.happiness(), state.needs(), state.breeding(),
                state.leveling(), state.traits(), state.talents(), state.lifeStage(),
                state.attachments(), state.healthPercent(), 0L);
        return BondedCompanionSnapshot.of(stable, claimed.extensionData());
    }

    private String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
