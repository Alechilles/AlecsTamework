package com.alechilles.alecstamework.metrics;

import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * Reports installed tracked dependency mods through Tamework's HStats bridge.
 */
public final class TameworkDependencyMetricsReporter {

    private final HytaleLogger logger;
    private final InstalledModManifestDiscovery manifestDiscovery;
    private final HStatsAddPluginClient addPluginClient;

    public TameworkDependencyMetricsReporter(HytaleLogger logger) {
        this(logger, new InstalledModManifestDiscovery(logger), new HttpHStatsAddPluginClient(logger));
    }

    TameworkDependencyMetricsReporter(HytaleLogger logger,
                                      InstalledModManifestDiscovery manifestDiscovery,
                                      HStatsAddPluginClient addPluginClient) {
        this.logger = logger;
        this.manifestDiscovery = manifestDiscovery;
        this.addPluginClient = addPluginClient;
    }

    public void reportTrackedDependencies(Path dataDirectory, Path serverUuidFile) {
        List<InstalledModManifest> manifests = manifestDiscovery.discover(dataDirectory);
        reportTrackedDependencies(manifests, serverUuidFile);
    }

    void reportTrackedDependencies(List<InstalledModManifest> manifests, Path serverUuidFile) {
        if (manifests == null || manifests.isEmpty()) {
            return;
        }
        String serverUuid = HStatsServerUuidFile.readEnabledServerUuid(serverUuidFile);
        if (isBlank(serverUuid)) {
            info("Tamework HStats forwarding skipped: metrics disabled via " + serverUuidFile);
            return;
        }
        int trackedDetected = 0;
        int reported = 0;
        int failed = 0;
        for (InstalledModManifest manifest : manifests) {
            if (manifest == null) {
                continue;
            }
            String pluginUuid = TameworkTrackedModRegistry.getHStatsUuid(manifest.modId());
            if (isBlank(pluginUuid)) {
                continue;
            }
            if (!manifest.dependsOnTamework()) {
                info("Tamework HStats forwarding skipped " + manifest.modId()
                        + " from " + manifest.sourcePath()
                        + " because it does not declare a Tamework dependency.");
                continue;
            }
            trackedDetected++;
            boolean success = addPluginClient.addPlugin(serverUuid, pluginUuid, manifest.version());
            if (success) {
                reported++;
            } else {
                failed++;
                warn("Tamework HStats forwarding failed for " + manifest.modId()
                        + " (" + manifest.version() + ") from " + manifest.sourcePath());
            }
        }
        if (trackedDetected > 0) {
            info("Tamework HStats forwarding summary: detected="
                    + trackedDetected
                    + ", reported="
                    + reported
                    + ", failed="
                    + failed);
        }
    }

    private void info(String message) {
        if (logger != null) {
            logger.at(Level.INFO).log(message);
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.at(Level.WARNING).log(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
