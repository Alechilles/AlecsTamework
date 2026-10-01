package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure checks for bringing a companion back from its snapshot (spec 8.5, 8.6), or, for a
 * provisioned bonded companion's first summon, from its role (plan 6 R16).
 */
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
     * its wall-clock cooldown; RELEASE needs ITEM; SUMMON needs STORED for ROSTER, TIMED or BONDED,
     * or PROVISIONED on a bonded record, past {@code summonCooldownUntilMs}; COOP_RELEASE needs COOP.
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
            case SUMMON -> kind != LocationKind.STORED || !summonable(record) ? Verdict.NOT_ALLOWED
                    : nowMs < record.summonCooldownUntilMs() ? Verdict.COOLDOWN : Verdict.ALLOWED;
            case COOP_RELEASE -> kind == LocationKind.COOP ? Verdict.ALLOWED : Verdict.NOT_ALLOWED;
        };
        if (verdict == Verdict.ALLOWED && expectedGeneration >= 0 && record.generation() != expectedGeneration) {
            return Verdict.STALE;
        }
        return verdict;
    }

    private static boolean summonable(CompanionRecord record) {
        StoredReason reason = record.location().reason();
        return reason == StoredReason.ROSTER || reason == StoredReason.TIMED || reason == StoredReason.BONDED
                || reason == StoredReason.PROVISIONED && record.bonded();
    }

    /**
     * Whether this restore may build the body from the record's role when no snapshot was ever
     * written: a SUMMON or RECOVER of a bonded companion that has an origin, that is, one that
     * was provisioned (plan 6 R16). That is its first summon, and also a summon after a stop
     * between the first summon's commit and its first snapshot, which left the record with no
     * body and no snapshot.
     */
    public static boolean respawnsFromRole(@Nonnull CompanionRecord record, @Nonnull Reason reason) {
        return record.bonded() && record.originNamespace() != null
                && (reason == Reason.SUMMON || reason == Reason.RECOVER);
    }

    /**
     * Whether this snapshot may restore the record: it must exist, use {@link CompanionSnapshots#FORMAT},
     * be no newer than the record, and hold an entity document. A snapshot taken at death serves
     * only a revive.
     */
    @Nonnull
    public static Verdict forSnapshot(@Nonnull CompanionRecord record, @Nullable SnapshotEnvelope snapshot, @Nonnull Reason reason) {
        return forSnapshot(record, snapshot, reason, false);
    }

    /**
     * As {@link #forSnapshot(CompanionRecord, SnapshotEnvelope, Reason)}. {@code neverWritten}
     * says that the missing snapshot is known to be absent: no file, nothing queued and no loaded
     * body to capture. Then, and only then, a {@link #respawnsFromRole} restore is allowed with no
     * snapshot and the spawner is handed null. A snapshot that exists but could not be read is
     * never treated as absent, because a body built from the role would lose its progression.
     */
    @Nonnull
    public static Verdict forSnapshot(@Nonnull CompanionRecord record, @Nullable SnapshotEnvelope snapshot,
                                      @Nonnull Reason reason, boolean neverWritten) {
        if (snapshot == null && neverWritten && respawnsFromRole(record, reason)) {
            return Verdict.ALLOWED;
        }
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
