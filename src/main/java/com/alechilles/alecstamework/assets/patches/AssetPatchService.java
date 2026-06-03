package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import javax.annotation.Nullable;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.assets.patches.selftest.AssetPatchSelfTestPack;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

/**
 * Coordinates discovery, generation, publication, and diagnostics for optional asset patches.
 */
public final class AssetPatchService {
    static final short EARLY_PATCH_GENERATION_PRIORITY = -39;

    private final JavaPlugin plugin;
    private final String generatedPackId;
    private final AssetPatchScanner scanner;
    private final AssetPatchTargetResolver targetResolver;
    private final AssetPatchEngine patchEngine;
    private final AssetPatchGeneratedPackPublisher publisher;
    private final AssetPatchReloadCoordinator reloadCoordinator;
    private final AssetPatchHotReloadTracker hotReloadTracker;

    private volatile AssetPatchStatus lastStatus = new AssetPatchStatus();

    public AssetPatchService(@Nonnull JavaPlugin plugin) {
        this(plugin, null);
    }

    public AssetPatchService(@Nonnull JavaPlugin plugin, @Nullable AssetPatchSelfTestPack selfTestPack) {
        this.plugin = plugin;
        this.generatedPackId = createGeneratedPackId(plugin);
        this.scanner = new AssetPatchScanner(plugin.getLogger());
        this.targetResolver = new AssetPatchTargetResolver();
        this.patchEngine = new AssetPatchEngine();
        this.publisher = new AssetPatchGeneratedPackPublisher(plugin, generatedPackId);
        this.reloadCoordinator = new AssetPatchReloadCoordinator(
                AssetPatchReloadCoordinator::loadGeneratedTameworkConfigFamily,
                createItemFeatureConfigReloader(plugin)
        );
        this.hotReloadTracker = new AssetPatchHotReloadTracker(generatedPackId, publisher.cacheRoot());
    }

    @Nonnull
    private static String createGeneratedPackId(@Nonnull JavaPlugin plugin) {
        PluginIdentifier pluginId = new PluginIdentifier(plugin.getManifest());
        return pluginId.getGroup() + ":" + pluginId.getName() + "_GeneratedPatches";
    }

    public void registerLoadHook() {
        if (plugin.getEventRegistry() == null) {
            return;
        }
        plugin.getEventRegistry().register(
                EARLY_PATCH_GENERATION_PRIORITY,
                LoadAssetEvent.class,
                this::onLoadAssets
        );
    }

    @Nonnull
    public AssetPatchStatus reload() {
        RegenerationResult result = regenerateAndPublish(
                AssetPatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
        );
        reloadRuntimeTargetsIfNeeded(result);
        return result.status();
    }

    @Nonnull
    public AssetPatchStatus getLastStatus() {
        return lastStatus;
    }

    @Nonnull
    public String getGeneratedPackId() {
        return generatedPackId;
    }

    @Nonnull
    public AssetPatchHotReloadTracker getHotReloadTracker() {
        return hotReloadTracker;
    }

    public void recordHotReloadedAssets(@Nonnull Class<?> assetClass,
                                        @Nullable AssetMap<?, ?> assetMap,
                                        @Nonnull Iterable<?> keys) {
        List<Object> keyList = new ArrayList<>();
        for (Object key : keys) {
            keyList.add(key);
        }
        hotReloadTracker.recordLoadedAssets(assetClass, assetMap, keyList);
    }

    public void recordGeneratedAssetStoreMonitor(@Nonnull Class<?> assetClass,
                                                 @Nullable String assetPack,
                                                 @Nonnull Iterable<Path> paths) {
        List<Path> pathList = new ArrayList<>();
        for (Path path : paths) {
            pathList.add(path);
        }
        hotReloadTracker.recordGeneratedAssetStoreMonitor(assetClass, assetPack, pathList);
    }

    public void recordGeneratedCommonAssetMonitor(@Nullable String assetPack,
                                                  @Nonnull Iterable<Path> paths) {
        List<Path> pathList = new ArrayList<>();
        for (Path path : paths) {
            pathList.add(path);
        }
        hotReloadTracker.recordGeneratedCommonAssetMonitor(assetPack, pathList);
    }

    @Nonnull
    public Path getGeneratedPatchCacheRoot() {
        return publisher.cacheRoot();
    }

    private void onLoadAssets(@Nonnull LoadAssetEvent event) {
        try {
            RegenerationResult result = regenerateAndPublish(
                    AssetPatchGeneratedPackPublisher.RegistrationMode.ALLOW_REGISTRATION
            );
            reloadRuntimeTargetsIfNeeded(result);
            if (result.status().hasFailures()) {
                event.failed(false, "Tamework patch errors");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework asset patches: failed during asset load."
            );
            event.failed(false, "Tamework patch generation failed");
        }
    }

