package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

/**
 * Publishes generated patched templates as a transient runtime asset pack.
 */
public final class NpcTemplatePatchGeneratedPackPublisher {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_DIRECTORY_NAME = "GeneratedPatches";

    private final JavaPlugin plugin;
    private final String generatedPackId;

    public NpcTemplatePatchGeneratedPackPublisher(@Nonnull JavaPlugin plugin, @Nonnull String generatedPackId) {
        this.plugin = plugin;
        this.generatedPackId = generatedPackId;
    }

    @Nonnull
    public Path cacheRoot() {
        Path dataDirectory = plugin.getDataDirectory();
        if (dataDirectory == null) {
            Path pluginFile = plugin.getFile();
            Path parent = pluginFile == null ? Path.of(".") : pluginFile.toAbsolutePath().normalize().getParent();
            return (parent == null ? Path.of(".") : parent).resolve(CACHE_DIRECTORY_NAME).toAbsolutePath().normalize();
        }
        return dataDirectory.resolve(CACHE_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    public void publish(@Nonnull Map<String, JsonObject> generatedTemplates,
                        @Nonnull NpcTemplatePatchStatus status) throws IOException {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            throw new IOException("AssetModule unavailable.");
        }

        if (assetModule.getAssetPack(generatedPackId) != null) {
            assetModule.unregisterPack(generatedPackId);
        }

        Path root = cacheRoot();
        recreateCache(root);
        for (Map.Entry<String, JsonObject> entry : generatedTemplates.entrySet()) {
            writeTemplate(root, entry.getKey(), entry.getValue());
            status.addGeneratedTarget(entry.getKey());
        }

        if (generatedTemplates.isEmpty()) {
            return;
        }

        assetModule.registerPack(generatedPackId, root, plugin.getManifest(), false);
        moveGeneratedPackToEnd(assetModule);
    }

    public void reloadNpcBuilders() {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            return;
        }
        AssetPack generatedPack = assetModule.getAssetPack(generatedPackId);
        if (generatedPack == null) {
            return;
        }
        try {
            com.hypixel.hytale.server.npc.NPCPlugin npcPlugin = com.hypixel.hytale.server.npc.NPCPlugin.get();
            if (npcPlugin != null) {
                npcPlugin.getBuilderManager().loadBuilders(generatedPack, true);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework template patches: failed to reload generated NPC builders."
            );
        }
    }

    private void recreateCache(@Nonnull Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (Files.exists(normalizedRoot)) {
            try (var stream = Files.walk(normalizedRoot)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
                        throw new IOException("Refusing to delete path outside generated cache: " + path);
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(normalizedRoot);
    }

    private void writeTemplate(@Nonnull Path root,
                               @Nonnull String target,
                               @Nonnull JsonObject template) throws IOException {
        Path output = root.resolve(NpcTemplatePatchDefinition.normalizeAssetPath(target)).toAbsolutePath().normalize();
        if (!output.startsWith(root)) {
            throw new IOException("Generated target escapes cache root: " + target);
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, GSON.toJson(template), StandardCharsets.UTF_8);
    }

    private void moveGeneratedPackToEnd(@Nonnull AssetModule assetModule) {
        var packs = assetModule.getAssetPacks();
        int currentIndex = -1;
        for (int i = 0; i < packs.size(); i++) {
            AssetPack pack = packs.get(i);
            if (pack != null && generatedPackId.equals(pack.getName())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0 || currentIndex == packs.size() - 1) {
            return;
        }
        AssetPack generated = packs.remove(currentIndex);
        packs.add(generated);
    }
}
