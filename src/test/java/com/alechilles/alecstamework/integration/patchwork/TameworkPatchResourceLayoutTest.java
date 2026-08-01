package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Guards Tamework's bundled Patchwork definitions and host-macro contribution contract. */
final class TameworkPatchResourceLayoutTest {
    private static final Path NEUTRAL_ROOT = Path.of(
            "src/main/resources/Server/Patchwork/Patches");
    private static final Path LEGACY_ROOT = Path.of(
            "src/main/resources/Server/Tamework/Patches");

    @Test
    void bundlesFiveUniqueDefinitionsOnlyUnderTheNeutralRoot() throws Exception {
        List<Path> definitions;
        try (var files = Files.walk(NEUTRAL_ROOT)) {
            definitions = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(5, definitions.size());
        assertFalse(containsJson(LEGACY_ROOT));

        Set<String> ids = new HashSet<>();
        for (Path definition : definitions) {
            JsonObject object = JsonParser.parseString(Files.readString(definition)).getAsJsonObject();
            assertTrue(ids.add(object.get("Id").getAsString()), "Duplicate patch ID in " + definition);
        }
    }

    @Test
    void everyBundledTameworkMacroHasAContributionProvider() throws Exception {
        Set<String> usedMacros = new HashSet<>();
        try (var files = Files.walk(NEUTRAL_ROOT)) {
            for (Path definition : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                collectMacros(JsonParser.parseString(Files.readString(definition)), usedMacros);
            }
        }
        Set<String> providers = new TameworkPatchworkContribution("test").macroProviders().stream()
                .map(provider -> provider.macroId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertFalse(usedMacros.isEmpty());
        assertTrue(providers.containsAll(usedMacros), "Missing providers for " + usedMacros);
    }

    private static boolean containsJson(Path root) throws Exception {
        if (!Files.exists(root)) return false;
        try (var files = Files.walk(root)) {
            return files.anyMatch(path -> path.toString().endsWith(".json"));
        }
    }

    private static void collectMacros(JsonElement element, Set<String> macros) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("Macro") && object.get("Macro").isJsonPrimitive()) {
                macros.add(object.get("Macro").getAsString());
            }
            for (var entry : object.entrySet()) collectMacros(entry.getValue(), macros);
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectMacros(child, macros);
        }
    }
}
