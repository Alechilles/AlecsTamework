package com.alechilles.alecstamework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerVesselConfigResolver;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class SpawnerItemConfigReloadServiceTest {
    @Test
    void validBondedGenerationIndexesEveryExactConfiguredAndFallbackItemId() throws Exception {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        Map<String, Integer> items = new HashMap<>();
        for (String itemId : List.of("PlainOrb", "RestingOrb", "AwakeOrb", "CrackedOrb")) {
            items.put(itemId, 1);
        }
        SpawnerItemConfigReloadService service = service(registry, items);
        TwSpawnerConfig config = bonded("DragonVessel", "PlainOrb", "RestingOrb",
                "\"Active\":\"AwakeOrb\",\"Dead\":\"CrackedOrb\"");

        SpawnerItemConfigReloadService.ReloadResult result = service.reload(List.of(config));
        TwSpawnerVesselConfigResolver resolver = new TwSpawnerVesselConfigResolver(registry);

        assertTrue(result.applied());
        assertEquals(1L, result.activeRevision());
        assertEquals("DragonVessel", resolver.resolveForItemId("PlainOrb").orElseThrow().configId());
        assertEquals("DragonVessel", resolver.resolveForItemId("RestingOrb").orElseThrow().configId());
        assertEquals("DragonVessel", resolver.resolveForItemId("AwakeOrb").orElseThrow().configId());
        assertEquals("DragonVessel", resolver.resolveForItemId("CrackedOrb").orElseThrow().configId());
        // Lost and unavailable both fall back to the filled item and therefore resolve directly.
        assertEquals("RestingOrb", resolver.getById("DragonVessel").orElseThrow().lostItemId());
        assertEquals("RestingOrb", resolver.getById("DragonVessel").orElseThrow().unavailableItemId());
        assertTrue(resolver.resolveForItemId("RestingOrb").isPresent());
        assertTrue(resolver.resolveForItemId("Anything_State_Awake").isEmpty());
    }

    @Test
    void failedProductionValidationRetainsTheCompleteLastValidGeneration() throws Exception {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        Map<String, Integer> items = new HashMap<>(Map.of(
                "PlainOrb", 1, "RestingOrb", 1, "AwakeOrb", 1, "CrackedOrb", 1));
        SpawnerItemConfigReloadService service = service(registry, items);
        TwSpawnerConfig initial = bonded("DragonVessel", "PlainOrb", "RestingOrb",
                "\"Active\":\"AwakeOrb\",\"Dead\":\"CrackedOrb\"");
        assertTrue(service.reload(List.of(initial)).applied());
        long validRevision = registry.revision();
        ItemFeatureConfig validFeature = registry.get("PlainOrb");

        items.put("CrackedOrb", 2);
        SpawnerItemConfigReloadService.ReloadResult rejected = service.reload(List.of(initial));

        assertFalse(rejected.applied());
        assertTrue(rejected.errors().contains(
                "vessel-item-must-be-non-stackable:DragonVessel:CrackedOrb"));
        assertEquals(validRevision, registry.revision());
        assertEquals(validFeature, registry.get("PlainOrb"));
        assertEquals("DragonVessel", registry.resolveVesselForItemId("CrackedOrb")
                .orElseThrow().configId());
        assertEquals(validRevision, registry.getCaptureByConfigId("DragonVessel")
                .orElseThrow().configRevision());
    }

    @Test
    void duplicateEmptyBindingsRejectDeterministicallyWithoutPublishingAPartialMap() throws Exception {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        Map<String, Integer> items = new HashMap<>(Map.of("SharedOrb", 1, "FirstStored", 1,
                "SecondStored", 1));
        SpawnerItemConfigReloadService service = service(registry, items);
        TwSpawnerConfig alpha = bonded("Alpha", "SharedOrb", "FirstStored", "");
        TwSpawnerConfig beta = bonded("Beta", "SharedOrb", "SecondStored", "");

        SpawnerItemConfigReloadService.ReloadResult forward = service.reload(List.of(beta, alpha));
        SpawnerItemConfigReloadService.ReloadResult reverse = service.reload(List.of(alpha, beta));

        assertFalse(forward.applied());
        assertEquals(forward.errors(), reverse.errors());
        assertEquals(List.of("spawner-empty-item-collision:SharedOrb:Alpha:Beta"), forward.errors());
        assertEquals(0L, registry.revision());
        assertTrue(registry.snapshot().isEmpty());
    }

    private static SpawnerItemConfigReloadService service(ItemFeatureRegistry registry,
                                                          Map<String, Integer> items) {
        return new SpawnerItemConfigReloadService(registry, itemId -> {
            Integer maxStack = items.get(itemId);
            return maxStack == null ? OptionalInt.empty() : OptionalInt.of(maxStack);
        });
    }

    private static TwSpawnerConfig bonded(String id,
                                           String emptyItemId,
                                           String filledItemId,
                                           String stateEntries) throws Exception {
        String states = stateEntries.isBlank() ? "" : ",\"StateItemIds\":{" + stateEntries + "}";
        TwSpawnerConfig config = TwSpawnerConfig.CODEC.decode(BsonDocument.parse("{"
                + "\"EmptyItemId\":\"" + emptyItemId + "\","
                + "\"FilledItemId\":\"" + filledItemId + "\","
                + "\"Vessel\":{\"Mode\":\"Bonded\"" + states + "}}"), new ExtraInfo());
        Field field = TwSpawnerConfig.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(config, id);
        return config;
    }
}
