package com.alechilles.alecstelemetry.runtime;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;

/**
 * Runtime-owned filesystem layout for Alec's Telemetry.
 */
public record TelemetryDataPaths(@Nonnull Path runtimeRoot,
                                  @Nonnull Path settingsFile,
                                  @Nonnull Path projectSettingsDirectory,
                                  @Nonnull Path telemetryRoot,
                                  @Nonnull Path crashReportsRoot,
                                  @Nonnull Path eventReportsRoot,
                                  @Nullable Path modsDirectory) {

    @Nonnull
    public static TelemetryDataPaths from(@Nonnull JavaPlugin plugin) {
        Path dataDirectory = plugin.getDataDirectory().toAbsolutePath().normalize();
        Path runtimeRoot = dataDirectory;
        Path telemetryRoot = runtimeRoot.resolve("Telemetry");
        Path settingsRoot = runtimeRoot.resolve("Settings");
        return new TelemetryDataPaths(
                runtimeRoot,
                settingsRoot.resolve("runtime.json"),
                settingsRoot.resolve("projects"),
                telemetryRoot,
                telemetryRoot.resolve("crash-reports"),
                telemetryRoot.resolve("events"),
                resolveModsDirectory(dataDirectory)
        );
    }

    @Nonnull
    public static TelemetryDataPaths forEmbeddedOwner(@Nonnull JavaPlugin plugin) {
        Path dataDirectory = plugin.getDataDirectory().toAbsolutePath().normalize();
        Path telemetryRoot = dataDirectory.resolve("Telemetry");
        Path settingsRoot = telemetryRoot.resolve("Settings");
        return new TelemetryDataPaths(
                telemetryRoot,
                settingsRoot.resolve("runtime.json"),
                settingsRoot.resolve("projects"),
                telemetryRoot,
                telemetryRoot.resolve("crash-reports"),
                telemetryRoot.resolve("events"),
                null
        );
    }

    @Nonnull
    public Path pendingDirectory(@Nonnull String projectId) {
        return crashReportsRoot.resolve(projectId).resolve("pending");
    }

    @Nonnull
    public Path pendingEventsDirectory(@Nonnull String projectId) {
        return eventReportsRoot.resolve(projectId).resolve("pending");
    }

    @Nonnull
    public Path projectOverrideFile(@Nonnull String projectId) {
        return projectSettingsDirectory.resolve(projectId + ".json");
    }

    @Nullable
    private static Path resolveModsDirectory(@Nonnull Path dataDirectory) {
        Path current = dataDirectory;
        while (current != null) {
            Path fileName = current.getFileName();
            if (fileName != null && "mods".equalsIgnoreCase(fileName.toString())) {
                return current;
            }
            current = current.getParent();
        }
        Path parent = dataDirectory.getParent();
        return parent == null ? null : parent.toAbsolutePath().normalize();
    }
}
