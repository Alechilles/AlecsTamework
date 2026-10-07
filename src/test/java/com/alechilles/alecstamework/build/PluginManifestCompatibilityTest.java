package com.alechilles.alecstamework.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private static JsonObject readManifest() throws IOException {
        Path path = Path.of("src", "main", "resources", "manifest.json");
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
