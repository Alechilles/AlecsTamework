package com.alechilles.alecstamework.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for player-facing feed trough interaction assets. */
class FeedTroughInteractionAssetTest {
    private static final Path TROUGH_ASSET = Path.of(
            "src/main/resources/Server/Item/Items/Tw_Feed_Trough.json"
    );
    private static final List<Path> LANGUAGE_FILES = List.of(
            Path.of("src/main/resources/Server/Languages/en-US/server.lang"),
            Path.of("src/main/resources/Server/Languages/de-DE/server.lang"),
            Path.of("src/main/resources/Server/Languages/fr-FR/server.lang"),
            Path.of("src/main/resources/Server/Languages/fr-CA/server.lang"),
            Path.of("src/main/resources/Server/Languages/pt-BR/server.lang")
    );

    @Test
    void waterTroughEmptyInteractionRequiresHoldTime() throws IOException {
        List<JsonObject> chargeInteractions = findChargingClearWaterInteractions(readTroughAsset());

        for (JsonObject chargeInteraction : chargeInteractions) {
            assertEquals("Charging", chargeInteraction.get("Type").getAsString());
            JsonObject next = chargeInteraction.getAsJsonObject("Next");
            JsonObject clearInteraction = next.getAsJsonObject("1.25");
            assertEquals("TameworkClearFeedTroughWater", clearInteraction.get("Type").getAsString());
            assertTrue(!clearInteraction.has("RunTime"), "Charging must own the hold timing so release can cancel.");
        }

        assertEquals(11, chargeInteractions.size());
    }

    @Test
    void emptyTroughHintUsesHoldWording() throws IOException {
        for (Path languageFile : LANGUAGE_FILES) {
            String value = readLanguageValue(languageFile, "interactionHints.emptyTrough");
            String lowerValue = value.toLowerCase(Locale.ROOT);

            assertTrue(
                    lowerValue.contains("hold")
                            || lowerValue.contains("halte")
                            || lowerValue.contains("maintenez")
                            || lowerValue.contains("segure"),
                    languageFile + " should describe the empty action as a hold"
            );
        }
    }

    private static JsonObject readTroughAsset() throws IOException {
        try (Reader reader = Files.newBufferedReader(TROUGH_ASSET, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<JsonObject> findChargingClearWaterInteractions(JsonElement element) {
        List<JsonObject> matches = new ArrayList<>();
        collectChargingClearWaterInteractions(element, matches);
        return matches;
    }

    private static void collectChargingClearWaterInteractions(JsonElement element, List<JsonObject> matches) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectChargingClearWaterInteractions(child, matches);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement type = object.get("Type");
        if (type != null && type.isJsonPrimitive()
                && "Charging".equals(type.getAsString())
                && hasClearWaterThreshold(object)) {
            matches.add(object);
        }
        for (JsonElement child : object.asMap().values()) {
            collectChargingClearWaterInteractions(child, matches);
        }
    }

    private static boolean hasClearWaterThreshold(JsonObject object) {
        if (!object.has("Next") || !object.get("Next").isJsonObject()) {
            return false;
        }
        JsonObject next = object.getAsJsonObject("Next");
        if (!next.has("1.25") || !next.get("1.25").isJsonObject()) {
            return false;
        }
        JsonObject threshold = next.getAsJsonObject("1.25");
        JsonElement type = threshold.get("Type");
        return type != null && type.isJsonPrimitive()
                && "TameworkClearFeedTroughWater".equals(type.getAsString());
    }

    private static String readLanguageValue(Path path, String key) throws IOException {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new AssertionError("Missing language key " + key + " in " + path);
    }
}
