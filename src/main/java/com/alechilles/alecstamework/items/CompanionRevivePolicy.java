package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import javax.annotation.Nullable;

/** Resolves the one effective revive policy shared by death, drops, and command UI. */
public final class CompanionRevivePolicy {
    private CompanionRevivePolicy() {
    }

    public static boolean featureEnabled(@Nullable String roleId) {
        boolean roleEnabled = TwCompanionConfig.resolveEffectiveForRole(roleId)
                .isDeadRespawnEnabled();
        return TameworkRuntimeSettings.reviveSystemEnabled(roleEnabled);
    }

    public static boolean supportsRevive(@Nullable String roleId,
                                         @Nullable TameworkCommandLinksComponent links) {
        return supportsRevive(links, featureEnabled(roleId));
    }

    static boolean supportsRevive(@Nullable TameworkCommandLinksComponent links,
                                  boolean featureEnabled) {
        return featureEnabled && links != null
                && links.getToolIds() != null
                && links.getToolIds().length > 0;
    }
}
