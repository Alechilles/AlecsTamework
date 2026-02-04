package com.alechilles.alecstamework.config;

import com.hypixel.hytale.logger.HytaleLogger;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds mod and per-world config files and loads them.
 */
public final class ModItemFeatureConfigDiscovery {
    public static final String MOD_CONFIG_PATH = "Server/Tamework/Tamework_Items_Config.json";
    public static final String LOCAL_CONFIG_FILENAME = "Tamework_Items_Config.json";
    public static final String OVERRIDE_CONFIG_FILE = "Tamework_Items_Config_Override.json";
    private static final String MANIFEST_PATH = "manifest.json";
    private static final String EMPTY_CONFIG_JSON = "{\n  \"Items\": {}\n}\n";

    private ModItemFeatureConfigDiscovery() {
    }

    public static int loadAll(ItemFeatureConfigLoader loader,
                              ItemFeatureRegistry registry,
                              HytaleLogger logger,
                              Path dataDirectory) {
        int loaded = 0;
        Path globalModsDir = resolveGlobalModsDirectory(dataDirectory);
        Path saveModsDir = resolveSaveModsDirectory(dataDirectory);
        if (globalModsDir != null) {
            loaded += loadFromModsDirectory(loader, registry, logger, globalModsDir, MOD_CONFIG_PATH);
            if (saveModsDir != null && !globalModsDir.equals(saveModsDir)) {
                ensureEmptyLocalOverrides(globalModsDir, saveModsDir, logger);
            }
        }
        if (saveModsDir != null && (globalModsDir == null || !saveModsDir.equals(globalModsDir))) {
            loaded += loadFromModsDirectory(loader, registry, logger, saveModsDir, LOCAL_CONFIG_FILENAME);
        }
        if (globalModsDir == null && saveModsDir == null) {
            Path legacyModsDir = resolveModsDirectoryLegacy(dataDirectory);
            if (legacyModsDir != null) {
                loaded += loadFromModsDirectory(loader, registry, logger, legacyModsDir, MOD_CONFIG_PATH);
            } else {
                logger.at(Level.INFO).log("No mods directory found for Tamework config discovery.");
            }
        }
        loaded += loadFromOverrideFile(loader, registry, logger, dataDirectory);
        return loaded;
    }

    private static int loadFromOverrideFile(ItemFeatureConfigLoader loader,
                                            ItemFeatureRegistry registry,
                                            HytaleLogger logger,
                                            Path dataDirectory) {
        Path overridePath = resolveOverridePath(dataDirectory);
        if (!Files.exists(overridePath)) {
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(overridePath, StandardCharsets.UTF_8)) {
            int loaded = loader.loadFromReader(reader, registry, logger, overridePath.toString());
            if (loaded > 0) {
                logger.at(Level.INFO).log("Loaded Tamework override config: " + overridePath);
            }
            return loaded;
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to read Tamework override config: " + overridePath);
            return 0;
        }
    }

