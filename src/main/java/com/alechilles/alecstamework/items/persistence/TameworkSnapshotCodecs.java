package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import java.util.List;
import javax.annotation.Nonnull;

/** Creates the complete immutable snapshot codec registry used by the replacement runtime. */
public final class TameworkSnapshotCodecs {
    public static final SnapshotKind COOP = new SnapshotKind("coop");
    public static final SnapshotKind DEATH = new SnapshotKind("death");
    public static final SnapshotKind LOST = new SnapshotKind("lost");

    private TameworkSnapshotCodecs() {
    }

    /** Creates exactly the released and modern payload codecs for the three snapshot kinds. */
    @Nonnull
    public static SnapshotCodecRegistry create() {
        return new SnapshotCodecRegistry(List.of(
                new LegacyDeathV1SnapshotCodec(),
                new LegacyLostV1SnapshotCodec(),
                new FullStateSnapshotCodecAdapter(COOP, 1),
                new DeathSnapshotV2Codec(),
                new FullStateSnapshotCodecAdapter(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                ),
                new FullStateSnapshotCodecAdapter(LOST, 2)
        ));
    }
}
