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
import com.hypixel.hytale.server.npc.NPCPlugin;

/**
 * Coordinates discovery, generation, publication, and diagnostics for optional NPC template patches.
 */
public final class NpcTemplatePatchService {
    private final JavaPlugin plugin;
    private final String generatedPackId;
    private final NpcTemplatePatchScanner scanner;
    private final NpcTemplatePatchTargetResolver targetResolver;
    private final NpcTemplatePatchEngine patchEngine;
    private final NpcTemplatePatchGeneratedPackPublisher publisher;

    private volatile NpcTemplatePatchStatus lastStatus = new NpcTemplatePatchStatus();

    public NpcTemplatePatchService(@Nonnull JavaPlugin plugin) {
        this.plugin = plugin;
        this.generatedPackId = new PluginIdentifier(plugin.getManifest()) + "_GeneratedPatches";
        this.scanner = new NpcTemplatePatchScanner(plugin.getLogger());
        this.targetResolver = new NpcTemplatePatchTargetResolver();
        this.patchEngine = new NpcTemplatePatchEngine();
        this.publisher = new NpcTemplatePatchGeneratedPackPublisher(plugin, generatedPackId);
    }

    public void registerLoadHook() {
        if (plugin.getEventRegistry() == null) {
            return;
        }
        plugin.getEventRegistry().register(
                (short) (NPCPlugin.PRIORITY_LOAD_NPC - 1),
                LoadAssetEvent.class,
                this::onLoadAssets
        );
    }

    @Nonnull
    public NpcTemplatePatchStatus reload() {
        NpcTemplatePatchStatus status = regenerateAndPublish();
        publisher.reloadNpcBuilders();
        return status;
    }

    @Nonnull
    public NpcTemplatePatchStatus getLastStatus() {
        return lastStatus;
    }

    @Nonnull
    public String getGeneratedPackId() {
        return generatedPackId;
    }

    private void onLoadAssets(@Nonnull LoadAssetEvent event) {
        try {
            NpcTemplatePatchStatus status = regenerateAndPublish();
            if (status.hasFailures()) {
                event.failed(false, "Tamework patch errors");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework template patches: failed during pre-NPC asset load."
            );
            event.failed(false, "Tamework patch generation failed");
        }
    }

    @Nonnull
    private NpcTemplatePatchStatus regenerateAndPublish() {
        NpcTemplatePatchStatus status = new NpcTemplatePatchStatus();
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            status.addFailed("AssetModule unavailable.");
            lastStatus = status;
            return status;
        }

        List<NpcTemplatePatchDefinition> definitions =
                scanner.scan(assetModule.getAssetPacks(), generatedPackId, status);
        Map<String, List<NpcTemplatePatchDefinition>> byTarget = definitions.stream()
                .collect(Collectors.groupingBy(
                        NpcTemplatePatchDefinition::getTarget,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<String, JsonObject> generatedTemplates = new LinkedHashMap<>();
        for (Map.Entry<String, List<NpcTemplatePatchDefinition>> entry : byTarget.entrySet()) {
            generateTarget(assetModule, entry.getKey(), entry.getValue(), generatedTemplates, status);
        }

        try {
            publisher.publish(generatedTemplates, status);
        } catch (IOException ex) {
            String message = "Failed to publish generated Tamework patch pack: " + ex.getMessage();
            status.addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }

        lastStatus = status;
        logStatus(status);
        return status;
    }

    private void generateTarget(@Nonnull AssetModule assetModule,
                                @Nonnull String target,
                                @Nonnull List<NpcTemplatePatchDefinition> definitions,
                                @Nonnull Map<String, JsonObject> generatedTemplates,
                                @Nonnull NpcTemplatePatchStatus status) {
        NpcTemplatePatchTargetResolver.TargetSource source =
                targetResolver.resolve(assetModule.getAssetPacks(), generatedPackId, target);
        if (source == null) {
            status.addFailed("No source template found for target " + target + ".");
            return;
        }
        try {
            JsonObject sourceJson = targetResolver.readTemplate(source);
            NpcTemplatePatchEngine.PatchResult result = patchEngine.apply(sourceJson, definitions);
            mergeStatus(status, result.status());
            if (!result.status().hasFailures()) {
                generatedTemplates.put(target, result.patched());
            }
        } catch (NpcTemplatePatchEngine.PatchFailureException ex) {
            status.addFailed(ex.getMessage());
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework template patches: failed target " + target + "."
            );
        } catch (Exception ex) {
            String message = "Failed to patch target " + target + " from " + source.packId() + ": " + ex.getMessage();
            status.addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }
    }

    private void mergeStatus(@Nonnull NpcTemplatePatchStatus target, @Nonnull NpcTemplatePatchStatus source) {
        source.getApplied().forEach(target::addApplied);
        source.getSkipped().forEach(target::addSkipped);
        source.getFailed().forEach(target::addFailed);
        source.getGeneratedTargets().forEach(target::addGeneratedTarget);
    }

    private void logStatus(@Nonnull NpcTemplatePatchStatus status) {
        Level level = status.hasFailures() ? Level.WARNING : Level.INFO;
        plugin.getLogger().at(level).log("Tamework patches: " + status.summaryLine());
        for (String failure : status.getFailed()) {
            plugin.getLogger().at(Level.WARNING).log("Tamework patch failure: " + Objects.toString(failure));
        }
    }
}