    private static int loadFromModsDirectory(ItemFeatureConfigLoader loader,
                                             ItemFeatureRegistry registry,
                                             HytaleLogger logger,
                                             Path modsDir,
                                             String configPath) {
        logger.at(Level.INFO).log("Tamework config discovery scanning mods dir: " + modsDir);
        int loaded = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path modPath : stream) {
                if (Files.isDirectory(modPath)) {
                    loaded += loadFromModFolder(loader, registry, logger, modPath, configPath);
                } else if (isArchive(modPath)) {
                    loaded += loadFromModArchive(loader, registry, logger, modPath, configPath);
                }
            }
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to scan mods directory: " + modsDir);
        }
        return loaded;
    }

    private static int loadFromModFolder(ItemFeatureConfigLoader loader,
                                         ItemFeatureRegistry registry,
                                         HytaleLogger logger,
                                         Path modPath,
                                         String configPath) {
        Path configFile = modPath.resolve(configPath);
        if (!Files.exists(configFile)) {
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            int loaded = loader.loadFromReader(reader, registry, logger, configFile.toString());
            if (loaded > 0) {
                logger.at(Level.INFO).log("Loaded Tamework config from mod: " + configFile);
            }
            return loaded;
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to read Tamework config: " + configFile);
            return 0;
        }
    }

    private static int loadFromModArchive(ItemFeatureConfigLoader loader,
                                          ItemFeatureRegistry registry,
                                          HytaleLogger logger,
                                          Path modArchive,
                                          String configPath) {
        try (ZipFile zipFile = new ZipFile(modArchive.toFile())) {
            ZipEntry entry = zipFile.getEntry(configPath);
            if (entry == null) {
                return 0;
            }
            try (InputStream stream = zipFile.getInputStream(entry);
                 Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                String label = (modArchive.getFileName() != null ? modArchive.getFileName().toString() : modArchive.toString()) + "!" + configPath;
                int loaded = loader.loadFromReader(reader, registry, logger, label);
                if (loaded > 0) {
                    logger.at(Level.INFO).log("Loaded Tamework config from archive: " + label);
                }
                return loaded;
            }
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to read Tamework config from archive: " + modArchive);
            return 0;
        }
    }

    // Match .jar/.zip mod archives; guard against null file names.
    private static boolean isArchive(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static Path resolveOverridePath(Path dataDirectory) {
        Path serverRoot = resolveServerRoot(dataDirectory);
        if (serverRoot == null) {
            return Path.of("Tamework", OVERRIDE_CONFIG_FILE);
        }
        return serverRoot.resolve("Tamework").resolve(OVERRIDE_CONFIG_FILE);
    }

    private static Path resolveServerRoot(Path dataDirectory) {
        Path modsDir = resolveModsDirectoryLegacy(dataDirectory);
        if (modsDir != null) {
            Path parent = modsDir.getParent();
            if (parent != null) {
                return parent.toAbsolutePath().normalize();
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Path resolveGlobalModsDirectory(Path dataDirectory) {
        Path userDataRoot = findUserDataRoot(dataDirectory);
        if (userDataRoot != null) {
            Path modsDir = userDataRoot.resolve("Mods");
            if (Files.isDirectory(modsDir)) {
                return modsDir.toAbsolutePath().normalize();
            }
        }
        return resolveModsDirectoryLegacy(dataDirectory);
    }

    private static Path resolveSaveModsDirectory(Path dataDirectory) {
        if (dataDirectory == null) {
            return null;
        }
        Path current = dataDirectory.toAbsolutePath().normalize();
        while (current != null) {
            Path parent = current.getParent();
            if (parent != null) {
                Path parentName = parent.getFileName();
                if (parentName != null && "mods".equalsIgnoreCase(parentName.toString())) {
                    Path worldDir = parent.getParent();
                    Path savesDir = worldDir != null ? worldDir.getParent() : null;
                    if (savesDir != null) {
                        Path savesName = savesDir.getFileName();
                        if (savesName != null && "saves".equalsIgnoreCase(savesName.toString())) {
                            return parent.toAbsolutePath().normalize();
                        }
                    }
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path findUserDataRoot(Path dataDirectory) {
        if (dataDirectory == null) {
            return null;
        }
        Path current = dataDirectory.toAbsolutePath().normalize();
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && "userdata".equalsIgnoreCase(name.toString())) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static void ensureEmptyLocalOverrides(Path globalModsDir, Path saveModsDir, HytaleLogger logger) {
        if (saveModsDir == null) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(globalModsDir)) {
            for (Path modPath : stream) {
                if (Files.isDirectory(modPath)) {
                    ensureEmptyLocalOverrideFromFolder(modPath, saveModsDir, logger);
                } else if (isArchive(modPath)) {
                    ensureEmptyLocalOverrideFromArchive(modPath, saveModsDir, logger);
                }
            }
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex)
                    .log("Failed to ensure local Tamework config overrides in: " + saveModsDir);
        }
    }

    private static void ensureEmptyLocalOverrideFromFolder(Path modPath, Path saveModsDir, HytaleLogger logger) {
        Path configPath = modPath.resolve(MOD_CONFIG_PATH);
        if (!Files.exists(configPath)) {
            return;
        }
        // Prefer manifest Name for per-world overrides.
        String modName = resolveModNameFromFolder(modPath);
        if (modName == null || modName.isEmpty()) {
            modName = modPath.getFileName().toString();
        }
        Path localConfigPath = saveModsDir
                .resolve(modName)
                .resolve(LOCAL_CONFIG_FILENAME);
        if (Files.exists(localConfigPath)) {
            return;
        }
        try {
            Files.createDirectories(localConfigPath.getParent());
            Files.writeString(localConfigPath, EMPTY_CONFIG_JSON, StandardCharsets.UTF_8);
            logger.at(Level.INFO).log("Created empty local Tamework item config: " + localConfigPath);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex)
                    .log("Failed to create local Tamework item config: " + localConfigPath);
        }
    }

    private static void ensureEmptyLocalOverrideFromArchive(Path modArchive, Path saveModsDir, HytaleLogger logger) {
        try (ZipFile zipFile = new ZipFile(modArchive.toFile())) {
            ZipEntry entry = zipFile.getEntry(MOD_CONFIG_PATH);
            if (entry == null) {
                return;
            }
            String modName = resolveModNameFromArchive(zipFile, modArchive);
            if (modName == null || modName.isEmpty()) {
                modName = stripArchiveExtension(modArchive.getFileName().toString());
            }
            Path localConfigPath = saveModsDir.resolve(modName).resolve(LOCAL_CONFIG_FILENAME);
            if (Files.exists(localConfigPath)) {
                return;
            }
            Files.createDirectories(localConfigPath.getParent());
            Files.writeString(localConfigPath, EMPTY_CONFIG_JSON, StandardCharsets.UTF_8);
            logger.at(Level.INFO).log("Created empty local Tamework item config: " + localConfigPath);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex)
                    .log("Failed to create local Tamework item config for archive: " + modArchive);
        }
    }

    private static String resolveModNameFromFolder(Path modPath) {
        Path manifestPath = modPath.resolve(MANIFEST_PATH);
        if (!Files.exists(manifestPath)) {
            return null;
        }
        try {
            String contents = Files.readString(manifestPath, StandardCharsets.UTF_8);
            return extractNameFromManifest(contents);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String resolveModNameFromArchive(ZipFile zipFile, Path modArchive) {
        try {
            ZipEntry entry = zipFile.getEntry(MANIFEST_PATH);
            if (entry == null) {
                return null;
            }
            try (InputStream stream = zipFile.getInputStream(entry)) {
                String contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return extractNameFromManifest(contents);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static String extractNameFromManifest(String manifestJson) {
        if (manifestJson == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"Name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(manifestJson);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static String stripArchiveExtension(String filename) {
        String name = filename;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private static Path resolveModsDirectoryLegacy(Path dataDirectory) {
        List<Path> candidates = new ArrayList<>();
        if (dataDirectory != null) {
            Path parent = dataDirectory.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                candidates.add(parent);
            }
        }
        candidates.add(Path.of("mods"));
        candidates.add(Path.of("Server", "mods"));
        candidates.add(Path.of("..", "mods"));
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
