package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a recall reaches one companion (spec 8.5). Only LIVE companions are recalled: one in the
 * player's world is moved (loading its chunk first when it is unloaded), and one in another world
 * is restored from its snapshot near the player. Dead, lost, stored and other companions have
 * their own Recover, Revive or release actions, so a recall refuses them.
 */
enum RecallRoute {
    MOVE_LOADED,
    LOAD_AND_MOVE,
    RESTORE,
    REFUSE;

    @Nonnull
    static RecallRoute decide(@Nullable CompanionRecord record, boolean loadedInPlayerWorld,
                              @Nonnull String playerWorld) {
        if (record == null || record.location().kind() != LocationKind.LIVE) {
            return REFUSE;
        }
        if (!playerWorld.equals(record.location().world())) {
            return RESTORE;
        }
        return loadedInPlayerWorld ? MOVE_LOADED : LOAD_AND_MOVE;
    }
}
