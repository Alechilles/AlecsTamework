package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.List;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests independent command target and hotswap HUD configuration behavior. */
class TwCommandItemConfigCommandHudTest {
    @Test
    void targetAndHotswapSelectionsInheritIndependently() {
        TwCommandItemConfig parent = decode(
                "{\"TargetHudRendererId\":\"Runeteria:Target\","
                        + "\"TargetHudContributors\":[{\"Id\":\"Runeteria:TargetBadge\",\"Required\":true}],"
                        + "\"HotswapHudRendererId\":\"Runeteria:Hotswap\","
                        + "\"HotswapHudContributors\":[{\"Id\":\"Runeteria:Badge\",\"Required\":false}]}"
        );
        TwCommandItemConfig child = decode("{\"TargetHudContributors\":[]}");

        child.inheritMissingTopLevelFrom(parent, Set.of("TargetHudContributors"));

        assertEquals("runeteria:target", child.getTargetHudRendererId());
        assertTrue(child.getTargetHudContributors().isEmpty());
        assertEquals("runeteria:hotswap", child.getHotswapHudRendererId());
        assertEquals(
                List.of(new CommandHudContributorRequirement(
                        CommandHudContributorId.of("runeteria:badge"), false)),
                child.getHotswapHudContributors()
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
                        + "{\"Id\":\"Runeteria:Badge\"}]}" )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"HotswapHudContributors\":["
                        + "{\"Id\":\"runeteria:badge\"},"
                        + "{\"Id\":\"Runeteria:Badge\"}]}" )
        );
    }

    private static TwCommandItemConfig decode(String json) {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }
}
