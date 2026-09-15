package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwCommandItemConfigCooldownTest {
    @Test
    void parsesFractionalAndExistingWholeSecondCooldowns() {
        assertCooldown("0.25", 0.25);
        assertCooldown("2", 2.0);
        assertCooldown("0", 0.0);
        assertCooldown("-1", 0.0);
    }

    private void assertCooldown(String seconds, double expected) {
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("{\"CooldownSeconds\":" + seconds + "}"), new ExtraInfo());

        assertEquals(expected, config.getCooldownSeconds());
    }
}
