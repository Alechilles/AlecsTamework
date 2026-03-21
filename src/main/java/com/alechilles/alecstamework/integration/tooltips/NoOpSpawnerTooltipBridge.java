package com.alechilles.alecstamework.integration.tooltips;

final class NoOpSpawnerTooltipBridge implements SpawnerTooltipBridge {
    static final NoOpSpawnerTooltipBridge INSTANCE = new NoOpSpawnerTooltipBridge();

    private NoOpSpawnerTooltipBridge() {
    }

    @Override
    public void refreshFromItemConfigReload() {
    }

    @Override
    public void shutdown() {
    }
}
