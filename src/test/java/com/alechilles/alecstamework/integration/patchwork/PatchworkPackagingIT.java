package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Verifies that Tamework packages the embedded Patchwork runtime without a second plugin payload.
 */
class PatchworkPackagingIT {
    private static final String EMBEDDED_BOOTSTRAP_CLASS =
            "com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.class";

    @Test
    void shadedJarContainsOneEmbeddedRuntimeAndNoStandalonePatchworkPlugin() throws IOException {
        Path packagedJar = Path.of(System.getProperty("patchwork.packagedJar"));
        assertTrue(Files.isRegularFile(packagedJar), () -> "Expected packaged jar at " + packagedJar);

        try (ZipFile jar = new ZipFile(packagedJar.toFile())) {
            List<String> entries = jar.stream().map(entry -> entry.getName()).toList();

            assertEquals(
                    1,
                    entries.stream().filter(EMBEDDED_BOOTSTRAP_CLASS::equals).count(),
                    "The shaded jar must contain exactly one embedded Patchwork bootstrap."
            );
            assertFalse(
                    entries.stream().anyMatch(entry -> entry.endsWith("/PatchworkPlugin.class")
                            || entry.equals("PatchworkPlugin.class")),
                    "The shaded jar must not contain Patchwork's standalone plugin entry point."
            );
            assertEquals(
                    1,
                    entries.stream().filter("manifest.json"::equals).count(),
                    "The shaded jar must contain exactly one root plugin manifest."
            );
            assertTameworkManifest(jar);
            assertFalse(
                    jar.stream().filter(PatchworkPackagingIT::isManifest).anyMatch(entry -> isPatchworkManifest(jar, entry)),
                    "The shaded jar must not contain Patchwork's standalone plugin manifest."
            );
        }
    }

    private static void assertTameworkManifest(ZipFile jar) throws IOException {
        JsonObject manifest = readManifest(jar, jar.getEntry("manifest.json"));
        assertEquals("Alechilles", manifest.get("Group").getAsString());
        assertEquals("Alec's Tamework!", manifest.get("Name").getAsString());
        assertEquals("com.alechilles.alecstamework.Tamework", manifest.get("Main").getAsString());
    }

    private static boolean isManifest(ZipEntry entry) {
        return entry.getName().equals("manifest.json") || entry.getName().endsWith("/manifest.json");
    }

    private static boolean isPatchworkManifest(ZipFile jar, ZipEntry entry) {
        try {
            JsonObject manifest = readManifest(jar, entry);
            return manifest.has("Main")
                    && "com.alechilles.patchwork.standalone.PatchworkPlugin"
                    .equals(manifest.get("Main").getAsString());
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private static JsonObject readManifest(ZipFile jar, ZipEntry entry) throws IOException {
        try (var input = jar.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
