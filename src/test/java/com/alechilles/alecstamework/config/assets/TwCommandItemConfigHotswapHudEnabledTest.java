package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for command items that only provide target HUD information. */
class TwCommandItemConfigHotswapHudEnabledTest {
    @Test
    void inheritedDisabledHotswapHudCanBeExplicitlyEnabledByChild() {
        TwCommandItemConfig parent = decode("{\"HotswapHudEnabled\":false}");
        TwCommandItemConfig inheritedChild = decode("{}");
        TwCommandItemConfig enabledChild = decode("{\"HotswapHudEnabled\":true}");

        inheritedChild.inheritMissingTopLevelFrom(parent, Set.of());
        enabledChild.inheritMissingTopLevelFrom(parent, Set.of("HotswapHudEnabled"));

        assertFalse(inheritedChild.isHotswapHudEnabled());
        assertTrue(enabledChild.isHotswapHudEnabled());
        assertTrue(decode("{}").isHotswapHudEnabled());
    }

    private static TwCommandItemConfig decode(String json) {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
    }
}
