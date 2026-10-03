package com.alechilles.alecstamework.persistence;

import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the canonical universe-scoped directory without relocating historical
 * persistence evidence.
 */
public final class TameworkDataPathService {
    private static final String UNIVERSE_DIR_NAME = "universe";
    private static final String WORLDS_DIR_NAME = "worlds";
    private static final String MODS_DIR_NAME = "mods";
    private static final String SERVER_DIR_NAME = "server";
    private static final String HYTALE_DIR_NAME = "hytale";
    private static final String TAMEWORK_DIR_NAME = "Tamework";
    private static final String DATA_DIR_NAME = "Data";

    @Nullable
    private final HytaleLogger logger;

    public TameworkDataPathService() {
        this(null);
    }

    public TameworkDataPathService(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Resolves and initializes the preferred runtime data directory.
     *
     * <p>The historical method name is retained for callers compiled against the
     * earlier contract. No file is migrated, moved, renamed, or deleted. Persistence
     * importers must inspect {@link #resolveDataPathLayout(Path)} before creating a
     * replacement database.</p>
     */
    @Nonnull
    public Path resolveAndMigrateDataDirectory(@Nonnull Path legacyDataDirectory) {
        return resolveAndInitializeDataPathLayout(
                legacyDataDirectory
        ).targetDirectory();
    }

    /**
     * Resolves the immutable source layout and initializes only its canonical
     * write directory. A creation failure returns a layout whose target is the
     * legacy directory while preserving any distinct historical candidate.
     */
    @Nonnull
    public TameworkDataPathLayout resolveAndInitializeDataPathLayout(
            @Nonnull Path legacyDataDirectory
    ) {
        TameworkDataPathLayout layout = resolveDataPathLayout(
                legacyDataDirectory
        );
        Path preferredDir = layout.targetDirectory();
        if (!ensureDirectoryExists(preferredDir)) {
            log(Level.WARNING,
                    "Failed to initialize universe data directory '" + preferredDir
                            + "'. Falling back to legacy data directory '"
                            + layout.legacyDirectory() + "'.");
            ensureDirectoryExists(layout.legacyDirectory());
            return new TameworkDataPathLayout(
                    layout.legacyDirectory(),
                    layout.legacyDirectory(),
                    layout.historicalDirectory()
            );
        }
        return layout;
    }

    /**
     * Resolves the canonical target and all known read-only source candidates
     * without creating directories or changing any file.
     */
    @Nonnull
    public TameworkDataPathLayout resolveDataPathLayout(
            @Nonnull Path legacyDataDirectory
    ) {
        if (legacyDataDirectory == null) {
            throw new IllegalArgumentException(
                    "Legacy Tamework data directory is required"
            );
        }
        Path legacyDir = legacyDataDirectory.toAbsolutePath().normalize();
        Path preferredDir = resolvePreferredDataDirectory(legacyDir);
        Path historicalDir =
                resolveHistoricalServerAnchoredDataDirectory(legacyDir);
        return new TameworkDataPathLayout(
                preferredDir,
                legacyDir,
                Optional.ofNullable(historicalDir)
        );
    }

    @Nonnull
    Path resolvePreferredDataDirectory(@Nonnull Path legacyDataDirectory) {
        Path runtimeRoot = resolveRuntimeRoot(legacyDataDirectory);
        if (runtimeRoot == null) {
            return legacyDataDirectory;
        }
        return runtimeRoot.resolve(UNIVERSE_DIR_NAME).resolve(TAMEWORK_DIR_NAME).resolve(DATA_DIR_NAME).normalize();
    }

    @Nullable
    private Path resolveRuntimeRoot(@Nonnull Path legacyDataDirectory) {
        Path modsDir = findAncestorNamed(legacyDataDirectory, MODS_DIR_NAME);
        Path modsRuntimeRoot = modsDir != null ? modsDir.getParent() : null;
        if (modsRuntimeRoot != null && hasChildDirectory(modsRuntimeRoot, UNIVERSE_DIR_NAME)) {
            return modsRuntimeRoot;
        }

        Path namedRootWithUniverse = findNamedRuntimeAncestorWithUniverse(legacyDataDirectory);
        if (namedRootWithUniverse != null) {
            return namedRootWithUniverse;
        }

        if (modsRuntimeRoot != null && isLikelyRuntimeRoot(modsRuntimeRoot)) {
            return modsRuntimeRoot;
        }

        Path serverAncestor = findAncestorNamed(legacyDataDirectory, SERVER_DIR_NAME);
        if (serverAncestor != null) {
            return serverAncestor;
        }
        return null;
    }

    @Nullable
    private Path findAncestorNamed(@Nonnull Path startingPath, @Nonnull String expectedName) {
        Path cursor = startingPath;
        while (cursor != null) {
            Path fileName = cursor.getFileName();
            if (fileName != null && expectedName.equalsIgnoreCase(fileName.toString())) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    @Nullable
    private Path findNamedRuntimeAncestorWithUniverse(@Nonnull Path startingPath) {
        Path cursor = startingPath;
        while (cursor != null) {
            if (hasRuntimeRootName(cursor) && hasChildDirectory(cursor, UNIVERSE_DIR_NAME)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private boolean isLikelyRuntimeRoot(@Nonnull Path directory) {
        return hasRuntimeRootName(directory) || hasChildDirectory(directory, WORLDS_DIR_NAME);
    }

    private boolean hasRuntimeRootName(@Nonnull Path directory) {
        Path fileName = directory.getFileName();
        String currentName = fileName != null ? fileName.toString() : null;
        return currentName != null
                && (SERVER_DIR_NAME.equalsIgnoreCase(currentName) || HYTALE_DIR_NAME.equalsIgnoreCase(currentName));
    }

    private boolean hasChildDirectory(@Nonnull Path parent, @Nonnull String childDirectoryName) {
        Path direct = parent.resolve(childDirectoryName);
        if (Files.isDirectory(direct)) {
            return true;
        }
        if (!Files.isDirectory(parent)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child) || child.getFileName() == null) {
                    continue;
                }
                if (childDirectoryName.equalsIgnoreCase(child.getFileName().toString())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    @Nullable
    private Path resolveHistoricalServerAnchoredDataDirectory(@Nonnull Path legacyDataDirectory) {
        Path serverAncestor = findAncestorNamed(legacyDataDirectory, SERVER_DIR_NAME);
        if (serverAncestor == null) {
            return null;
        }
        return serverAncestor.resolve(UNIVERSE_DIR_NAME).resolve(TAMEWORK_DIR_NAME).resolve(DATA_DIR_NAME).normalize();
    }

    private boolean ensureDirectoryExists(@Nonnull Path directory) {
        try {
            Files.createDirectories(directory);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Failed to create directory '" + directory + "': " + ex.getMessage());
            return false;
        }
    }

    private void log(@Nonnull Level level, @Nonnull String message) {
        if (logger != null) {
            logger.at(level).log(message);
        }
    }
}