    private void reloadRuntimeTargetsIfNeeded(@Nonnull RegenerationResult result) {
        if (!result.publicationResult().shouldReloadRuntimeTargets()) {
            return;
        }
        try {
            reloadCoordinator.reloadPublishedTargets(
                    publisher.getGeneratedPack(),
                    result.publicationResult().affectedTargets(),
                    result.status()
            );
        } catch (IOException ex) {
            String message = "Generated Tamework patch pack could not be hot-reloaded: " + ex.getMessage();
            result.status().addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }
    }

    @Nonnull
    private static Runnable createItemFeatureConfigReloader(@Nonnull JavaPlugin plugin) {
        if (plugin instanceof Tamework tamework) {
            return tamework::reloadItemFeatureConfigs;
        }
        return () -> {
        };
    }

    @Nonnull
    private RegenerationResult regenerateAndPublish(
            @Nonnull AssetPatchGeneratedPackPublisher.RegistrationMode registrationMode
    ) {
        AssetPatchStatus status = new AssetPatchStatus();
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            status.addFailed("AssetModule unavailable.");
            lastStatus = status;
            return new RegenerationResult(status, AssetPatchGeneratedPackPublisher.PublicationResult.empty());
        }

        List<AssetPatchDefinition> definitions =
                scanner.scan(assetModule.getAssetPacks(), generatedPackId, status);
        Map<String, List<AssetPatchDefinition>> byTarget = definitions.stream()
                .collect(Collectors.groupingBy(
                        AssetPatchDefinition::getTarget,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<String, JsonObject> generatedAssets = new LinkedHashMap<>();
        for (Map.Entry<String, List<AssetPatchDefinition>> entry : byTarget.entrySet()) {
            generateTarget(assetModule, entry.getKey(), entry.getValue(), generatedAssets, status);
        }

        AssetPatchGeneratedPackPublisher.PublicationResult publicationResult =
                AssetPatchGeneratedPackPublisher.PublicationResult.empty();
        try {
            publicationResult = publisher.publish(generatedAssets, status, registrationMode);
            hotReloadTracker.recordGeneratedNpcTargets(publicationResult.generatedTargets());
        } catch (IOException ex) {
            String message = "Failed to publish generated Tamework patch pack: " + ex.getMessage();
            status.addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }

        lastStatus = status;
        logStatus(status);
        return new RegenerationResult(status, publicationResult);
    }

    private record RegenerationResult(@Nonnull AssetPatchStatus status,
                                      @Nonnull AssetPatchGeneratedPackPublisher.PublicationResult publicationResult) {
    }

    private void generateTarget(@Nonnull AssetModule assetModule,
                                @Nonnull String target,
                                @Nonnull List<AssetPatchDefinition> definitions,
                                @Nonnull Map<String, JsonObject> generatedAssets,
                                @Nonnull AssetPatchStatus status) {
        AssetPatchTargetResolver.TargetSource source =
                targetResolver.resolve(assetModule.getAssetPacks(), generatedPackId, target);
        if (source == null) {
            status.addFailed("No source asset found for target " + target + ".");
            return;
        }
        try {
            JsonObject sourceJson = targetResolver.readAsset(source);
            AssetPatchEngine.PatchResult result = patchEngine.apply(sourceJson, definitions);
            mergeStatus(status, result.status());
            if (!result.status().hasFailures()) {
                generatedAssets.put(target, result.patched());
            }
        } catch (AssetPatchEngine.PatchFailureException ex) {
            status.addFailed(ex.getMessage());
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework asset patches: failed target " + target + "."
            );
        } catch (Exception ex) {
            String message = "Failed to patch target " + target + " from " + source.packId() + ": " + ex.getMessage();
            status.addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }
    }

    private void mergeStatus(@Nonnull AssetPatchStatus target, @Nonnull AssetPatchStatus source) {
        source.getApplied().forEach(target::addApplied);
        source.getSkipped().forEach(target::addSkipped);
        source.getFailed().forEach(target::addFailed);
        source.getGeneratedTargets().forEach(target::addGeneratedTarget);
        source.getRemovedGeneratedTargets().forEach(target::addRemovedGeneratedTarget);
        source.getHotReloadedTargets().forEach(target::addHotReloadedTarget);
        source.getRestartRequiredTargets().forEach(target::addRestartRequiredTarget);
    }

    private void logStatus(@Nonnull AssetPatchStatus status) {
        Level level = status.hasFailures() ? Level.WARNING : Level.INFO;
        plugin.getLogger().at(level).log("Tamework patches: " + status.summaryLine());
        for (String failure : status.getFailed()) {
            plugin.getLogger().at(Level.WARNING).log("Tamework patch failure: " + Objects.toString(failure));
        }
    }
}
