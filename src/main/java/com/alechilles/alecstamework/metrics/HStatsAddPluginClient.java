package com.alechilles.alecstamework.metrics;

/**
 * Transport for reporting a plugin association to HStats.
 */
@FunctionalInterface
public interface HStatsAddPluginClient {
    boolean addPlugin(String serverUuid, String pluginUuid, String pluginVersion);
}
