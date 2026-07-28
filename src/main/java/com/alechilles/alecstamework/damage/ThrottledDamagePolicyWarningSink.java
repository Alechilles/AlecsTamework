package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Emits at most one warning per category/provider/context key per minute through the active logger.
 */
final class ThrottledDamagePolicyWarningSink implements DamagePolicyWarningSink {
    private final DamagePolicyWarningThrottle warningThrottle = new DamagePolicyWarningThrottle();

    @Override
    public void warn(@Nonnull String category, @Nonnull String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null || message.isBlank()) {
            return;
        }
        if (!warningThrottle.tryAcquire(category, "simpleclaims", "tamed-damage")) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).log(message);
    }
}
