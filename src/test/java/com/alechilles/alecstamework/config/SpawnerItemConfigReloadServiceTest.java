package com.alechilles.alecstamework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class SpawnerItemConfigReloadServiceTest {
    @Test
    void tameAndCommandLinkRejectsContradictoryExplicitFilledItem() throws Exception {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        SpawnerItemConfigReloadService service = service(registry, Map.of("Stone", 64));
        TwSpawnerConfig config = config("DragonStone", """
                "EmptyItemId":"Stone",
                "FilledItemId":"FilledStone",
                "Capture":{
                  "RequireTamed":false,
                  "TamesTarget":true,
                  "TamedRoleOverrides":{"WildDragon":"TamedDragon"},
                  "SuccessDisposition":"TameAndCommandLink",
                  "CommandFamilyId":"hydragon:dragon_horn"
                }
                """);

        SpawnerItemConfigReloadService.ReloadResult result = service.reload(List.of(config));

        assertFalse(result.applied());
        assertEquals(List.of(
                "capture-tame-link-contradicts-explicit-filled-item:DragonStone"),
                result.errors());
    }

    @Test
    void tameAndCommandLinkAcceptsInheritedFilledItemBecauseDispositionIgnoresIt() throws Exception {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        SpawnerItemConfigReloadService service = service(registry, Map.of("Stone", 64));
        TwSpawnerConfig parent = config("Parent", """
                "EmptyItemId":"ParentStone", "FilledItemId":"FilledStone"
                """);
        TwSpawnerConfig child = config("DragonStone", """
                "EmptyItemId":"Stone",
                "Capture":{
                  "RequireTamed":false,
                  "TamesTarget":true,
                  "TamedRoleOverrides":{"WildDragon":"TamedDragon"},
                  "SuccessDisposition":"TameAndCommandLink",
                  "CommandFamilyId":"hydragon:dragon_horn"
                }
                """);
        child.inheritMissingTopLevelFrom(parent, Set.of("EmptyItemId", "Capture"),
                Map.of("Capture", Set.of("RequireTamed", "TamesTarget", "TamedRoleOverrides",
                        "SuccessDisposition", "CommandFamilyId")));

        SpawnerItemConfigReloadService.ReloadResult result = service.reload(List.of(child));

        assertTrue(result.applied());
        assertEquals("FilledStone", registry.get("Stone").getSpawnerFilledItemId());
    }

    private static SpawnerItemConfigReloadService service(ItemFeatureRegistry registry,
                                                          Map<String, Integer> items) {
        return new SpawnerItemConfigReloadService(registry, itemId -> {
            Integer maxStack = items.get(itemId);
            return maxStack == null ? OptionalInt.empty() : OptionalInt.of(maxStack);
        });
    }

    private static TwSpawnerConfig config(String id, String entries) throws Exception {
        TwSpawnerConfig config = TwSpawnerConfig.CODEC.decode(
                BsonDocument.parse("{" + entries + "}"), new ExtraInfo());
        Field field = TwSpawnerConfig.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(config, id);
        return config;
    }
}
