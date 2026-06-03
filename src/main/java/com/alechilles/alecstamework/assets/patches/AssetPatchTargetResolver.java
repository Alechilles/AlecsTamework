package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;

/**
 * Resolves the currently winning source asset for a target asset path.
 */
public final class AssetPatchTargetResolver {
    private static final Gson GSON = new Gson();

    @Nullable
    public TargetSource resolve(@Nonnull List<AssetPack> packs,
                                @Nonnull String generatedPackId,
                                @Nonnull String target) {
        TargetSource result = null;
        String normalizedTarget = AssetPatchDefinition.normalizeAssetPath(target);
        for (AssetPack pack : packs) {
            if (pack == null || generatedPackId.equals(pack.getName()) || pack.getRoot() == null) {
                continue;
            }
            Path root = pack.getRoot().toAbsolutePath().normalize();
            Path file = root.resolve(normalizedTarget).toAbsolutePath().normalize();
            if (!file.startsWith(root)) {
                continue;
            }
            if (Files.isRegularFile(file)) {
                result = new TargetSource(pack.getName(), file);
            }
        }
        return result;
    }

    @Nonnull
    public JsonObject readAsset(@Nonnull TargetSource source) throws IOException {
        try (Reader reader = Files.newBufferedReader(source.path(), StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new IOException("Asset source is not a JSON object: " + source.path());
            }
            return element.getAsJsonObject();
        }
    }

    /**
     * Winning source file for a target asset.
     */
    public record TargetSource(@Nonnull String packId, @Nonnull Path path) {
    }
}
