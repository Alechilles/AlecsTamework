package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TwDynamicIconConfigTest {
    @Test
    void codecResolvesOrderedVariantsAndDefault() {
        TwDynamicIconConfig config = decode("""
                {
                  "RoleIds": [" Tamed_Sheep "],
                  "IconDefault": "Icons/Sheep.png",
                  "IconOverrides": [
                    { "Icon": "Icons/Sheep-First.png", "Attachments": { "Fleece": "White" } },
                    { "Icon": "Icons/Sheep-Later.png", "Attachments": { "Fleece": "White" } },
                    { "Icon": "Icons/Sheep-Black.png", "Attachments": { "Fleece": "Black", "Horns": "None" } }
                  ]
                }
                """);

        assertEquals("Icons/Sheep-First.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(config), "tamed_sheep", Map.of("Fleece", "White", "Horns", "Curled")
        ));
        assertEquals("Icons/Sheep-Black.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(config), "TAMED_SHEEP", Map.of("Fleece", "Black", "Horns", "None", "Tail", "Long")
        ));
        assertEquals("Icons/Sheep.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(config), "Tamed_Sheep", Map.of("Fleece", "white")
        ));
        assertEquals("Icons/Sheep.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(config), "Tamed_Sheep", Map.of()
        ));
    }

    @Test
    void selectsOneEnabledConfigByPriorityThenAssetId() {
        TwDynamicIconConfig disabled = config("Mod:Disabled", false, 100, "Tamed_Cow", "Icons/Disabled.png");
        TwDynamicIconConfig lowerPriority = config("Mod:Low", true, 5, "Tamed_Cow", "Icons/Low.png");
        TwDynamicIconConfig firstById = config("mod:alpha", true, 10, "Tamed_Cow", "Icons/Alpha.png");
        TwDynamicIconConfig laterById = config("Mod:Zeta", true, 10, "Tamed_Cow", "Icons/Zeta.png");

        assertEquals("Icons/Alpha.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(disabled, lowerPriority, laterById, firstById), "  tamed_cow  ", Map.of()
        ));
    }

    @Test
    void returnsNullWhenNoEnabledConfigOrIconExists() {
        TwDynamicIconConfig disabled = config("Mod:Disabled", false, 0, "Tamed_Cow", "Icons/Disabled.png");
        TwDynamicIconConfig iconless = config("Mod:Iconless", true, 0, "Tamed_Horse", null);

        assertNull(TwDynamicIconConfig.resolveIconForTest(List.of(disabled), "Tamed_Cow", Map.of()));
        assertNull(TwDynamicIconConfig.resolveIconForTest(List.of(iconless), "Tamed_Horse", Map.of()));
        assertNull(TwDynamicIconConfig.resolveIconForTest(List.of(iconless), null, Map.of()));
    }

    @Test
    void omittedIconFieldsInheritWhileExplicitOverridesReplaceParentArray() {
        TwDynamicIconConfig parent = decode("""
                {
                  "RoleIds": ["Tamed_Goat"],
                  "IconDefault": "Icons/Goat.png",
                  "IconOverrides": [
                    { "Icon": "Icons/Goat-White.png", "Attachments": { "Coat": "White" } }
                  ]
                }
                """);
        TwDynamicIconConfig omitted = decode("{}");
        TwDynamicIconConfig explicitEmpty = decode("{ \"IconOverrides\": [] }");

        omitted.inheritMissingTopLevelFrom(parent, Set.of());
        explicitEmpty.inheritMissingTopLevelFrom(parent, Set.of("IconOverrides"));

        assertEquals("Icons/Goat-White.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(omitted), "Tamed_Goat", Map.of("Coat", "White")));
        assertEquals("Icons/Goat.png", TwDynamicIconConfig.resolveIconForTest(
                List.of(explicitEmpty), "Tamed_Goat", Map.of("Coat", "White")));
    }

    private static TwDynamicIconConfig decode(String json) {
        return TwDynamicIconConfig.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
    }

    private static TwDynamicIconConfig config(String id,
                                              boolean enabled,
                                              int priority,
                                              String roleId,
                                              String iconDefault) {
        TwDynamicIconConfig config = new TwDynamicIconConfig();
        config.setId(id);
        config.setEnabled(enabled);
        config.setPriority(priority);
        config.setRoleIds(new String[] { roleId });
        config.setIconDefault(iconDefault);
        return config;
    }
}
