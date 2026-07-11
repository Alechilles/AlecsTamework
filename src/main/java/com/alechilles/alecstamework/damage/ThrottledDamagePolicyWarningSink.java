package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Emits at most one warning per category per minute through the active Tamework logger.
 */
final class ThrottledDamagePolicyWarningSink implements DamagePolicyWarningSink {
    private static final long WARNING_THROTTLE_MS = 60_000L;

    private final Map<String, AtomicLong> nextWarningByCategory = new ConcurrentHashMap<>();

    @Override
    public void warn(@Nonnull String category, @Nonnull String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null || message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        AtomicLong nextWarning = nextWarningByCategory.computeIfAbsent(category, ignored -> new AtomicLong());
        long next = nextWarning.get();
        if (now < next || !nextWarning.compareAndSet(next, now + WARNING_THROTTLE_MS)) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).log(message);
    }
}
