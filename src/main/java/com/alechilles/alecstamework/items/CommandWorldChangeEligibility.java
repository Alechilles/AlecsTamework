package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;

/**
 * Decides whether persisted command state is strong enough to authorize automatic world travel.
 */
final class CommandWorldChangeEligibility {
    private CommandWorldChangeEligibility() {
    }

    static boolean isEligible(LinkedNpcRecord record,
                              TwCompanionConfig.EffectiveSettings settings) {
        if (record == null || settings == null) {
            return false;
        }
        String[] requiredStates = settings.getFollowMasterOnWorldChangeStateFilter();
        if (requiredStates == null || requiredStates.length == 0) {
            return true;
        }
        if (record.cachedCommandState == null || record.cachedCommandState.isBlank()) {
            // Old records cannot prove that the companion was following. Do not turn login into recall.
            return false;
        }
        return settings.isWorldChangeStateAllowed(record.cachedCommandState);
    }
}
