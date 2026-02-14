package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests for Interaction parsing. */
class InteractionParsingTest {

    @Test
    void parseItemIdsFromJsonHandlesStringsAndObjects() throws Exception {
        String json = "[\"ItemA\", {\"Item\": \"ItemB\"}, {\"item\": \"ItemC\"}, null, \"\"]";
        String[] result = InteractionItemParser.parseItemIdsFromJson(json);
        assertArrayEquals(new String[] { "ItemA", "ItemB", "ItemC" }, result);
    }

    @Test
    void parseItemIdsFromJsonIgnoresMissingItemFields() throws Exception {
        String json = "[{}, {\"Item\": \"\"}, {\"item\": \"ItemA\"}]";
        String[] result = InteractionItemParser.parseItemIdsFromJson(json);
        assertArrayEquals(new String[] { "ItemA" }, result);
    }

    @Test
    void parseItemIdsFromParamHandlesJsonArrayString() throws Exception {
        String[] result = InteractionItemParser.parseItemIdsFromParam(new String[] { "[\"ItemA\", \"ItemB\"]" });
        assertArrayEquals(new String[] { "ItemA", "ItemB" }, result);
    }

    @Test
    void parseItemIdsFromParamFiltersBlanks() throws Exception {
        String[] result = InteractionItemParser.parseItemIdsFromParam(new String[] { "", "ItemA", "  ", "ItemB" });
        assertArrayEquals(new String[] { "ItemA", "ItemB" }, result);
    }

    @Test
    void parseFeedItemsFromJsonSupportsStringAndHealOverrides() throws Exception {
        String json = "[\"ItemA\", {\"Item\": \"ItemB\", \"Heal\": 4}]";
        FeedItem[] items = InteractionItemParser.parseFeedItemsFromJson(json);
        assertNotNull(items);
        assertEquals(2, items.length);
        assertEquals("ItemA", items[0].getItem());
        assertNull(items[0].getHeal());
        assertEquals("ItemB", items[1].getItem());
        assertEquals(4.0, items[1].getHeal());
    }

    @Test
    void buildCooldownAlarmNameSanitizesId() throws Exception {
        ActionTameworkInteract interact = newInteract();
        TwInteractionConfig config = newInteractionConfig();
        Field idField = TwInteractionConfig.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(config, "My Config#1");

        InteractionCooldowns cooldowns = new InteractionCooldowns(
                interact,
                "TameworkInteract_Cooldown"
        );
        String alarm = cooldowns.buildCooldownAlarmName(config, 2);
        assertEquals("TameworkInteract_Cooldown_My_Config_1_2", alarm);
    }

    @Test
    void buildCooldownAlarmNameUsesUnknownWhenIdMissing() throws Exception {
        ActionTameworkInteract interact = newInteract();
        TwInteractionConfig config = newInteractionConfig();

        InteractionCooldowns cooldowns = new InteractionCooldowns(
                interact,
                "TameworkInteract_Cooldown"
        );
        String alarm = cooldowns.buildCooldownAlarmName(config, 1);
        assertEquals("TameworkInteract_Cooldown_unknown_1", alarm);
    }

    private static ActionTameworkInteract newInteract() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (ActionTameworkInteract) unsafe.allocateInstance(ActionTameworkInteract.class);
    }

    private static TwInteractionConfig newInteractionConfig() throws Exception {
        var ctor = TwInteractionConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }
}
