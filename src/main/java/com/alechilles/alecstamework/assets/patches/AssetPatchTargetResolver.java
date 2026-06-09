package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
            if (isArchive(root)) {
                if (archiveContains(root, normalizedTarget)) {
                    result = new TargetSource(pack.getName(), root, normalizedTarget);
                }
                continue;
            }
            Path file = root.resolve(normalizedTarget).toAbsolutePath().normalize();
            if (!file.startsWith(root)) {
                continue;
            }
            if (Files.isRegularFile(file)) {
                result = new TargetSource(pack.getName(), file, null);
            }
        }
        return result;
    }

    @Nonnull
    public JsonObject readAsset(@Nonnull TargetSource source) throws IOException {
        try (Reader reader = openReader(source)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new IOException("Asset source is not a JSON object: " + source.describe());
            }
            return element.getAsJsonObject();
        }
    }

    @Nonnull
    private static Reader openReader(@Nonnull TargetSource source) throws IOException {
        String archiveEntry = source.archiveEntry();
        if (archiveEntry == null) {
            return Files.newBufferedReader(source.path(), StandardCharsets.UTF_8);
        }
        ZipFile zipFile = new ZipFile(source.path().toFile());
        ZipEntry entry = zipFile.getEntry(archiveEntry);
        if (entry == null || entry.isDirectory()) {
            zipFile.close();
            throw new IOException("Asset source entry is missing: " + source.describe());
        }
        return new ArchiveEntryReader(zipFile, entry);
    }

    private static boolean archiveContains(@Nonnull Path archive, @Nonnull String entryName) {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            return entry != null && !entry.isDirectory();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isArchive(@Nonnull Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    /**
     * Winning source file for a target asset.
     */
    public record TargetSource(@Nonnull String packId, @Nonnull Path path, @Nullable String archiveEntry) {
        @Nonnull
        private String describe() {
            return archiveEntry == null ? path.toString() : path + "!" + archiveEntry;
        }
    }

    private static final class ArchiveEntryReader extends Reader {
        private final ZipFile zipFile;
        private final Reader delegate;

        private ArchiveEntryReader(@Nonnull ZipFile zipFile, @Nonnull ZipEntry entry) throws IOException {
            this.zipFile = zipFile;
            this.delegate = new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                zipFile.close();
            }
        }
    }
}
