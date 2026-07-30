package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers normalization and codec decoding for avatar-flight combat abilities. */
class AvatarFlightCombatAbilitySettingsTest {
    @Test
    void codecDecodesConfiguredAbilityAndNormalizesValues() {
        AvatarFlightCombatAbilitySettings settings = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "RootInteraction": " Root_NPC_NordicDrake_Avatar_Fire_Ball ",
                          "Glyph": " FIRE "
                        }
                        """),
                new ExtraInfo()
        );

        assertEquals("Root_NPC_NordicDrake_Avatar_Fire_Ball", settings.getRootInteraction());
        assertEquals("FIRE", settings.getGlyph());
        assertTrue(settings.isConfigured());
    }

    @Test
    void blankOrMissingRootInteractionIsUnavailable() {
        AvatarFlightCombatAbilitySettings blank = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("{ \"RootInteraction\": \"   \", \"Glyph\": \"FIRE\" }"),
                new ExtraInfo()
        );
        AvatarFlightCombatAbilitySettings missing = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("{ \"Glyph\": \"FIRE\" }"),
                new ExtraInfo()
        );

        assertFalse(blank.isConfigured());
        assertFalse(missing.isConfigured());
    }
}
