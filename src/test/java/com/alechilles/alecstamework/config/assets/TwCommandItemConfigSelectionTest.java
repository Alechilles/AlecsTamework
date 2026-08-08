package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests command lookup and cycling behavior for TwCommandItemConfig. */
class TwCommandItemConfigSelectionTest {

    @Test
    void findNextCommandStartsAtFirstWhenNoSelection() throws Exception {
        TwCommandItemConfig config = configWithCommands("Follow", "Hold", "AttackTarget");

        TwCommandItemConfig.CommandEntry first = config.findNextCommand(null);

        assertNotNull(first);
        assertEquals("Follow", first.getId());
    }

    @Test
    void findNextCommandAdvancesAndWraps() throws Exception {
        TwCommandItemConfig config = configWithCommands("Follow", "Hold", "AttackTarget");

        assertEquals("Hold", config.findNextCommand("Follow").getId());
        assertEquals("AttackTarget", config.findNextCommand("Hold").getId());
        assertEquals("Follow", config.findNextCommand("AttackTarget").getId());
    }

    @Test
    void findNextCommandIgnoresInvalidEntries() throws Exception {
        TwCommandItemConfig config = new TwCommandItemConfig();
        TwCommandItemConfig.CommandEntry[] commands = new TwCommandItemConfig.CommandEntry[] {
                commandWithId("Follow"),
                null,
                commandWithId(""),
                commandWithId("Hold")
        };
        setField(config, "commandList", commands);

        assertEquals("Follow", config.findNextCommand(null).getId());
        assertEquals("Hold", config.findNextCommand("Follow").getId());
        assertEquals("Follow", config.findNextCommand("Hold").getId());
    }

    @Test
    void findCommandByIdIsCaseInsensitive() throws Exception {
        TwCommandItemConfig config = configWithCommands("Follow", "Hold");

        TwCommandItemConfig.CommandEntry found = config.findCommandById("  hold ");

        assertNotNull(found);
        assertEquals("Hold", found.getId());
    }

    @Test
    void commandEntriesRemainRadialVisibleUnlessExplicitlyHidden() throws Exception {
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "CommandList": [
                            { "Id": "Follow" },
                            { "Id": "Hold", "ShowInRadial": false }
                          ]
                        }
                        """),
                new ExtraInfo()
        );

        assertEquals(true, config.findCommandById("Follow").isShowInRadial());
        assertEquals(false, config.findCommandById("Hold").isShowInRadial());
    }

    private TwCommandItemConfig configWithCommands(String... ids) throws Exception {
        TwCommandItemConfig config = new TwCommandItemConfig();
        TwCommandItemConfig.CommandEntry[] commands = new TwCommandItemConfig.CommandEntry[ids.length];
        for (int i = 0; i < ids.length; i++) {
            commands[i] = commandWithId(ids[i]);
        }
        setField(config, "commandList", commands);
        return config;
    }

    private TwCommandItemConfig.CommandEntry commandWithId(String id) throws Exception {
        TwCommandItemConfig.CommandEntry entry = new TwCommandItemConfig.CommandEntry();
        setField(entry, "id", id);
        return entry;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
