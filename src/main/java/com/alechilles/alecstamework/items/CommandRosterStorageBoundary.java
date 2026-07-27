package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import javax.annotation.Nullable;

/**
 * Defines the storage boundary between legacy linked-record command items and
 * server-authoritative bonded companion rosters.
 */
final class CommandRosterStorageBoundary {
    private CommandRosterStorageBoundary() {
    }

    static boolean allowsGenericRosterActions(
            @Nullable TwCommandItemConfig config
    ) {
        return config != null
                && config.isEnabled()
                && !config.usesBondedCompanionRoster();
    }
}
