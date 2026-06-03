package com.alechilles.alecstamework.lifecycle;

import com.hypixel.hytale.event.IBaseEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Registers optional Hytale event listeners without letting unavailable event buses abort startup.
 */
public final class TameworkEventRegistrationSupport {
    private static final String EVENT_REGISTRY_SHUTDOWN_MESSAGE = "EventRegistry is shutdown!";

    private TameworkEventRegistrationSupport() {
    }

    public static <KeyType, EventType extends IBaseEvent<KeyType>> boolean registerGlobal(
            @Nonnull JavaPlugin plugin,
            @Nonnull Class<? super EventType> eventClass,
            @Nonnull Consumer<EventType> listener,
            @Nonnull String listenerName) {
        if (plugin.getEventRegistry() == null) {
            logUnavailable(plugin, eventClass, listenerName, null);
            return false;
        }
        try {
            plugin.getEventRegistry().registerGlobal(eventClass, listener);
            return true;
        } catch (IllegalArgumentException ex) {
            if (!isEventRegistryShutdown(ex)) {
                throw ex;
            }
            logUnavailable(plugin, eventClass, listenerName, ex);
            return false;
        }
    }

    static boolean isEventRegistryShutdown(@Nullable IllegalArgumentException ex) {
        return ex != null && EVENT_REGISTRY_SHUTDOWN_MESSAGE.equals(ex.getMessage());
    }

    private static void logUnavailable(JavaPlugin plugin,
                                       Class<?> eventClass,
                                       String listenerName,
                                       @Nullable Throwable throwable) {
        String message = "Tamework skipped optional global event listener '" + listenerName
                + "' for " + eventClass.getSimpleName()
                + " because the Hytale event registry is unavailable during plugin setup.";
        if (throwable == null) {
            plugin.getLogger().at(Level.WARNING).log(message);
            return;
        }
        plugin.getLogger().at(Level.WARNING).withCause(throwable).log(message);
    }
}
