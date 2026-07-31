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
                          "Glyph": " FIRE ",
                          "GlyphTexturePath": " MyDragon/AvatarFlightIcons/Fireball.png "
                        }
                        """),
                new ExtraInfo()
        );

        assertEquals("Root_NPC_NordicDrake_Avatar_Fire_Ball", settings.getRootInteraction());
        assertEquals("FIRE", settings.getGlyph());
        assertEquals("MyDragon/AvatarFlightIcons/Fireball.png", settings.getGlyphTexturePath());
        assertTrue(settings.isConfigured());
    }

    @Test
    void codecRoundTripPreservesConfiguredAbility() {
        AvatarFlightCombatAbilitySettings decoded = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "RootInteraction": "Root_NPC_NordicDrake_Avatar_Fire_Ball",
                          "Glyph": "FIRE",
                          "GlyphTexturePath": "MyDragon/AvatarFlightIcons/Fireball.png"
                        }
                        """),
                new ExtraInfo()
        );

        AvatarFlightCombatAbilitySettings roundTripped = AvatarFlightCombatAbilitySettings.CODEC.decode(
                AvatarFlightCombatAbilitySettings.CODEC.encode(decoded, new ExtraInfo()),
                new ExtraInfo()
        );

        assertEquals("Root_NPC_NordicDrake_Avatar_Fire_Ball", roundTripped.getRootInteraction());
        assertEquals("FIRE", roundTripped.getGlyph());
        assertEquals("MyDragon/AvatarFlightIcons/Fireball.png", roundTripped.getGlyphTexturePath());
        assertTrue(roundTripped.isConfigured());
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
