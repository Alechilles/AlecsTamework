package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure checks for bringing a companion back from its snapshot (spec 8.5, 8.6). */
public final class RestoreRules {
    public enum Reason { RECALL, RECOVER, REVIVE }

    public enum Verdict { ALLOWED, NOT_FOUND, NOT_ALLOWED, NO_SNAPSHOT, COOLDOWN }

    private RestoreRules() {
    }

    /**
     * Whether the record's location allows this restore. RECALL needs LIVE; RECOVER needs LOST or
     * LIVE (a LIVE record with no visible body is offered Recover too); REVIVE needs DEAD and a
     * wall-clock {@code nowMs} at or after {@code reviveAvailableAtMs}.
     */
    @Nonnull
    public static Verdict forRecord(@Nullable CompanionRecord record, @Nonnull Reason reason, long nowMs) {
        if (record == null) {
            return Verdict.NOT_FOUND;
        }
        LocationKind kind = record.location().kind();
        return switch (reason) {
            case RECALL -> kind == LocationKind.LIVE ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
            case RECOVER -> kind == LocationKind.LOST || kind == LocationKind.LIVE ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
            case REVIVE -> kind != LocationKind.DEAD ? Verdict.NOT_ALLOWED
                    : nowMs < record.reviveAvailableAtMs() ? Verdict.COOLDOWN : Verdict.ALLOWED;
        };
    }

    /**
     * Whether this snapshot may restore the record: it must exist, use {@link CompanionSnapshots#FORMAT},
     * be no newer than the record, and hold an entity document. A snapshot taken at death serves
     * only a revive.
     */
    @Nonnull
    public static Verdict forSnapshot(@Nonnull CompanionRecord record, @Nullable SnapshotEnvelope snapshot, @Nonnull Reason reason) {
        if (snapshot == null || snapshot.format() != CompanionSnapshots.FORMAT || snapshot.generation() > record.generation()
                || !snapshot.data().isDocument("Entity")) {
            return Verdict.NO_SNAPSHOT;
        }
        if (reason != Reason.REVIVE && SnapshotPatch.isDeathSnapshot(CompanionSnapshots.entity(snapshot))) {
            return Verdict.NOT_ALLOWED;
        }
        return Verdict.ALLOWED;
    }
}
