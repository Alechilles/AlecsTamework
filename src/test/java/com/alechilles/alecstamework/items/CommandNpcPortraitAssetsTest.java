package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandNpcPortraitAssetsTest {
    @Test
    void standaloneImagesProduceReusableClientIconsWithoutCaptureItems() throws Exception {
        String sheep = "Icons/ItemsGenerated/Sheep.png";
        var config = DynamicIconTestAssets.config("""
                {"RoleIds":["Sheep"],"IconDefault":"Icons/ItemsGenerated/Sheep.png",
                 "IconOverrides":[{"Icon":"Icons/ItemsGenerated/Sheep.png","Attachments":{"Coat":"White"}}]}
                """);
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
