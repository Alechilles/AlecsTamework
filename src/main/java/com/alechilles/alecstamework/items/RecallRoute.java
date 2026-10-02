package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a recall reaches one companion (spec 8.5). Only LIVE companions are recalled: one in the
 * player's world is moved (loading its chunk first when it is unloaded), and one in another world
 * is restored from its snapshot near the player. Dead, lost, stored and other companions have
 * their own Recover, Revive or release actions, so a recall refuses them.
 *
 * <p>A companion imported from 3.x or 4.x whose body has not been seen since
 * ({@link LegacyBodyResolution#neverSighted}) is not recalled while its body is unloaded: its world
 * may be a guess and it has no position to load, and a restore would replace the real animal with
 * one built from an empty or older state. The player is told to visit it first. Recover from the
 * companion panel stays available.
 */
enum RecallRoute {
    MOVE_LOADED,
    LOAD_AND_MOVE,
    RESTORE,
    /** Refused with a message: an import whose body has not been seen since the update. */
    UNSEEN_IMPORT,
    REFUSE;

    @Nonnull
    static RecallRoute decide(@Nullable CompanionRecord record, boolean loadedInPlayerWorld,
                              @Nonnull String playerWorld) {
        if (record == null || record.location().kind() != LocationKind.LIVE) {
            return REFUSE;
        }
        if (!loadedInPlayerWorld && LegacyBodyResolution.neverSighted(record)) {
            return UNSEEN_IMPORT;
        }
        if (!playerWorld.equals(record.location().world())) {
            return RESTORE;
        }
        return loadedInPlayerWorld ? MOVE_LOADED : LOAD_AND_MOVE;
    }
}
