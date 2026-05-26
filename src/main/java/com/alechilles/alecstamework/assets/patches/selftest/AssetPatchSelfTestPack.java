package com.alechilles.alecstamework.assets.patches.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;

/**
 * Owns the isolated writable asset pack used by `/tw patches selftest`.
 */
public final class AssetPatchSelfTestPack {
    private static final String DEFAULT_GROUP = "Alechilles";
    private static final String PACK_NAME_SUFFIX = "AssetPatchSelfTest";
    private static final String DIRECTORY_NAME = "AssetPatchSelfTestPack";

    private final Path root;
    private final PluginManifest manifest;
    private final HytaleLogger logger;
    private final String packId;

    public AssetPatchSelfTestPack(@Nonnull Path dataDirectory,
                                  @Nullable PluginManifest manifest,
                                  @Nullable HytaleLogger logger) {
        this.root = dataDirectory.resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
        this.manifest = manifest;
        this.logger = logger;
        this.packId = createPackId(manifest);
    }

    @Nonnull
    public Path root() {
        return root;
    }

    public void prepareRoot() throws IOException {
        Files.createDirectories(root);
    }

    @Nonnull
    public String packId() {
        return packId;
    }

    public void registerIfMissing(@Nullable AssetModule assetModule) {
        if (assetModule == null) {
            return;
        }
        if (assetModule.getAssetPack(packId) != null) {
            return;
        }
        try {
            prepareRoot();
            assetModule.registerPack(packId, root, manifest, AssetPack.PackSource.RUNTIME);
            if (logger != null) {
                logger.at(Level.INFO).log("Registered Tamework asset patch self-test pack at " + root + ".");
            }
        } catch (RuntimeException | IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Failed to register Tamework asset patch self-test pack at " + root + "."
                );
            }
        }
    }

    public void writeRunFixtures(@Nonnull String runId, @Nonnull List<AssetPatchSelfTestCase> cases)
            throws IOException {
        prepareRoot();
        for (AssetPatchSelfTestCase selfTestCase : cases) {
            writeRelative(selfTestCase.sourcePath(), selfTestCase.sourceJson(runId));
            writeRelative(selfTestCase.patchPath(), selfTestCase.patchJson(runId));
        }
    }

    public void cleanupFixtures(@Nonnull List<AssetPatchSelfTestCase> cases) throws IOException {
        for (AssetPatchSelfTestCase selfTestCase : cases) {
            deleteRelative(selfTestCase.patchPath());
            deleteRelative(selfTestCase.sourcePath());
        }
        pruneEmptyDirectories();
    }

    @Nonnull
    public Path resolveRelative(@Nonnull String relativePath) throws IOException {
        Path resolved = root.resolve(AssetPatchSelfTestCase.normalizeAssetPath(relativePath))
                .toAbsolutePath()
                .normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Self-test path escapes pack root: " + relativePath);
        }
        return resolved;
    }

    @Nonnull
    AssetPack pack() {
        return new AssetPack(root, packId, root, null, false, manifest, AssetPack.PackSource.RUNTIME);
    }

    @Nonnull
    private static String createPackId(@Nullable PluginManifest manifest) {
        if (manifest == null) {
            return DEFAULT_GROUP + ":" + PACK_NAME_SUFFIX;
        }
        PluginIdentifier pluginId = new PluginIdentifier(manifest);
        return pluginId.getGroup() + ":" + pluginId.getName() + "_" + PACK_NAME_SUFFIX;
    }

    private void writeRelative(@Nonnull String relativePath, @Nonnull String content) throws IOException {
        Path target = resolveRelative(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void deleteRelative(@Nonnull String relativePath) throws IOException {
        Files.deleteIfExists(resolveRelative(relativePath));
    }

    private void pruneEmptyDirectories() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                if (path.equals(root)) {
                    continue;
                }
                try (var children = Files.list(path)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }
}
