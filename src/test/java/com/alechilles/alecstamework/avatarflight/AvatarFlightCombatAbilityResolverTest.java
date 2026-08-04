package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers config-only resolution for native avatar-flight combat inputs. */
class AvatarFlightCombatAbilityResolverTest {
    private final AvatarFlightCombatAbilityResolver resolver = new AvatarFlightCombatAbilityResolver();

    @Test
    void noActiveFlightStateIsUnavailable() {
        assertFalse(resolver.resolve(null, configuredAbility(), AvatarFlightCombatAbilitySlot.ABILITY_2).isAvailable());
    }

    @Test
    void missingConfigIsUnavailable() {
        assertFalse(resolver.resolve(activeFlight(), null, AvatarFlightCombatAbilitySlot.ABILITY_2).isAvailable());
    }

    @Test
    void configuredAbilityResolvesItsRootWithoutExecutingIt() {
        AvatarFlightCombatAbilityResolver.Resolution resolution = resolver.resolve(
                activeFlight(), configuredAbility(), AvatarFlightCombatAbilitySlot.ABILITY_2);

        assertTrue(resolution.isAvailable());
        assertEquals("Root_NPC_NordicDrake_Avatar_Fire_Ball", resolution.rootInteractionId());
        assertEquals(15.0, resolution.cooldownSeconds());
    }

    @Test
    void configuredButBlankRootIsUnavailable() {
        assertFalse(resolver.resolve(activeFlight(), blankAbility(), AvatarFlightCombatAbilitySlot.ABILITY_2).isAvailable());
    }

    @Test
    void staleCapturedConfigDoesNotFallBackToAnotherActiveProfile() {
        AtomicReference<String> requestedId = new AtomicReference<>();
        AvatarFlightCombatAbilityResolver exactResolver = new AvatarFlightCombatAbilityResolver(configId -> {
            requestedId.set(configId);
            return "CapturedFlight".equals(configId) ? configuredAbility() : null;
        });

        AvatarFlightComponent staleFlight = new AvatarFlightComponent("StaleFlight", 1L);
        AvatarFlightCombatAbilityResolver.Resolution resolution = exactResolver.resolve(
                staleFlight, AvatarFlightCombatAbilitySlot.ABILITY_2);

        assertEquals("StaleFlight", requestedId.get());
        assertFalse(resolution.isAvailable());
    }

    @Test
    void disabledCapturedConfigIsUnavailable() {
        TwAvatarFlightConfig disabledConfig = TwAvatarFlightConfig.CODEC.decode(BsonDocument.parse("""
                { "Enabled": false, "CombatAbilities": {
                  "Ability2": {
                    "RootInteraction": "Root_NPC_NordicDrake_Avatar_Fire_Ball",
                    "CooldownSeconds": 15
                  }
                } }
                """), new ExtraInfo());

        assertFalse(resolver.resolve(activeFlight(), disabledConfig,
                AvatarFlightCombatAbilitySlot.ABILITY_2).isAvailable());
    }

    private static AvatarFlightComponent activeFlight() {
        return new AvatarFlightComponent("TestFlight", 1L);
    }

    private static TwAvatarFlightConfig configuredAbility() {
        return TwAvatarFlightConfig.CODEC.decode(BsonDocument.parse("""
                { "CombatAbilities": {
                  "Ability2": {
                    "RootInteraction": "Root_NPC_NordicDrake_Avatar_Fire_Ball",
                    "CooldownSeconds": 15
                  }
                } }
                """), new ExtraInfo());
    }

    private static TwAvatarFlightConfig blankAbility() {
        return TwAvatarFlightConfig.CODEC.decode(BsonDocument.parse("""
                { "CombatAbilities": {
                  "Ability2": { "RootInteraction": "   " }
                } }
                """), new ExtraInfo());
    }
}
