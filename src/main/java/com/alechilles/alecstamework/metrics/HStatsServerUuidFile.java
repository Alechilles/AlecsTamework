package com.alechilles.alecstamework.metrics;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads HStats server UUID configuration and opt-out status from disk.
 */
public final class HStatsServerUuidFile {

    private HStatsServerUuidFile() {
    }

    public static String readEnabledServerUuid(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String raw = Files.readString(file);
            String[] lines = raw.split("\\R");
            boolean enabled = false;
            String uuid = null;
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("enabled=")) {
                    enabled = "true".equalsIgnoreCase(trimmed.substring("enabled=".length()).trim());
                    continue;
                }
                uuid = trimmed;
            }
            if (!enabled || uuid == null || uuid.isBlank()) {
                return null;
            }
            return uuid;
        } catch (Exception ignored) {
            return null;
        }
    }
}
