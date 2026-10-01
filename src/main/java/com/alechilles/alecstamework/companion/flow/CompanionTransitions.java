package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure record transitions for the companion lifecycle (spec 6.3, 8.1, 8.6 to 8.8, 8.12).
 * Every change of holder raises the generation, so a body stamped with the old generation is
 * removed by the fence when it next loads. Wall-clock times (snapshot, death, revive) follow
 * the old death path's clock.
 */
public final class CompanionTransitions {
    public static final long SNAPSHOT_ON_UNLOAD_AFTER_MS = 5L * 60_000L;
    public static final String CAUSE_REMOVED = "REMOVED";
    public static final String CAUSE_WORLD_REMOVED = "WORLD_REMOVED";
    public static final String CAUSE_ITEM_DESTROYED = "ITEM_DESTROYED";
    public static final String CAUSE_FORGOTTEN = "FORGOTTEN";
    public static final String CAUSE_RELEASED_UNOWNED = "RELEASED_UNOWNED";
    /** Position changes below this many blocks are not worth a record write. */
    private static final double MOVE_THRESHOLD = 2.0;

    /** The role recorded when none is known, for example a body tamed while parked in Empty_Role. */
    public static final String UNKNOWN_ROLE = "unknown";

    /**
     * What a loaded body tells the index, read on its world thread. {@code roleId} is null while
     * the body is parked (mounted or in avatar flight) with no known original role; the record
     * then keeps the role it has.
     */
    public record BodyFacts(@Nonnull UUID npcUuid, @Nullable UUID ownerUuid, @Nullable String ownerName,
                            @Nullable String roleId, @Nullable String displayName, @Nonnull String world,
                            double x, double y, double z, @Nonnull List<String> toolIds,
                            @Nonnull CompanionSummary summary) {
        public BodyFacts {
            Objects.requireNonNull(npcUuid, "npcUuid");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(summary, "summary");
            toolIds = List.copyOf(toolIds);
        }
    }

    private CompanionTransitions() {
    }

    @Nonnull
    public static CompanionRecord newLive(@Nonnull UUID profileId, long generation, @Nonnull BodyFacts body) {
        return CompanionRecord.builder(profileId, body.roleId() == null ? UNKNOWN_ROLE : body.roleId(), live(body))
                .generation(generation)
                .ownerUuid(body.ownerUuid())
                .ownerName(body.ownerName())
                .displayName(body.displayName())
                .homeWorld(body.world())
                .currentNpcUuid(body.npcUuid())
                .summary(body.summary())
                .toolIds(body.toolIds())
                .build();
    }

    /** True when the record no longer describes where and which this body is. */
    public static boolean needsRefresh(@Nonnull CompanionRecord record, @Nonnull BodyFacts body) {
        CompanionLocation at = record.location();
        return at.kind() != LocationKind.LIVE
                || !body.world().equals(at.world())
                || !body.npcUuid().equals(record.currentNpcUuid())
                || body.roleId() != null && !body.roleId().equals(record.roleId())
                || !Objects.equals(body.displayName(), record.displayName())
                || !sameTools(record.toolIds(), body.toolIds())
                || Math.abs(at.x() - body.x()) > MOVE_THRESHOLD
                || Math.abs(at.y() - body.y()) > MOVE_THRESHOLD
                || Math.abs(at.z() - body.z()) > MOVE_THRESHOLD;
    }

    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> seenAt(@Nonnull BodyFacts body) {
        // The body is the authority for its role, name and tool links (they change on growth,
        // rename and linking). An unknown role (parked body) keeps the record's role.
        return b -> {
            b.location(live(body)).currentNpcUuid(body.npcUuid())
                    .displayName(body.displayName()).toolIds(body.toolIds());
            if (body.roleId() != null) {
                b.roleId(body.roleId());
            }
            return b;
        };
    }

