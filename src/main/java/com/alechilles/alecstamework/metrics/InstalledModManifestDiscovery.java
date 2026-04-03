package com.alechilles.alecstamework.metrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers installed mod manifests from folder and archive mod layouts.
 */
public final class InstalledModManifestDiscovery {

    private static final String MANIFEST_PATH = "manifest.json";
    private static final String TAMEWORK_MOD_ID = "Alechilles:Alec's Tamework!";

    private final HytaleLogger logger;

    public InstalledModManifestDiscovery(HytaleLogger logger) {
        this.logger = logger;
    }

    public List<InstalledModManifest> discover(Path dataDirectory) {
        List<Path> modsDirs = resolveModsDirectories(dataDirectory);
        return discoverFromDirectories(modsDirs);
    }

    /**
     * Discovers manifests from the currently loaded world mods directory when available.
     *
     * <p>This intentionally avoids the global mods folder when world-scoped mods exist so
     * startup guards can reason about what is actually enabled for the running world.
     */
    public List<InstalledModManifest> discoverLoadedWorldManifests(Path dataDirectory) {
        Path saveModsDir = resolveSaveModsDirectory(dataDirectory);
        if (saveModsDir != null) {
            return discoverFromDirectories(List.of(saveModsDir));
        }
        Path globalModsDir = resolveGlobalModsDirectory(dataDirectory);
        if (globalModsDir != null) {
            return discoverFromDirectories(List.of(globalModsDir));
        }
        return List.of();
    }

    private List<InstalledModManifest> discoverFromDirectories(List<Path> modsDirs) {
        Map<String, InstalledModManifest> manifestsByModId = new LinkedHashMap<>();
        for (Path modsDir : modsDirs) {
            loadFromModsDirectory(modsDir, manifestsByModId);
        }
        return new ArrayList<>(manifestsByModId.values());
    }

    private void loadFromModsDirectory(Path modsDir, Map<String, InstalledModManifest> manifestsByModId) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (Exception ex) {
            warn("Tamework HStats forwarding: failed to scan mods directory " + modsDir, ex);
            return;
        }

        entries.sort(Comparator.comparing(path -> {
            Path fileName = path.getFileName();
            return fileName == null ? "" : fileName.toString().toLowerCase();
        }));

        for (Path entry : entries) {
            InstalledModManifest manifest = null;
            try {
                if (Files.isDirectory(entry)) {
                    manifest = loadFromModFolder(entry);
                } else if (isArchive(entry)) {
                    manifest = loadFromModArchive(entry);
                }
            } catch (Exception ex) {
                warn("Tamework HStats forwarding: failed to inspect mod entry " + entry, ex);
            }
            if (manifest == null) {
                continue;
            }
            String modId = manifest.modId();
            InstalledModManifest current = manifestsByModId.get(modId);
            manifestsByModId.put(modId, choosePreferred(current, manifest));
        }
    }

    private InstalledModManifest loadFromModFolder(Path modFolder) {
        Path manifestPath = modFolder.resolve(MANIFEST_PATH);
        if (!Files.exists(manifestPath)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            return parseManifest(reader, modFolder, safeLastModified(manifestPath));
        } catch (Exception ex) {
            warn("Tamework HStats forwarding: failed to read mod manifest " + manifestPath, ex);
            return null;
        }
    }

    private InstalledModManifest loadFromModArchive(Path modArchive) {
        try (ZipFile zipFile = new ZipFile(modArchive.toFile())) {
            ZipEntry entry = zipFile.getEntry(MANIFEST_PATH);
            if (entry == null) {
                return null;
            }
            try (InputStream stream = zipFile.getInputStream(entry);
                 Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parseManifest(reader, modArchive, safeLastModified(modArchive));
            }
        } catch (Exception ex) {
            warn("Tamework HStats forwarding: failed to read archive manifest " + modArchive, ex);
            return null;
        }
    }

    private InstalledModManifest parseManifest(Reader reader, Path sourcePath, long sourceLastModifiedMillis) {
        JsonElement parsed = JsonParser.parseReader(reader);
        if (parsed == null || !parsed.isJsonObject()) {
            return null;
        }
        JsonObject object = parsed.getAsJsonObject();
        String group = getString(object, "Group");
        String name = getString(object, "Name");
        String version = getString(object, "Version");
        if (isBlank(group) || isBlank(name) || isBlank(version)) {
            return null;
        }
        boolean dependsOnTamework = hasDependency(object, "Dependencies", TAMEWORK_MOD_ID)
                || hasDependency(object, "OptionalDependencies", TAMEWORK_MOD_ID);

        return new InstalledModManifest(
                group.trim(),
                name.trim(),
                version.trim(),
                dependsOnTamework,
                sourcePath.toAbsolutePath().normalize(),
                sourceLastModifiedMillis
        );
    }

    private InstalledModManifest choosePreferred(InstalledModManifest current, InstalledModManifest candidate) {
        if (current == null) {
            return candidate;
        }
        int semverCompare = compareVersions(candidate.version(), current.version());
        if (semverCompare > 0) {
            return candidate;
        }
        if (semverCompare < 0) {
            return current;
        }
        if (candidate.sourceLastModifiedMillis() > current.sourceLastModifiedMillis()) {
            return candidate;
        }
        return current;
    }

    private static int compareVersions(String left, String right) {
        Semver leftSemver = parseSemver(left);
        Semver rightSemver = parseSemver(right);
        if (leftSemver != null && rightSemver != null) {
            return leftSemver.compareTo(rightSemver);
        }
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static Semver parseSemver(String version) {
        if (isBlank(version)) {
            return null;
        }
        try {
            return Semver.fromString(version.trim(), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasDependency(JsonObject root, String key, String dependencyId) {
        JsonObject object = getObject(root, key);
        if (object == null || isBlank(dependencyId)) {
            return false;
        }
        if (object.has(dependencyId)) {
            return true;
        }
        for (String dependencyKey : object.keySet()) {
            if (dependencyId.equalsIgnoreCase(dependencyKey)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject getObject(JsonObject root, String key) {
        if (root == null || isBlank(key) || !root.has(key)) {
            return null;
        }
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private static String getString(JsonObject root, String key) {
        if (root == null || isBlank(key) || !root.has(key)) {
            return null;
        }
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long safeLastModified(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean isArchive(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private List<Path> resolveModsDirectories(Path dataDirectory) {
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

    private void warn(String message, Exception ex) {
        if (logger == null) {
            return;
        }
        logger.at(Level.WARNING).withCause(ex).log(message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
