package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests command UI renderer and contributor configuration behavior. */
class TwCommandItemConfigCommandUiTest {
    @Test
    void explicitContributorListReplacesInheritedList() {
        TwCommandItemConfig parent = decode(
                "{\"UiRendererId\":\"runeteria:ui\","
                        + "\"UiContributors\":[{\"Id\":\"runeteria:base\",\"Required\":true}]}"
        );
        TwCommandItemConfig child = decode("{\"UiContributors\":[]}");

        child.inheritMissingTopLevelFrom(parent, Set.of("UiContributors"));

        assertEquals("runeteria:ui", child.getUiRendererId());
        assertTrue(child.getUiContributors().isEmpty());
    }

    @Test
    void omittedRendererAndContributorValuesInherit() {
        TwCommandItemConfig parent = decode(
                "{\"UiRendererId\":\"Runeteria:UI\","
                        + "\"UiContributors\":[{\"Id\":\"Runeteria:Base\",\"Required\":false}]}"
        );
        TwCommandItemConfig child = decode("{}");

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals("runeteria:ui", child.getUiRendererId());
        assertEquals(
                new CommandUiContributorRequirement(CommandUiContributorId.of("runeteria:base"), false),
                child.getUiContributors().get(0)
        );
    }

    @Test
    void rendererIdIsNormalizedAndBlankSelectsStandardUi() {
        TwCommandItemConfig normalized = decode("{\"UiRendererId\":\" Runeteria:UI \"}");
        TwCommandItemConfig blank = decode("{\"UiRendererId\":\"  \"}");

        assertEquals("runeteria:ui", normalized.getUiRendererId());
        assertNull(blank.getUiRendererId());
    }

    @Test
    void malformedRendererAndContributorIdsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"UiRendererId\":\"not-namespaced\"}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"UiContributors\":[{\"Id\":\"not-namespaced\"}]}")
        );
    }

    @Test
    void reservedRendererIdIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"UiRendererId\":\"tamework:internal\"}")
        );
    }

    @Test
    void reservedContributorIdIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"UiContributors\":[{\"Id\":\"tamework:internal\"}]}")
        );
    }

    @Test
    void duplicateContributorIdsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"UiContributors\":["
                        + "{\"Id\":\"runeteria:base\"},"
                        + "{\"Id\":\"Runeteria:Base\"}]}" )
        );
    }

    private static TwCommandItemConfig decode(String json) {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }
}
