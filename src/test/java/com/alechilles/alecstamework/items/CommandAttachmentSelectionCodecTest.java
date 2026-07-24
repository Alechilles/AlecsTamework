package com.alechilles.alecstamework.items;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAttachmentSelectionCodecTest {

    @Test
    void roundTripsAttachmentSelections() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Fur", "Black");
        expected.put("Eyes", "Green");

        String encoded = CommandAttachmentSelectionCodec.encode(expected);
        Map<String, String> decoded = CommandAttachmentSelectionCodec.decode(encoded);

        assertEquals(expected, decoded);
    }

    @Test
    void handlesEmptyAndInvalidInput() {
        assertNull(CommandAttachmentSelectionCodec.encode(Map.of()));
        assertTrue(CommandAttachmentSelectionCodec.decode(null).isEmpty());
        assertTrue(CommandAttachmentSelectionCodec.decode("").isEmpty());
        assertTrue(CommandAttachmentSelectionCodec.decode("not-valid").isEmpty());
    }
}
