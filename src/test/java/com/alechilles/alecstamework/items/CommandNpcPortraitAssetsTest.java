package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandNpcPortraitAssetsTest {
    @Test
    void sharedCaptureImagesProduceReusableClientIconsWithoutGenericLantern() {
        String sheep = "Icons/ItemsGenerated/Sheep.png";
        var config = ItemFeatureConfig.builder()
                .spawnerIconDefault("Icons/Lantern.png")
                .spawnerIconOverridesByRole(Map.of("Sheep", List.of(
                        new ItemFeatureConfig.SpawnerIconOverride(Map.of("Coat", "White"), sheep))))
                .spawnerIconOverrideGroups(List.of(new ItemFeatureConfig.SpawnerIconOverrideGroup(
                        List.of("Sheep", "Tamed_Sheep"), List.of(), sheep)))
                .build();
        List<Item> aliases = CommandNpcPortraitAssets.missingAliases(List.of(config, config), Map.of());
        assertEquals(List.of(sheep), aliases.stream().map(item -> item.toPacket().icon).toList());
        var packet = aliases.getFirst().toPacket();
        assertTrue(packet.variant);
        assertTrue(packet.categories == null || packet.categories.length == 0);
        Map<String, Item> registered = new HashMap<>();
        for (Item alias : aliases) registered.put(alias.getId(), alias);
        assertTrue(CommandNpcPortraitAssets.missingAliases(List.of(config), registered).isEmpty());
    }
}
