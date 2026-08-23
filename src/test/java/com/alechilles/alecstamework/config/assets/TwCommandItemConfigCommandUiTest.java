package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests command UI provider selection, normalization, and inheritance. */
class TwCommandItemConfigCommandUiTest {
    @Test
    void omittedAndBlankProviderSelectTheStandardMenu() {
        TwCommandItemConfig omitted = decode("{}");
        TwCommandItemConfig blank = decode("{\"UiProviderId\":\"  \"}");

        assertNull(omitted.getUiProviderId());
        assertNull(blank.getUiProviderId());
    }

    @Test
    void providerIdIsNormalizedAndOmittedChildValueInherits() {
        TwCommandItemConfig parent = decode("{\"UiProviderId\":\" Runeteria:Husbandry \"}");
        TwCommandItemConfig child = decode("{}");

        assertEquals("runeteria:husbandry", parent.getUiProviderId());
        child.inheritMissingTopLevelFrom(parent, java.util.Set.of());
        assertEquals("runeteria:husbandry", child.getUiProviderId());
    }

    @Test
    void explicitProviderIdPreventsParentFallback() {
        TwCommandItemConfig parent = decode("{\"UiProviderId\":\"runeteria:husbandry\"}");
        TwCommandItemConfig child = decode("{\"UiProviderId\":\"\"}");

        child.inheritMissingTopLevelFrom(
                parent,
                java.util.Set.of("UiProviderId")
        );

        assertNull(child.getUiProviderId());
    }

    @Test
    void malformedProviderIdIsRejectedByCodec() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("{\"UiProviderId\":\"not-namespaced\"}")
        );
    }

    private static TwCommandItemConfig decode(String json) {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }
}
