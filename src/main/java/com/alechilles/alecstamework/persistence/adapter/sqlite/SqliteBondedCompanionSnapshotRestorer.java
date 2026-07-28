package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restores a persisted bonded snapshot to its summon-safe post-revive form. */
final class SqliteBondedCompanionSnapshotRestorer {
    private final SqliteBondedCompanionMapper mapper =
            new SqliteBondedCompanionMapper();
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    @Nullable
    String restore(@Nonnull String snapshotJson) {
        try {
            BondedCompanionPayload payload = mapper.payload(snapshotJson);
            BondedCompanionSnapshotCodec.DecodeResult decoded = snapshots.decode(
                    new String(payload.bytes(), StandardCharsets.UTF_8));
            BondedCompanionSnapshot snapshot = decoded.snapshot();
            if (decoded.status() != BondedCompanionSnapshotCodec.Status.FOUND
                    || snapshot == null) {
                return null;
            }
            return mapper.payloadJson(BondedCompanionPayload.of(
                    snapshots.encode(snapshot.restoredAfterRevive())
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
