package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Verifies that Tamework exposes its bundled Patchwork runtime to dependent mods.
 */
class PatchworkPackagingIT {
    private static final String EMBEDDED_BOOTSTRAP_CLASS =
            "com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.class";

    @Test
    void shadedJarExposesEmbeddedPatchworkAsAPluginDependency() throws IOException {
        Path packagedJar = Path.of(System.getProperty("patchwork.packagedJar"));
        assertTrue(Files.isRegularFile(packagedJar), () -> "Expected packaged jar at " + packagedJar);

        try (ZipFile jar = new ZipFile(packagedJar.toFile())) {
            List<String> entries = jar.stream().map(entry -> entry.getName()).toList();

            assertEquals(
                    1,
                    entries.stream().filter(EMBEDDED_BOOTSTRAP_CLASS::equals).count(),
                    "The shaded jar must contain exactly one embedded Patchwork bootstrap."
            );
            assertEquals(
                    1,
                    entries.stream().filter("manifest.json"::equals).count(),
                    "The shaded jar must contain exactly one root plugin manifest."
            );
            assertTameworkManifest(jar);
            assertPatchworkDependencyManifest(jar, entries);
        }
    }

    private static void assertTameworkManifest(ZipFile jar) throws IOException {
        JsonObject manifest = readManifest(jar, jar.getEntry("manifest.json"));
        assertEquals("Alechilles", manifest.get("Group").getAsString());
        assertEquals("Alec's Tamework!", manifest.get("Name").getAsString());
        assertEquals("com.alechilles.alecstamework.Tamework", manifest.get("Main").getAsString());
    }

    /** Protects dependent mods that declare Alechilles:Patchwork while only Tamework is installed. */
    private static void assertPatchworkDependencyManifest(ZipFile jar, List<String> entries) throws IOException {
        assertTrue(entries.contains("com/alechilles/patchwork/standalone/PatchworkPlugin.class"),
                "The bundled Patchwork plugin entry point must be packaged.");
        ZipEntry bundleEntry = jar.getEntry("manifests.json");
        assertTrue(bundleEntry != null,
                "The bundled Patchwork plugin must be advertised through manifests.json for dependency resolution.");

        JsonArray manifests;
        try (var input = jar.getInputStream(bundleEntry)) {
            manifests = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonArray();
        }
        JsonObject patchwork = manifests.asList().stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .filter(manifest -> "Alechilles".equals(manifest.get("Group").getAsString())
                        && "Patchwork".equals(manifest.get("Name").getAsString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("manifests.json must expose Alechilles:Patchwork."));
        assertEquals("com.alechilles.patchwork.standalone.PatchworkPlugin", patchwork.get("Main").getAsString());
    }

    private static JsonObject readManifest(ZipFile jar, ZipEntry entry) throws IOException {
        try (var input = jar.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
