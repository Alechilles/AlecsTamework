package com.alechilles.alecstamework.localization;

import com.hypixel.hytale.logger.HytaleLogger;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers and loads language files from mods and per-world overrides.
 */
public final class ModLanguageDiscovery {
    private static final String LANGUAGE_ROOT = "Server/Languages";
    private static final String SERVER_LANG_FILE = "server.lang";

    private ModLanguageDiscovery() {
    }

    public static int loadAll(TranslationRegistry registry,
                              HytaleLogger logger,
                              Path dataDirectory) {
        // Load from global mods first, then per-world mods.
        List<Path> modsDirs = resolveModsDirectories(dataDirectory);
        if (modsDirs.isEmpty()) {
            logger.at(Level.INFO).log("No mods directory found for language discovery.");
            return 0;
        }
        int loaded = 0;
        for (Path modsDir : modsDirs) {
            loaded += loadFromModsDirectory(registry, logger, modsDir);
        }
        return loaded;
    }

    private static int loadFromModsDirectory(TranslationRegistry registry,
                                             HytaleLogger logger,
                                             Path modsDir) {
        logger.at(Level.INFO).log("Tamework language discovery scanning mods dir: " + modsDir);
        int loaded = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path modPath : stream) {
                if (Files.isDirectory(modPath)) {
                    loaded += loadFromModFolder(registry, logger, modPath);
                } else if (isArchive(modPath)) {
                    loaded += loadFromModArchive(registry, logger, modPath);
                }
            }
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to scan mods directory for languages: " + modsDir);
        }
        return loaded;
    }

    private static int loadFromModFolder(TranslationRegistry registry,
                                         HytaleLogger logger,
                                         Path modPath) {
        Path languageRoot = modPath.resolve(LANGUAGE_ROOT);
        if (!Files.isDirectory(languageRoot)) {
            return 0;
        }
        int loaded = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(languageRoot)) {
            for (Path languageDir : stream) {
                if (!Files.isDirectory(languageDir) || languageDir.getFileName() == null) {
                    continue;
                }
                Path langPath = languageDir.resolve(SERVER_LANG_FILE);
                if (!Files.exists(langPath)) {
                    continue;
                }
                String language = languageDir.getFileName().toString();
                try (Reader reader = Files.newBufferedReader(langPath, StandardCharsets.UTF_8)) {
                    int fileLoaded = loadFromReader(registry, language, reader);
                    loaded += fileLoaded;
                    if (fileLoaded > 0) {
                        logger.at(Level.INFO).log("Loaded Tamework language entries from mod: " + langPath);
                    }
                } catch (Exception ex) {
                    logger.at(Level.WARNING).withCause(ex).log("Failed to read language file: " + langPath);
                }
            }
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to scan language files in mod: " + languageRoot);
        }
        return loaded;
    }

    private static int loadFromModArchive(TranslationRegistry registry,
                                          HytaleLogger logger,
                                          Path modArchive) {
        try (ZipFile zipFile = new ZipFile(modArchive.toFile())) {
            int loaded = 0;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) {
                    continue;
                }
                String language = languageFromArchiveEntry(entry.getName());
                if (language == null) {
                    continue;
                }
                try (InputStream stream = zipFile.getInputStream(entry);
                     Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    String label = (modArchive.getFileName() != null ? modArchive.getFileName().toString() : modArchive.toString())
                            + "!" + entry.getName();
                    int fileLoaded = loadFromReader(registry, language, reader);
                    loaded += fileLoaded;
                    if (fileLoaded > 0) {
                        logger.at(Level.INFO).log("Loaded Tamework language entries from archive: " + label);
                    }
                }
            }
            return loaded;
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Failed to read language file from archive: " + modArchive);
            return 0;
        }
    }

    private static int loadFromReader(TranslationRegistry registry, String language, Reader reader) throws Exception {
        int loaded = 0;
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            // Skip blank lines and comments; parse key=value pairs.
            while ((line = buffered.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                registry.put(language, key, value);
                loaded++;
            }
        }
        return loaded;
    }

    private static String languageFromArchiveEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return null;
        }
        String normalized = entryName.replace('\\', '/');
        String prefix = LANGUAGE_ROOT + "/";
        String suffix = "/" + SERVER_LANG_FILE;
        if (!normalized.startsWith(prefix) || !normalized.endsWith(suffix)) {
            return null;
        }
        String language = normalized.substring(prefix.length(), normalized.length() - suffix.length());
        return language.isBlank() || language.contains("/") ? null : language;
    }

    // Match .jar/.zip mod archives; guard against null file names.
    private static boolean isArchive(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static List<Path> resolveModsDirectories(Path dataDirectory) {
        // Prefer global mods, then per-world overrides, then legacy locations.
        List<Path> modsDirs = new ArrayList<>();
        Path globalModsDir = resolveGlobalModsDirectory(dataDirectory);
        if (globalModsDir != null) {
            modsDirs.add(globalModsDir);
        }
        Path saveModsDir = resolveSaveModsDirectory(dataDirectory);
        if (saveModsDir != null && (globalModsDir == null || !saveModsDir.equals(globalModsDir))) {
            modsDirs.add(saveModsDir);
        }
        if (modsDirs.isEmpty()) {
            Path legacy = resolveModsDirectoryLegacy(dataDirectory);
            if (legacy != null) {
                modsDirs.add(legacy);
            }
        }
        return modsDirs;
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
