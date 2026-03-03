package com.alechilles.alecstamework.items;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcDeathServiceAttachmentCodecTest {

    @Test
    void roundTripsAttachmentSelections() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Fur", "Black");
        expected.put("Eyes", "Green");

        String encoded = CommandLinkedNpcDeathService.encodeAttachmentSelections(expected);
        Map<String, String> decoded = CommandLinkedNpcDeathService.decodeAttachmentSelections(encoded);

        assertEquals(expected, decoded);
    }

    @Test
    void handlesEmptyAndInvalidInput() {
        assertNull(CommandLinkedNpcDeathService.encodeAttachmentSelections(Map.of()));
        assertTrue(CommandLinkedNpcDeathService.decodeAttachmentSelections(null).isEmpty());
        assertTrue(CommandLinkedNpcDeathService.decodeAttachmentSelections("").isEmpty());
        assertTrue(CommandLinkedNpcDeathService.decodeAttachmentSelections("not-valid").isEmpty());
    }
}
