package com.alechilles.alecstamework.metrics;

import java.util.logging.Level;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

/**
 * Bootstraps HStats metrics reporting for Alec's Tamework.
 */
public final class TameworkHStatsIntegration implements AutoCloseable {

    private static final String TAMEWORK_HSTATS_UUID = "3f16563d-d983-4cf2-ac22-3be61b3d920f";
    private static final Path HSTATS_SERVER_UUID_FILE = Path.of("hstats-server-uuid.txt");

    private final JavaPlugin plugin;
    private final TameworkDependencyMetricsReporter dependencyMetricsReporter;
    private final ExecutorService dependencyMetricsExecutor;
    private HStats hStats;
    private boolean initialized;

    public TameworkHStatsIntegration(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dependencyMetricsReporter =
                plugin == null ? null : new TameworkDependencyMetricsReporter(plugin.getLogger());
        this.dependencyMetricsExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AlecTamework-HStatsDependencies");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void initialize() {
        if (initialized || plugin == null) {
            return;
        }
        String version = resolvePluginVersion();
        try {
            hStats = new HStats(TAMEWORK_HSTATS_UUID, version);
            initialized = true;
            if (HStatsServerUuidFile.readEnabledServerUuid(HSTATS_SERVER_UUID_FILE) == null) {
                plugin.getLogger().at(Level.INFO).log(
                        "Tamework metrics are disabled by server config (hstats-server-uuid.txt)."
                );
                return;
            }
            plugin.getLogger().at(Level.INFO).log(
                    "Tamework metrics enabled via HStats. Server owners can opt out in hstats-server-uuid.txt."
            );
            if (dependencyMetricsReporter != null) {
                dependencyMetricsExecutor.execute(() ->
                        dependencyMetricsReporter.reportTrackedDependencies(
                                plugin.getDataDirectory(),
                                HSTATS_SERVER_UUID_FILE
                        )
                );
            }
        } catch (Exception ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex)
                    .log("Tamework metrics failed to initialize; continuing without HStats.");
        }
    }

    private String resolvePluginVersion() {
        PluginManifest manifest = plugin.getManifest();
        if (manifest == null) {
            return "Unknown";
        }
        Semver version = manifest.getVersion();
        if (version == null) {
            return "Unknown";
        }
        return version.toString();
    }

    @Override
    public void close() {
        dependencyMetricsExecutor.shutdownNow();
        if (hStats != null) {
            hStats.close();
            hStats = null;
        }
    }
}
