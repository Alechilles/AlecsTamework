package com.alechilles.alecstelemetry.runtime;

import com.alechilles.alecstelemetry.project.TelemetryProjectOverride;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads optional project override files stored by the telemetry runtime.
 */
public final class TelemetryProjectOverrideStore {

    private final HytaleLogger logger;

    public TelemetryProjectOverrideStore(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    @Nonnull
    public Map<String, TelemetryProjectOverride> loadAll(@Nonnull Path directory) {
        LinkedHashMap<String, TelemetryProjectOverride> overrides = new LinkedHashMap<>();
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : stream) {
                    String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
                    String projectId = projectIdFromFileName(fileName);
                    if (projectId.isBlank()) {
                        continue;
                    }
                    String rawJson = Files.readString(file, StandardCharsets.UTF_8);
                    TelemetryProjectOverride override = TelemetryProjectOverride.fromJson(rawJson);
                    if (override.hasAnyValue()) {
                        overrides.put(projectId.toLowerCase(Locale.ROOT), override);
                    }
                }
            }
        } catch (Exception ex) {
            warn("Failed to load telemetry project overrides from " + directory, ex);
        }
        return Map.copyOf(overrides);
    }

    @Nonnull
    private static String projectIdFromFileName(@Nonnull String fileName) {
        String trimmed = fileName.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return "";
        }
        return trimmed.substring(0, trimmed.length() - 5).trim();
    }

    private void warn(@Nonnull String message, @Nullable Throwable throwable) {
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.at(Level.WARNING).log(message);
            return;
        }
        logger.at(Level.WARNING).withCause(throwable).log(message);
    }
}
