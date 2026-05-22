package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;

/**
 * Publishes generated patched assets as a transient runtime asset pack.
 */
public final class AssetPatchGeneratedPackPublisher {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_DIRECTORY_NAME = "GeneratedPatches";

    private final JavaPlugin plugin;
    private final String generatedPackId;
    private final BuilderCacheReloader builderCacheReloader;

    enum RegistrationMode {
        ALLOW_REGISTRATION,
        REFRESH_EXISTING_ONLY
    }

    enum PublicationAction {
        NO_GENERATED_TEMPLATES,
        REFRESH_EXISTING_PACK,
        REGISTER_PACK,
        MISSING_RUNTIME_PACK
    }

    public AssetPatchGeneratedPackPublisher(@Nonnull JavaPlugin plugin, @Nonnull String generatedPackId) {
        this(plugin, generatedPackId, new NpcPluginBuilderCacheReloader());
    }

    AssetPatchGeneratedPackPublisher(@Nonnull JavaPlugin plugin,
                                           @Nonnull String generatedPackId,
                                           @Nonnull BuilderCacheReloader builderCacheReloader) {
        this.plugin = plugin;
        this.generatedPackId = generatedPackId;
        this.builderCacheReloader = builderCacheReloader;
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

    public boolean publish(@Nonnull Map<String, JsonObject> generatedAssets,
                           @Nonnull AssetPatchStatus status,
                           @Nonnull RegistrationMode registrationMode) throws IOException {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            throw new IOException("AssetModule unavailable.");
        }

        AssetPack existingPack = assetModule.getAssetPack(generatedPackId);
        boolean existingPackPresent = existingPack != null;

        PublicationAction action = publicationAction(
                existingPackPresent,
                !generatedAssets.isEmpty(),
                registrationMode
        );
        Path root = cacheRoot();
        if (!mutateCacheForPublication(existingPack, action, root, generatedAssets, status)) {
            return false;
        }

        // Live world commands must not mutate AssetModule pack registration; that can block the world thread.
        switch (action) {
            case NO_GENERATED_TEMPLATES -> {
                return shouldReloadNpcBuildersAfterPublication(true, action, existingPackPresent);
            }
            case REFRESH_EXISTING_PACK -> {
                moveGeneratedPackToEnd(assetModule);
                return shouldReloadNpcBuildersAfterPublication(true, action, existingPackPresent);
            }
            case REGISTER_PACK -> {
                assetModule.registerPack(generatedPackId, root, plugin.getManifest(), false);
                moveGeneratedPackToEnd(assetModule);
                return shouldReloadNpcBuildersAfterPublication(true, action, existingPackPresent);
            }
            case MISSING_RUNTIME_PACK -> {
                status.addFailed(
                        "Generated Tamework patch pack is not registered; restart the server to register generated patches."
                );
                return shouldReloadNpcBuildersAfterPublication(true, action, existingPackPresent);
            }
        }
        return false;
    }

    static PublicationAction publicationAction(boolean existingPackPresent,
                                               boolean hasGeneratedAssets,
                                               @Nonnull RegistrationMode registrationMode) {
        if (!hasGeneratedAssets) {
            return PublicationAction.NO_GENERATED_TEMPLATES;
        }
        if (existingPackPresent) {
            return PublicationAction.REFRESH_EXISTING_PACK;
        }
        if (registrationMode == RegistrationMode.ALLOW_REGISTRATION) {
            return PublicationAction.REGISTER_PACK;
        }
        return PublicationAction.MISSING_RUNTIME_PACK;
    }

    static boolean shouldRecreateCache(@Nonnull PublicationAction action) {
        return action == PublicationAction.REGISTER_PACK;
    }

    static boolean shouldReloadNpcBuildersAfterPublication(boolean cacheMutationSucceeded,
                                                           @Nonnull PublicationAction action,
                                                           boolean existingPackPresent) {
        if (!cacheMutationSucceeded) {
            return false;
        }
        return action == PublicationAction.REFRESH_EXISTING_PACK
                || (action == PublicationAction.NO_GENERATED_TEMPLATES && existingPackPresent);
    }