    /** A body newer than its record wins (spec 6.8): the record moves to LIVE at its generation. */
    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> raisedTo(long generation, @Nonnull BodyFacts body) {
        return b -> seenAt(body).apply(b).generation(generation).summary(body.summary())
                .diedAtMs(0L).reviveAvailableAtMs(0L);
    }

    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> unloaded(@Nonnull BodyFacts body, @Nullable Long snapshotAtMs) {
        return b -> {
            seenAt(body).apply(b).summary(body.summary());
            if (snapshotAtMs != null) {
                b.lastSnapshotAtMs(snapshotAtMs);
            }
            return b;
        };
    }

    /** Spec 6.5: on UNLOAD, snapshot only when none exists or the last one is 5 minutes old. */
    public static boolean snapshotDue(@Nonnull CompanionRecord record, long nowMs) {
        return record.lastSnapshotAtMs() == 0L || nowMs - record.lastSnapshotAtMs() >= SNAPSHOT_ON_UNLOAD_AFTER_MS;
    }

    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> died(@Nonnull CompanionRecord before, @Nonnull CompanionSummary summary,
                                                             long diedAtMs, long reviveAvailableAtMs,
                                                             @Nullable String cause, @Nullable Long snapshotAtMs) {
        long generation = before.generation() + 1;
        return b -> {
            b.generation(generation)
                    .location(CompanionLocation.dead(cause))
                    .currentNpcUuid(null)
                    .summary(summary)
                    .diedAtMs(diedAtMs)
                    .reviveAvailableAtMs(reviveAvailableAtMs);
            if (snapshotAtMs != null) {
                b.lastSnapshotAtMs(snapshotAtMs);
            }
            return b;
        };
    }

    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> lost(@Nonnull CompanionRecord before, @Nullable CompanionSummary summary,
                                                             @Nonnull String cause, @Nullable Long snapshotAtMs) {
        long generation = before.generation() + 1;
        return b -> {
            b.generation(generation).location(CompanionLocation.lost(cause)).currentNpcUuid(null);
            if (summary != null) {
                b.summary(summary);
            }
            if (snapshotAtMs != null) {
                b.lastSnapshotAtMs(snapshotAtMs);
            }
            return b;
        };
    }

    /** A restored body at a new place: LIVE, one generation newer, new NPC UUID, death timers cleared. */
    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> restored(@Nonnull CompanionRecord before, @Nonnull String world,
                                                                 double x, double y, double z, @Nonnull UUID newNpcUuid) {
        long generation = before.generation() + 1;
        return b -> b.generation(generation)
                .location(CompanionLocation.live(world, x, y, z))
                .currentNpcUuid(newNpcUuid)
                .diedAtMs(0L)
                .reviveAvailableAtMs(0L)
                .summonedUntilMs(0L);
    }

    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> released(@Nonnull CompanionRecord before) {
        return released(before, null);
    }

    /** Spec 8.12, 8.14: a tombstone that keeps old bodies from being adopted, with an optional cause. */
    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> released(@Nonnull CompanionRecord before, @Nullable String cause) {
        long generation = before.generation() + 1;
        return b -> b.generation(generation)
                .location(CompanionLocation.released(cause))
                .currentNpcUuid(null)
                .extensions(Map.of())
                .domainClaims(List.of())
                .toolIds(List.of())
                .summonedUntilMs(0L);
    }

    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> ownerChanged(@Nullable UUID owner, @Nullable String ownerName) {
        return b -> b.ownerUuid(owner).ownerName(ownerName);
    }

    /** The command links of a live body changed; the body is the authority for them. */
    @Nonnull
    public static UnaryOperator<CompanionRecord.Builder> toolsChanged(@Nonnull List<String> toolIds) {
        return b -> b.toolIds(toolIds);
    }

    /** Tool links compare as sets: their order carries no meaning. */
    public static boolean sameTools(@Nonnull List<String> a, @Nonnull List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }

    private static CompanionLocation live(BodyFacts body) {
        return CompanionLocation.live(body.world(), body.x(), body.y(), body.z());
    }
}
