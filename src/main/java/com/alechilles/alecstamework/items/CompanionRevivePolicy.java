package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import javax.annotation.Nullable;

/** Resolves the one effective revive policy shared by death, drops, and command UI. */
public final class CompanionRevivePolicy {
    public static final String OLD_AGE_SOURCE = "tamework.old_age";

    private CompanionRevivePolicy() {
    }

    /** Identifies the actual fatal event, independent of the current revive settings. */
    public static boolean isOldAgeDeath(@Nullable DeathComponent death) {
        var damage = death == null ? null : death.getDeathInfo();
        return damage != null
                && damage.getSource() instanceof Damage.EnvironmentSource source
                && OLD_AGE_SOURCE.equals(source.getType());
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
