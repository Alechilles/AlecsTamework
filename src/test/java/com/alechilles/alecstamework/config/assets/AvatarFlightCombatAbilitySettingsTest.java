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
                          "GlyphTexturePath": " MyDragon/AvatarFlightIcons/Fireball.png ",
                          "CooldownSeconds": 15
                        }
                        """),
                new ExtraInfo()
        );

        assertEquals("Root_NPC_NordicDrake_Avatar_Fire_Ball", settings.getRootInteraction());
        assertEquals("FIRE", settings.getGlyph());
        assertEquals("MyDragon/AvatarFlightIcons/Fireball.png", settings.getGlyphTexturePath());
        assertEquals(15.0, settings.getCooldownSeconds());
        assertTrue(settings.isConfigured());
    }

    @Test
    void codecRoundTripPreservesConfiguredAbility() {
        AvatarFlightCombatAbilitySettings decoded = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "RootInteraction": "Root_NPC_NordicDrake_Avatar_Fire_Ball",
                          "Glyph": "FIRE",
                          "GlyphTexturePath": "MyDragon/AvatarFlightIcons/Fireball.png",
                          "CooldownSeconds": 15
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
        assertEquals(15.0, roundTripped.getCooldownSeconds());
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

    @Test
    void cooldownIsClampedToZeroWhenMissingOrNegative() {
        AvatarFlightCombatAbilitySettings missing = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("{ \"RootInteraction\": \"Root_Test\" }"), new ExtraInfo());
        AvatarFlightCombatAbilitySettings negative = AvatarFlightCombatAbilitySettings.CODEC.decode(
                BsonDocument.parse("{ \"RootInteraction\": \"Root_Test\", \"CooldownSeconds\": -1 }"),
                new ExtraInfo());

        assertEquals(0.0, missing.getCooldownSeconds());
        assertEquals(0.0, negative.getCooldownSeconds());
    }
}