    boolean mutateCacheForPublication(AssetPack existingPack,
                                      @Nonnull PublicationAction action,
                                      @Nonnull Path root,
                                      @Nonnull Map<String, JsonObject> generatedAssets,
                                      @Nonnull AssetPatchStatus status) throws IOException {
        if (!unloadExistingGeneratedBuildersBeforeCacheMutation(existingPack, action, status)) {
            return false;
        }
        prepareCache(root, action);
        pruneStaleGeneratedFiles(root, generatedAssets.keySet());
        for (Map.Entry<String, JsonObject> entry : generatedAssets.entrySet()) {
            writeAsset(root, entry.getKey(), entry.getValue());
            status.addGeneratedTarget(entry.getKey());
        }
        return true;
    }

    boolean unloadExistingGeneratedBuildersBeforeCacheMutation(AssetPack existingPack,
                                                               @Nonnull PublicationAction action,
                                                               @Nonnull AssetPatchStatus status) {
        if (existingPack == null || !shouldUnloadExistingBuildersBeforeCacheMutation(action)) {
            return true;
        }
        try {
            builderCacheReloader.unload(existingPack);
            return true;
        } catch (RuntimeException ex) {
            String message = "Tamework asset patches: failed to unload generated NPC builders before cache refresh.";
            status.addFailed(message);
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
            }
            return false;
        }
    }

    static boolean shouldUnloadExistingBuildersBeforeCacheMutation(@Nonnull PublicationAction action) {
        return action == PublicationAction.REFRESH_EXISTING_PACK
                || action == PublicationAction.NO_GENERATED_TEMPLATES;
    }

    static void pruneStaleGeneratedFiles(@Nonnull Path root, @Nonnull Set<String> currentTargets) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            return;
        }
        Set<Path> keep = currentTargets.stream()
                .map(AssetPatchDefinition::normalizeAssetPath)
                .map(normalizedRoot::resolve)
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> path.startsWith(normalizedRoot))
                .collect(Collectors.toUnmodifiableSet());
        try (var stream = Files.walk(normalizedRoot)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(AssetPatchGeneratedPackPublisher::isJsonFile)
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(normalizedRoot)) {
                    throw new IOException("Refusing to delete path outside generated cache: " + path);
                }
                if (!keep.contains(normalized)) {
                    Files.deleteIfExists(normalized);
                }
            }
        }
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
            builderCacheReloader.load(generatedPack);
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework asset patches: failed to reload generated NPC builders."
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

    private void prepareCache(@Nonnull Path root, @Nonnull PublicationAction action) throws IOException {
        if (shouldRecreateCache(action)) {
            recreateCache(root);
            return;
        }
        Files.createDirectories(root.toAbsolutePath().normalize());
    }

    private void writeAsset(@Nonnull Path root,
                            @Nonnull String target,
                            @Nonnull JsonObject asset) throws IOException {
        Path output = root.resolve(AssetPatchDefinition.normalizeAssetPath(target)).toAbsolutePath().normalize();
        if (!output.startsWith(root)) {
            throw new IOException("Generated target escapes cache root: " + target);
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, GSON.toJson(asset), StandardCharsets.UTF_8);
    }

    private static boolean isJsonFile(@Nonnull Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().endsWith(".json");
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

    interface BuilderCacheReloader {
        void unload(@Nonnull AssetPack generatedPack);

        void load(@Nonnull AssetPack generatedPack);
    }

    private static final class NpcPluginBuilderCacheReloader implements BuilderCacheReloader {
        private NpcPluginBuilderCacheReloader() {
        }

        @Override
        public void unload(@Nonnull AssetPack generatedPack) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                npcPlugin.getBuilderManager().unloadBuilders(generatedPack);
            }
        }

        @Override
        public void load(@Nonnull AssetPack generatedPack) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                npcPlugin.getBuilderManager().loadBuilders(generatedPack, true);
            }
        }
    }
}
