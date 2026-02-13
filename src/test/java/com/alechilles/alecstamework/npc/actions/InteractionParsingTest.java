package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InteractionParsingTest {

    @Test
    void parseItemIdsFromJsonHandlesStringsAndObjects() throws Exception {
        ActionTameworkInteract interact = newInteract();
        String json = "[\"ItemA\", {\"Item\": \"ItemB\"}, {\"item\": \"ItemC\"}, null, \"\"]";
        String[] result = invokeParseItemIdsFromJson(interact, json);
        assertArrayEquals(new String[] { "ItemA", "ItemB", "ItemC" }, result);
    }

    @Test
    void parseItemIdsFromParamHandlesJsonArrayString() throws Exception {
        ActionTameworkInteract interact = newInteract();
        String[] result = invokeParseItemIdsFromParam(interact, new String[] { "[\"ItemA\", \"ItemB\"]" });
        assertArrayEquals(new String[] { "ItemA", "ItemB" }, result);
    }

    @Test
    void parseItemIdsFromParamFiltersBlanks() throws Exception {
        ActionTameworkInteract interact = newInteract();
        String[] result = invokeParseItemIdsFromParam(interact, new String[] { "", "ItemA", "  ", "ItemB" });
        assertArrayEquals(new String[] { "ItemA", "ItemB" }, result);
    }

    @Test
    void parseFeedItemsFromJsonSupportsStringAndHealOverrides() throws Exception {
        ActionTameworkInteract interact = newInteract();
        String json = "[\"ItemA\", {\"Item\": \"ItemB\", \"Heal\": 4}]";
        FeedItem[] items = invokeParseFeedItemsFromJson(interact, json);
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
        TwInteractionConfig config = new TwInteractionConfig();
        Field idField = TwInteractionConfig.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(config, "My Config#1");

        Method method = ActionTameworkInteract.class.getDeclaredMethod(
                "buildCooldownAlarmName",
                TwInteractionConfig.class,
                int.class
        );
        method.setAccessible(true);
        String alarm = (String) method.invoke(interact, config, 2);
        assertEquals("TameworkInteract_Cooldown_My_Config_1_2", alarm);
    }

    private static String[] invokeParseItemIdsFromParam(ActionTameworkInteract interact, String[] values)
            throws Exception {
        Method method = ActionTameworkInteract.class.getDeclaredMethod("parseItemIdsFromParam", String[].class);
        method.setAccessible(true);
        return (String[]) method.invoke(interact, (Object) values);
    }

    private static String[] invokeParseItemIdsFromJson(ActionTameworkInteract interact, String json)
            throws Exception {
        Method method = ActionTameworkInteract.class.getDeclaredMethod("parseItemIdsFromJson", String.class);
        method.setAccessible(true);
        return (String[]) method.invoke(interact, json);
    }

    private static FeedItem[] invokeParseFeedItemsFromJson(ActionTameworkInteract interact, String json)
            throws Exception {
        Method method = ActionTameworkInteract.class.getDeclaredMethod("parseFeedItemsFromJson", String.class);
        method.setAccessible(true);
        return (FeedItem[]) method.invoke(interact, json);
    }

    private static ActionTameworkInteract newInteract() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (ActionTameworkInteract) unsafe.allocateInstance(ActionTameworkInteract.class);
    }
}
