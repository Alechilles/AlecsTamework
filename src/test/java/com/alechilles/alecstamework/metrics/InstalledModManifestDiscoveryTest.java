package com.alechilles.alecstamework.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledModManifestDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversAndDeduplicatesByHighestVersion() throws Exception {
        Path userData = tempDir.resolve("UserData");
        Path globalMods = userData.resolve("Mods");
        Files.createDirectories(globalMods);

        writeFolderManifest(
                globalMods.resolve("CatsFolder"),
                "Alechilles",
                "Alec's Cats!",
                "1.5.5",
                true
        );
        writeArchiveManifest(
                globalMods.resolve("CatsPack.zip"),
                "Alechilles",
                "Alec's Cats!",
                "1.5.6",
                true
        );
        writeArchiveManifest(
                globalMods.resolve("NametagsPack.zip"),
                "Alechilles",
                "Alec's Nametags!",
                "1.1.2",
                true
        );
        writeFolderManifest(
                globalMods.resolve("Unrelated"),
                "Example",
                "Other Mod",
                "0.1.0",
                false
        );

        Path dataDirectory = userData.resolve("Saves")
                .resolve("WorldOne")
                .resolve("mods")
                .resolve("Alec's Tamework!");
        Files.createDirectories(dataDirectory);

        InstalledModManifestDiscovery discovery = new InstalledModManifestDiscovery(null);
        List<InstalledModManifest> manifests = discovery.discover(dataDirectory);

        Map<String, InstalledModManifest> byId = manifests.stream()
                .collect(java.util.stream.Collectors.toMap(InstalledModManifest::modId, manifest -> manifest));

        InstalledModManifest cats = byId.get("Alechilles:Alec's Cats!");
        assertNotNull(cats);
        assertEquals("1.5.6", cats.version());
        assertTrue(cats.dependsOnTamework());

        InstalledModManifest nametags = byId.get("Alechilles:Alec's Nametags!");
        assertNotNull(nametags);
        assertEquals("1.1.2", nametags.version());
        assertTrue(nametags.dependsOnTamework());
    }

    @Test
    void discoversLoadedWorldManifestsFromSaveModsDirectoryFirst() throws Exception {
        Path userData = tempDir.resolve("UserData");
        Path globalMods = userData.resolve("Mods");
        Files.createDirectories(globalMods);

        writeArchiveManifest(
                globalMods.resolve("CoopsPack.zip"),
                "Alechilles",
                "Alec's Coops!",
                "1.0.0",
                true
        );

        Path worldMods = userData.resolve("Saves")
                .resolve("WorldOne")
                .resolve("mods");
        Files.createDirectories(worldMods);

        writeArchiveManifest(
                worldMods.resolve("AnimalHusbandryPack.zip"),
                "Alechilles",
                "Alec's Animal Husbandry!",
                "1.3.3",
                true
        );

        Path dataDirectory = worldMods.resolve("Alec's Tamework!");
        Files.createDirectories(dataDirectory);

        InstalledModManifestDiscovery discovery = new InstalledModManifestDiscovery(null);
        List<InstalledModManifest> manifests = discovery.discoverLoadedWorldManifests(dataDirectory);

        assertEquals(1, manifests.size());
        assertEquals("Alechilles:Alec's Animal Husbandry!", manifests.get(0).modId());
    }

    private static void writeFolderManifest(Path folder, String group, String name, String version, boolean dependsOnTamework)
            throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("manifest.json"), manifestJson(group, name, version, dependsOnTamework));
    }

    private static void writeArchiveManifest(Path archive, String group, String name, String version, boolean dependsOnTamework)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            writer.write(manifestJson(group, name, version, dependsOnTamework));
            writer.flush();
            zip.closeEntry();
        }
    }

    private static String manifestJson(String group, String name, String version, boolean dependsOnTamework) {
        String dependencies = dependsOnTamework
                ? "{ \"Alechilles:Alec's Tamework!\": \"2.x\" }"
                : "{}";
        return "{\n"
                + "  \"Group\": \"" + group + "\",\n"
                + "  \"Name\": \"" + name + "\",\n"
                + "  \"Version\": \"" + version + "\",\n"
                + "  \"Dependencies\": " + dependencies + ",\n"
                + "  \"OptionalDependencies\": {}\n"
                + "}";
    }
}
