package com.alechilles.alecstamework.debug;

import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Toggleable debug logger that validates companion XP events through the public API event bus.
 */
public final class CompanionXpEventDebugLogService implements AutoCloseable {
    private final Supplier<TameworkApi> apiSupplier;
    private final Consumer<String> infoLogger;
    private final AtomicLong eventCount = new AtomicLong();
    @Nullable
    private AutoCloseable subscription;
    private boolean enabled;

    public CompanionXpEventDebugLogService(@Nonnull Supplier<TameworkApi> apiSupplier,
                                           @Nonnull Consumer<String> infoLogger) {
        this.apiSupplier = Objects.requireNonNull(apiSupplier);
        this.infoLogger = Objects.requireNonNull(infoLogger);
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized boolean setEnabled(boolean requested) {
        if (requested) {
            return enable();
        }
        disable();
        return false;
    }

    public synchronized boolean toggle() {
        return setEnabled(!enabled);
    }

    public long getEventCount() {
        return eventCount.get();
    }

    public void logHarvestDropAttempt(@Nonnull String message) {
        if (!isEnabled()) {
            return;
        }
        infoLogger.accept("[Tamework XP Event Debug] harvestDrop " + message);
    }

    private boolean enable() {
        if (enabled) {
            return true;
        }
        TameworkApi api = apiSupplier.get();
        if (!hasRequiredApi(api)) {
            infoLogger.accept("Tamework XP event debug logging could not be enabled: API events are unavailable.");
            return false;
        }
        subscription = api.events().subscribe(CompanionXpAwardedEvent.class, this::logEvent);
        enabled = true;
        eventCount.set(0L);
        infoLogger.accept("Tamework XP event debug logging enabled through TameworkApi.events().");
        return true;
    }

    private void disable() {
        AutoCloseable handle = subscription;
        subscription = null;
        enabled = false;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception ex) {
                infoLogger.accept("Tamework XP event debug logging unsubscribe failed: " + ex.getMessage());
            }
        }
        infoLogger.accept("Tamework XP event debug logging disabled after " + eventCount.get() + " event(s).");
    }

    private static boolean hasRequiredApi(@Nullable TameworkApi api) {
        if (api == null) {
            return false;
        }
        EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
        return capabilities.contains(TameworkApiCapability.EVENTS)
                && capabilities.contains(TameworkApiCapability.COMPANION_XP_EVENTS);
    }

    private void logEvent(@Nonnull CompanionXpAwardedEvent event) {
        long hit = eventCount.incrementAndGet();
        infoLogger.accept(
                "[Tamework XP Event Debug] hit="
                        + hit
                        + " npcUuid="
                        + event.npcUuid()
                        + " ownerUuid="
                        + valueOrNull(event.ownerUuid())
                        + " toolIds="
                        + formatToolIds(event.toolIds())
                        + " roleId="
                        + valueOrNull(event.roleId())
                        + " levelingConfigId="
                        + valueOrNull(event.levelingConfigId())
                        + " source="
                        + event.source()
                        + " awardedXp="
                        + formatDouble(event.awardedXp())
                        + " level="
                        + event.previousLevel()
                        + "->"
                        + event.currentLevel()
                        + " totalXp="
                        + formatDouble(event.previousTotalXp())
                        + "->"
                        + formatDouble(event.currentTotalXp())
                        + " currentXp="
                        + formatDouble(event.previousCurrentXp())
                        + "->"
                        + formatDouble(event.currentXp())
                        + " nextLevelXp="
                        + formatDouble(event.nextLevelXp())
                        + " maxLevel="
                        + event.maxLevel()
                        + " atMaxLevel="
                        + event.atMaxLevel()
                        + " leveledUp="
                        + event.leveledUp()
                        + " occurredAtMs="
                        + event.occurredAtMs()
                        + " emittedAtMs="
                        + event.emittedAtMs()
        );
    }

    @Nonnull
    private static String valueOrNull(@Nullable Object value) {
        return value == null ? "<null>" : value.toString();
    }

    @Nonnull
    private static String formatToolIds(@Nonnull Set<String> toolIds) {
        return toolIds.isEmpty() ? "[]" : toolIds.toString();
    }

    @Nonnull
    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @Override
    public synchronized void close() {
        if (enabled || subscription != null) {
            disable();
        }
    }
}
