package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Regression coverage for command-option snapshots used by the hotswap selectors. */
class CommandSelectionOptionSourceTest {

    @Test
    void unboundedOptionLimitDoesNotPreallocateAnUnboundedList() throws Exception {
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "CommandList": [
                            { "Id": "Follow" },
                            { "Id": "Hold" }
                          ]
                        }
                        """),
                new ExtraInfo()
        );

        CommandSelectionOptionSource.Option[] options = assertDoesNotThrow(
                () -> CommandSelectionOptionSource.build(
                        config, null, null, Integer.MAX_VALUE)
        );

        assertEquals(2, options.length);
    }
}
