package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.List;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests independent command target and hotswap HUD configuration behavior. */
class TwCommandItemConfigCommandHudTest {
    @Test
    void targetAndHotswapSelectionsDecodeIndependently() {
        TwCommandItemConfig config = decode(
                "{\"TargetHudRendererId\":\"Runeteria:Target\","
                        + "\"TargetHudContributors\":[{\"Id\":\"Runeteria:TargetBadge\",\"Required\":true}],"
                        + "\"HotswapHudRendererId\":\"Runeteria:Hotswap\","
                        + "\"HotswapHudContributors\":[{\"Id\":\"Runeteria:HotswapBadge\",\"Required\":false}]}"
        );

        assertEquals("runeteria:target", config.getTargetHudRendererId());
        assertEquals(
                List.of(new CommandHudContributorRequirement(
                        CommandHudContributorId.of("runeteria:targetbadge"), true)),
                config.getTargetHudContributors()
        );
        assertEquals("runeteria:hotswap", config.getHotswapHudRendererId());
        assertEquals(
                List.of(new CommandHudContributorRequirement(
                        CommandHudContributorId.of("runeteria:hotswapbadge"), false)),
                config.getHotswapHudContributors()
        );
    }

    @Test
    void rendererIdsNormalizeAndBlankValuesSelectStandardHud() {
        TwCommandItemConfig normalized = decode(
                "{\"TargetHudRendererId\":\" Runeteria:Target \","
                        + "\"HotswapHudRendererId\":\"RUNETERIA:Hotswap\"}"
        );
        TwCommandItemConfig blank = decode(
                "{\"TargetHudRendererId\":\"  \",\"HotswapHudRendererId\":\"\"}"
        );

        assertEquals("runeteria:target", normalized.getTargetHudRendererId());
        assertEquals("runeteria:hotswap", normalized.getHotswapHudRendererId());
        assertNull(blank.getTargetHudRendererId());
        assertNull(blank.getHotswapHudRendererId());
    }

    @Test
    void reservedHudIdsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"TargetHudRendererId\":\"tamework:internal\"}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"HotswapHudRendererId\":\"tamework:internal\"}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"TargetHudContributors\":[{\"Id\":\"tamework:internal\"}]}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"HotswapHudContributors\":[{\"Id\":\"tamework:internal\"}]}")
        );
    }

    @Test
    void duplicateContributorIdsAreRejectedPerHudSurface() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"TargetHudContributors\":["
                        + "{\"Id\":\"runeteria:badge\"},"
                        + "{\"Id\":\"Runeteria:Badge\"}]}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"HotswapHudContributors\":["
                        + "{\"Id\":\"runeteria:badge\"},"
                        + "{\"Id\":\"Runeteria:Badge\"}]}")
        );
    }

    private static TwCommandItemConfig decode(String json) {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }
}
