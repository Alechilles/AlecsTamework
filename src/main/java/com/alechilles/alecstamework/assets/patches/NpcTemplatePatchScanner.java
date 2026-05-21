package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Scans registered asset packs for optional patch definitions.
 */
public final class NpcTemplatePatchScanner {
    public static final String PATCH_DIRECTORY = "Server/Tamework/Patches";

    private static final Gson GSON = new Gson();

    private final HytaleLogger logger;

    public NpcTemplatePatchScanner(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    @Nonnull
    public List<NpcTemplatePatchDefinition> scan(@Nonnull List<AssetPack> packs,
                                                 @Nonnull String generatedPackId,
                                                 @Nonnull NpcTemplatePatchStatus status) {
        List<NpcTemplatePatchDefinition> definitions = new ArrayList<>();
        for (AssetPack pack : packs) {
            if (pack == null || generatedPackId.equals(pack.getName())) {
                continue;
            }
            Path root = pack.getRoot();
            if (root == null) {
                continue;
            }
            Path patchRoot = root.resolve(PATCH_DIRECTORY);
            if (!Files.isDirectory(patchRoot)) {
                continue;
            }
            scanPack(pack, patchRoot, definitions, status);
        }
        definitions.sort(Comparator.comparing(NpcTemplatePatchDefinition::getTarget)
                .thenComparingInt(NpcTemplatePatchDefinition::getPriority)
                .thenComparing(NpcTemplatePatchDefinition::getId));
        return definitions;
    }

    private void scanPack(@Nonnull AssetPack pack,
                          @Nonnull Path patchRoot,
                          @Nonnull List<NpcTemplatePatchDefinition> definitions,
                          @Nonnull NpcTemplatePatchStatus status) {
        try (var stream = Files.walk(patchRoot)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                parsePatchFile(pack, patchRoot, file, definitions, status);
            }
        } catch (IOException ex) {
            String message = "Failed to scan template patches in pack '" + pack.getName() + "': " + ex.getMessage();
            status.addFailed(message);
            logWarning(message, ex);
        }
    }

    private void parsePatchFile(@Nonnull AssetPack pack,
                                @Nonnull Path patchRoot,
                                @Nonnull Path file,
                                @Nonnull List<NpcTemplatePatchDefinition> definitions,
                                @Nonnull NpcTemplatePatchStatus status) {
        String sourcePath = NpcTemplatePatchDefinition.normalizeAssetPath(PATCH_DIRECTORY + "/"
                + patchRoot.relativize(file).toString());
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("Patch file must contain a JSON object.");
            }
            NpcTemplatePatchDefinition definition =
                    NpcTemplatePatchDefinition.parse((JsonObject) element, pack.getName(), sourcePath);
            if (definition.isEnabled()) {
                definitions.add(definition);
            } else {
                status.addSkipped(definition.getId() + " disabled");
            }
        } catch (Exception ex) {
            String message = "Failed to parse template patch " + pack.getName() + ":" + sourcePath + ": " + ex.getMessage();
            status.addFailed(message);
            logWarning(message, ex);
        }
    }

    private void logWarning(@Nonnull String message, @Nullable Throwable throwable) {
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.at(Level.WARNING).log(message);
        } else {
            logger.at(Level.WARNING).withCause(throwable).log(message);
        }
    }
}
