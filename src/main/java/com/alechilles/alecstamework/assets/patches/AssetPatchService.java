package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;
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

    private volatile AssetPatchStatus lastStatus = new AssetPatchStatus();

    public AssetPatchService(@Nonnull JavaPlugin plugin) {
        this.plugin = plugin;
        this.generatedPackId = new PluginIdentifier(plugin.getManifest()) + "_GeneratedPatches";
        this.scanner = new AssetPatchScanner(plugin.getLogger());
        this.targetResolver = new AssetPatchTargetResolver();
        this.patchEngine = new AssetPatchEngine();
        this.publisher = new AssetPatchGeneratedPackPublisher(plugin, generatedPackId);
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
        if (result.reloadNpcBuilders()) {
            publisher.reloadNpcBuilders();
        }
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

    private void onLoadAssets(@Nonnull LoadAssetEvent event) {
        try {
            RegenerationResult result = regenerateAndPublish(
                    AssetPatchGeneratedPackPublisher.RegistrationMode.ALLOW_REGISTRATION
            );
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

    @Nonnull
    private RegenerationResult regenerateAndPublish(
            @Nonnull AssetPatchGeneratedPackPublisher.RegistrationMode registrationMode
    ) {
        AssetPatchStatus status = new AssetPatchStatus();
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            status.addFailed("AssetModule unavailable.");
            lastStatus = status;
            return new RegenerationResult(status, false);
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

        boolean reloadNpcBuilders = false;
        try {
            reloadNpcBuilders = publisher.publish(generatedAssets, status, registrationMode);
        } catch (IOException ex) {
            String message = "Failed to publish generated Tamework patch pack: " + ex.getMessage();
            status.addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }

        lastStatus = status;
        logStatus(status);
        return new RegenerationResult(status, reloadNpcBuilders);
    }

    private record RegenerationResult(@Nonnull AssetPatchStatus status, boolean reloadNpcBuilders) {
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
    }

    private void logStatus(@Nonnull AssetPatchStatus status) {
        Level level = status.hasFailures() ? Level.WARNING : Level.INFO;
        plugin.getLogger().at(level).log("Tamework patches: " + status.summaryLine());
        for (String failure : status.getFailed()) {
            plugin.getLogger().at(Level.WARNING).log("Tamework patch failure: " + Objects.toString(failure));
        }
    }
}
