package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import java.util.List;
import javax.annotation.Nonnull;

/** Creates the complete immutable snapshot codec registry used by the replacement runtime. */
public final class TameworkSnapshotCodecs {
    public static final SnapshotKind COOP = new SnapshotKind("coop");
    public static final SnapshotKind DEATH = new SnapshotKind("death");
    public static final SnapshotKind LOST = new SnapshotKind("lost");

    private TameworkSnapshotCodecs() {
    }

    /**
     * Creates exactly the two released payload codecs and three complete-state codecs.
     */
    @Nonnull
    public static SnapshotCodecRegistry create() {
        return new SnapshotCodecRegistry(List.of(
                new LegacyDeathV1SnapshotCodec(),
                new LegacyLostV1SnapshotCodec(),
                new FullStateSnapshotCodecAdapter(COOP, 1),
                new FullStateSnapshotCodecAdapter(DEATH, 2),
                new FullStateSnapshotCodecAdapter(LOST, 2)
        ));
    }
}
