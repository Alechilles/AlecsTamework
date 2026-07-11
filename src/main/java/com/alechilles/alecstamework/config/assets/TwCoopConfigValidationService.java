package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Emits one actionable warning when a coop config cannot own managed lifecycle authority. */
public final class TwCoopConfigValidationService {
    private static final Map<String, String> LAST_WARNING_BY_CONFIG = new ConcurrentHashMap<>();

    private TwCoopConfigValidationService() {
    }

    /** Clears process-local warning suppression when a new plugin instance registers its assets. */
    public static void resetWarnings() {
        LAST_WARNING_BY_CONFIG.clear();
    }

    /** Forgets removed config IDs so an unchanged config warns again if it is later re-added. */
    public static void forgetConfigs(@Nullable Collection<String> configIds) {
        if (configIds == null) {
            return;
        }
        for (String configId : configIds) {
            if (configId != null && !configId.isBlank()) {
                LAST_WARNING_BY_CONFIG.remove(configId.trim());
            }
        }
    }

    public static void logInvalidManagedConfigs(@Nullable HytaleLogger logger,
                                                @Nullable Iterable<TwCoopConfig> configs) {
        if (logger == null || configs == null) {
            return;
        }
        for (TwCoopConfig config : configs) {
            if (config == null) {
                continue;
            }
            String key = configKey(config);
            String error = config.getManagedAuthorityValidationError();
            if (error == null) {
                LAST_WARNING_BY_CONFIG.remove(key);
                continue;
            }
            String previous = LAST_WARNING_BY_CONFIG.put(key, error);
            if (error.equals(previous)) {
                continue;
            }
            logger.at(Level.WARNING).log(
                    "Ignored managed coop overlay '" + key + "': " + error + ". "
                            + "Another valid overlay may become authority; otherwise the coop remains vanilla."
            );
        }
    }

    @Nonnull
    private static String configKey(@Nonnull TwCoopConfig config) {
        String id = config.getId();
        return id == null || id.isBlank() ? "<unknown>" : id;
    }
}
