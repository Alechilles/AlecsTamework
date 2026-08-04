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
        String build = Files.readString(Path.of("build.gradle"));

        assertTrue(
                build.contains("https://www.cursemaven.com")
                        && build.contains("curse.maven:creditor-1560961:${property('creditor_file_id')}"),
                "Creditor should be resolved from Cursemaven by CurseForge file id."
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

    /**
     * Guards the third-party API versions whose public contracts were verified for claim integrations.
     */
    @Test
    void claimIntegrationsUseVerifiedOptionalDependencyRanges() throws IOException {
        JsonObject manifest = readManifest();
        JsonObject dependencies = manifest.getAsJsonObject("Dependencies");
        JsonObject optionalDependencies = manifest.getAsJsonObject("OptionalDependencies");

        assertNotNull(dependencies, "The manifest must retain an explicit required-dependency object.");
        assertNotNull(optionalDependencies, "Claim integrations must be declared as optional dependencies.");
        assertTrue(optionalDependencies.has("Buuz135:SimpleClaims"), "SimpleClaims must remain declared.");
        assertTrue(
                optionalDependencies.has("net.evilcraft:QuestLinesClaims"),
                "QuestLines Claims must be declared under its verified plugin identifier."
        );
        assertEquals(
                ">=1.0.38 <1.1.0",
                optionalDependencies.get("Buuz135:SimpleClaims").getAsString(),
                "SimpleClaims must stay within the verified 1.0.38-compatible API line."
        );
        assertEquals(
                "=1.3.1",
                optionalDependencies.get("net.evilcraft:QuestLinesClaims").getAsString(),
                "QuestLines Claims must remain pinned to its verified 1.3.1 API contract."
        );
        assertFalse(
                dependencies.has("Buuz135:SimpleClaims")
                        || dependencies.has("net.evilcraft:QuestLinesClaims"),
                "Claims integrations must degrade gracefully when either external plugin is absent."
        );
    }

    private static JsonObject readManifest() throws IOException {
        Path path = Path.of("src", "main", "resources", "manifest.json");
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
