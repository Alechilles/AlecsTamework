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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModItemFeatureConfigDiscovery {
    public static final String MOD_CONFIG_PATH = "Server/Tamework/Tamework_Items_Config.json";
    public static final String OVERRIDE_CONFIG_PATH = "config/Tamework_Items_Config_Override.json";

    private ModItemFeatureConfigDiscovery() {
    }

    public static int loadAll(ItemFeatureConfigLoader loader,
                              ItemFeatureRegistry registry,
                              HytaleLogger logger) {
        int loaded = 0;
        loaded += loadFromModsDirectory(loader, registry, logger);
        loaded += loadFromOverrideFile(loader, registry, logger);
        return loaded;
    }

    private static int loadFromOverrideFile(ItemFeatureConfigLoader loader,
                                            ItemFeatureRegistry registry,
                                            HytaleLogger logger) {
        Path overridePath = Path.of(OVERRIDE_CONFIG_PATH);
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
                                             HytaleLogger logger) {
        Path modsDir = resolveModsDirectory();
        if (modsDir == null) {
            logger.at(Level.INFO).log("No mods directory found for Tamework config discovery.");
            return 0;
        }
        int loaded = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path modPath : stream) {
                if (Files.isDirectory(modPath)) {
                    loaded += loadFromModFolder(loader, registry, logger, modPath);
                } else if (isArchive(modPath)) {
                    loaded += loadFromModArchive(loader, registry, logger, modPath);
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
                                         Path modPath) {
        Path configPath = modPath.resolve(MOD_CONFIG_PATH);
        if (!Files.exists(configPath)) {
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            int loaded = loader.loadFromReader(reader, registry, logger, configPath.toString());
            if (loaded > 0) {
                logger.at(Level.INFO).log("Loaded Tamework config from mod: " + configPath);
            }
            return loaded;
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to read Tamework config: " + configPath);
            return 0;
        }
    }

    private static int loadFromModArchive(ItemFeatureConfigLoader loader,
                                          ItemFeatureRegistry registry,
                                          HytaleLogger logger,
                                          Path modArchive) {
        try (ZipFile zipFile = new ZipFile(modArchive.toFile())) {
            ZipEntry entry = zipFile.getEntry(MOD_CONFIG_PATH);
            if (entry == null) {
                return 0;
            }
            try (InputStream stream = zipFile.getInputStream(entry);
                 Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                String label = modArchive.getFileName() + "!" + MOD_CONFIG_PATH;
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

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static Path resolveModsDirectory() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("mods"));
        candidates.add(Path.of("Server", "mods"));
        candidates.add(Path.of("..", "mods"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
