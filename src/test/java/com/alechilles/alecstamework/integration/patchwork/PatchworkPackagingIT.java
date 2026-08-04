package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Verifies that Tamework shades its bundled Patchwork runtime without publishing it as a plugin.
 */
class PatchworkPackagingIT {
    private static final String EMBEDDED_BOOTSTRAP_CLASS =
            "com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.class";

    @Test
    void shadedJarEmbedsPatchworkWithoutAdvertisingASecondPlugin() throws IOException {
        Path packagedJar = Path.of(System.getProperty("patchwork.packagedJar"));
        assertTrue(Files.isRegularFile(packagedJar), () -> "Expected packaged jar at " + packagedJar);

        try (ZipFile jar = new ZipFile(packagedJar.toFile())) {
            assertEquals(
                    1,
                    jar.stream().map(entry -> entry.getName())
                            .filter(EMBEDDED_BOOTSTRAP_CLASS::equals).count(),
                    "The shaded jar must contain exactly one embedded Patchwork bootstrap."
            );
            assertEquals(
                    1,
                    jar.stream().map(entry -> entry.getName())
                            .filter("manifest.json"::equals).count(),
                    "The shaded jar must contain exactly one root plugin manifest."
            );
            assertTameworkManifest(jar);
            assertTrue(jar.getEntry("com/alechilles/patchwork/standalone/PatchworkPlugin.class") != null,
                    "The shaded jar must contain Patchwork's runtime classes.");
            assertFalse(jar.getEntry("manifests.json") != null,
                    "Tamework must not advertise its shaded Patchwork runtime as a second plugin.");
        }
    }

    private static void assertTameworkManifest(ZipFile jar) throws IOException {
        JsonObject manifest = readManifest(jar, jar.getEntry("manifest.json"));
        assertEquals("Alechilles", manifest.get("Group").getAsString());
        assertEquals("Alec's Tamework!", manifest.get("Name").getAsString());
        assertEquals("com.alechilles.alecstamework.Tamework", manifest.get("Main").getAsString());
    }

    private static JsonObject readManifest(ZipFile jar, ZipEntry entry) throws IOException {
        try (var input = jar.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
