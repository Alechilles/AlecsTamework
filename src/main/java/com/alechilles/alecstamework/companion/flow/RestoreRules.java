package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure checks for bringing a companion back from its snapshot (spec 8.5, 8.6). */
public final class RestoreRules {
    public enum Reason { RECALL, RECOVER, REVIVE, RELEASE, SUMMON, COOP_RELEASE }

    public enum Verdict { ALLOWED, NOT_FOUND, NOT_ALLOWED, NO_SNAPSHOT, COOLDOWN, STALE }

    private RestoreRules() {
    }

    @Nonnull
    public static Verdict forRecord(@Nullable CompanionRecord record, @Nonnull Reason reason, long nowMs) {
        return forRecord(record, reason, nowMs, -1L);
    }

    /**
     * Whether the record's location allows this restore. {@code expectedGeneration} is the
     * generation an item or slot entry carries; -1 accepts any. RECALL needs LIVE; RECOVER needs
     * LOST, LIVE (no visible body), ITEM (the item may be gone) or COOP; REVIVE needs DEAD past
     * its wall-clock cooldown; RELEASE needs ITEM; SUMMON needs STORED for ROSTER, TIMED or BONDED
     * past {@code summonCooldownUntilMs}; COOP_RELEASE needs COOP.
     */
    @Nonnull
    public static Verdict forRecord(@Nullable CompanionRecord record, @Nonnull Reason reason, long nowMs,
                                    long expectedGeneration) {
        if (record == null) {
            return Verdict.NOT_FOUND;
        }
        LocationKind kind = record.location().kind();
        Verdict verdict = switch (reason) {
            case RECALL -> kind == LocationKind.LIVE ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
            case RECOVER -> kind == LocationKind.LOST || kind == LocationKind.LIVE || kind == LocationKind.ITEM
                    || kind == LocationKind.COOP ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
            case REVIVE -> kind != LocationKind.DEAD ? Verdict.NOT_ALLOWED
                    : nowMs < record.reviveAvailableAtMs() ? Verdict.COOLDOWN : Verdict.ALLOWED;
            case RELEASE -> kind == LocationKind.ITEM ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
            case SUMMON -> kind != LocationKind.STORED || !summonable(record.location().reason()) ? Verdict.NOT_ALLOWED
                    : nowMs < record.summonCooldownUntilMs() ? Verdict.COOLDOWN : Verdict.ALLOWED;
            case COOP_RELEASE -> kind == LocationKind.COOP ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
        };
        if (verdict == Verdict.ALLOWED && expectedGeneration >= 0 && record.generation() != expectedGeneration) {
            return Verdict.STALE;
        }
        return verdict;
    }

    /** Provisioned companions are activated by their API. */
    private static boolean summonable(@Nullable StoredReason reason) {
        return reason == StoredReason.ROSTER || reason == StoredReason.TIMED || reason == StoredReason.BONDED;
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
