package com.alechilles.alecstamework.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards source manifest values that can prevent the packaged plugin from loading.
 */
class PluginManifestCompatibilityTest {

    private static final Pattern BARE_PATCH_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    @Test
    void serverVersionDoesNotUseBareNonZeroPatchRange() throws IOException {
        JsonObject manifest = readManifest();
        String serverVersion = manifest.get("ServerVersion").getAsString();
        Matcher matcher = BARE_PATCH_VERSION.matcher(serverVersion);

        assertFalse(
                matcher.matches() && !"0".equals(matcher.group(3)),
                "Hytale rejects bare non-zero patch ServerVersion ranges; use =, ^, or ~ instead."
        );
    }

    @Test
    void creditorEmbeddedDependencyIsPackagedWithoutReplacingPluginManifest() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(
                pom.contains("<id>cursemaven</id>")
                        && pom.contains("<artifactId>creditor-1560961</artifactId>")
                        && pom.contains("<version>${creditor.file.id}</version>"),
                "Creditor should be resolved from Cursemaven by CurseForge file id."
        );
        assertTrue(
                pom.contains("<include>curse.maven:creditor-1560961</include>"),
                "Creditor must be included in the shaded release jar for embedded mode."
        );
        assertTrue(
                pom.contains("<artifact>curse.maven:creditor-1560961</artifact>")
                        && pom.contains("<exclude>manifest.json</exclude>")
                        && pom.contains("<exclude>icon-256.png</exclude>"),
                "Creditor's root manifest.json and icon-256.png must be excluded so they cannot replace Tamework's roots."
        );

        JsonObject manifest = readManifest();
        assertEquals("Alechilles", manifest.get("Group").getAsString());
        assertEquals("Alec's Tamework!", manifest.get("Name").getAsString());
    }

    @Test
    void assetPackIconUsesRequiredRootNameAndSize() throws IOException {
        Path path = Path.of("src", "main", "resources", "icon-256.png");
        assertTrue(Files.isRegularFile(path), "Tamework's asset pack icon must be at the root beside manifest.json.");

        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image, "icon-256.png must be a readable PNG image.");
        assertEquals(256, image.getWidth(), "icon-256.png must be 256 pixels wide.");
        assertEquals(256, image.getHeight(), "icon-256.png must be 256 pixels high.");
    }

    private static JsonObject readManifest() throws IOException {
        Path path = Path.of("src", "main", "resources", "manifest.json");
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
