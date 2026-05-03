package com.alechilles.alecstamework.items;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommandLinkedNpcRecordStoreTest {

    @Test
    void parseReadsLastKnownWorldName() throws Exception {
        CommandLinkedNpcRecordStore store = new CommandLinkedNpcRecordStore();
        UUID npcUuid = UUID.randomUUID();
        String encodedWorld = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("adventure_world".getBytes(StandardCharsets.UTF_8));
        Method parse = CommandLinkedNpcRecordStore.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);

        LinkedNpcRecord record = (LinkedNpcRecord) parse.invoke(
                store,
                npcUuid + "|12.5|64.0|-3.25|1.0|2.0|3.0|lw=" + encodedWorld + "|rid=Y2F0X3BldA"
        );

        assertNotNull(record);
        assertEquals(npcUuid, record.npcUuid);
        assertEquals("adventure_world", record.lastKnownWorldName);
        assertEquals(12.5, record.lastKnownPosition.x);
        assertEquals(64.0, record.lastKnownPosition.y);
        assertEquals(-3.25, record.lastKnownPosition.z);
    }
}
