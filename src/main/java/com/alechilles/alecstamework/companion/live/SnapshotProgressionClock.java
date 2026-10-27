package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt64;

/**
 * Keeps a snapshotted companion's growth paused while it is out of a world (in a capture item,
 * stored, dead or lost), on any server. A body's progression reads elapsed time as its owner's
 * progression clock minus {@code TameworkLifeStage.ProgressionClockMs}. The snapshot records that
 * clock when it is taken ({@code OwnerClockMs}); the restore moves {@code ProgressionClockMs} by the
 * destination clock minus that reading, so the body resumes with exactly the time it had pending.
 * Owner clocks are per server, so without this a body restored on another server would jump
 * ahead or freeze. Pure edits on BSON; every method leaves its input unchanged.
 */
public final class SnapshotProgressionClock {
    /** The snapshot data field holding the owner clock reading. */
    public static final String OWNER_CLOCK_MS = "OwnerClockMs";
    private static final String COMPONENTS = "Components";
    private static final String LIFE_STAGE = "TameworkLifeStage";
    private static final String INITIALIZED = "ProgressionInitialized";
    private static final String PAUSED = "StoredProgressionPaused";
    private static final String OWNER = "ProgressionOwnerId";
    private static final String CLOCK = "ProgressionClockMs";

    /** Reads an owner's progression clock; a null owner is the server runtime clock. */
    @FunctionalInterface
    public interface OwnerClock {
        long current(@Nullable UUID owner);
    }

    /** What a snapshot needs to know about a body's progression clock. */
    public record Running(@Nullable UUID owner) {
    }

    private SnapshotProgressionClock() {
    }

    /**
     * The progression owner of a body whose progression is running (initialized and not paused), or
     * null when nothing runs: then there is no pending time to keep. An owner id that is not a UUID
     * reads as not running, as the progression service reads it.
     */
    @Nullable
    public static Running running(@Nonnull BsonDocument entity) {
        BsonDocument lifeStage = runningLifeStage(entity);
        if (lifeStage == null) {
            return null;
        }
        if (!lifeStage.isString(OWNER)) {
            return new Running(null);
        }
        try {
            return new Running(UUID.fromString(lifeStage.getString(OWNER).getValue()));
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** The snapshot data with {@link #OWNER_CLOCK_MS} added when the body's progression runs. */
    @Nonnull
    public static BsonDocument withReading(@Nonnull BsonDocument data, @Nonnull BsonDocument entity,
                                           @Nonnull OwnerClock clock) {
        Running running = running(entity);
        if (running != null) {
            data.put(OWNER_CLOCK_MS, new BsonInt64(clock.current(running.owner())));
        }
        return data;
    }

    /**
     * The entity to restore from snapshot data that holds {@code entity}: its progression clock moved
     * by the destination clock minus the snapshot's reading. Unchanged when the snapshot has no
     * reading (taken before this existed, or progression was not running).
     */
    @Nonnull
    public static BsonDocument rebase(@Nonnull BsonDocument entity, @Nonnull BsonDocument snapshotData,
                                      @Nonnull OwnerClock clock) {
        BsonDocument copy = entity.clone();
        Running running = running(copy);
        BsonDocument lifeStage = runningLifeStage(copy);
        if (running == null || lifeStage == null || !lifeStage.isNumber(CLOCK)
                || !snapshotData.isNumber(OWNER_CLOCK_MS)) {
            return copy;
        }
        long delta = clock.current(running.owner()) - snapshotData.getNumber(OWNER_CLOCK_MS).longValue();
        if (delta != 0L) {
            long clockMs = lifeStage.getNumber(CLOCK).longValue();
            lifeStage.put(CLOCK, new BsonInt64(BreedingTimeService.saturatingAdd(clockMs, delta)));
        }
        return copy;
    }

    @Nullable
    private static BsonDocument runningLifeStage(BsonDocument entity) {
        if (!entity.isDocument(COMPONENTS) || !entity.getDocument(COMPONENTS).isDocument(LIFE_STAGE)) {
            return null;
        }
        BsonDocument lifeStage = entity.getDocument(COMPONENTS).getDocument(LIFE_STAGE);
        boolean initialized = lifeStage.isBoolean(INITIALIZED) && lifeStage.getBoolean(INITIALIZED).getValue();
        boolean paused = lifeStage.isBoolean(PAUSED) && lifeStage.getBoolean(PAUSED).getValue();
        return initialized && !paused ? lifeStage : null;
    }
}
