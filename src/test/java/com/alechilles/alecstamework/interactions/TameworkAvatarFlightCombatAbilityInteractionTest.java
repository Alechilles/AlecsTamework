package com.alechilles.alecstamework.interactions;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import com.alechilles.alecstamework.avatarflight.AvatarFlightCombatAbilityResolver;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the native interaction bridge's single delegated root execution. */
class TameworkAvatarFlightCombatAbilityInteractionTest {
    @Test
    void unavailableAbilityDoesNotDelegate() {
        AtomicInteger delegationCount = new AtomicInteger();

        assertFalse(TameworkAvatarFlightCombatAbilityInteraction.delegate(
                AvatarFlightCombatAbilityResolver.Resolution.unavailable(),
                ignored -> delegationCount.incrementAndGet()));
        assertEquals(0, delegationCount.get());
    }

    @Test
    void configuredAbilityDelegatesItsRootExactlyOnce() {
        AtomicInteger delegationCount = new AtomicInteger();
        AtomicReference<String> delegatedRoot = new AtomicReference<>();

        assertTrue(TameworkAvatarFlightCombatAbilityInteraction.delegate(
                AvatarFlightCombatAbilityResolver.Resolution.available("Root_Test", 0.0),
                rootId -> {
                    delegationCount.incrementAndGet();
                    delegatedRoot.set(rootId);
                }));
        assertEquals(1, delegationCount.get());
        assertEquals("Root_Test", delegatedRoot.get());
    }

    @Test
    void invalidSlotFailsDecodingWithTheInteractionTypeInItsError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TameworkAvatarFlightCombatAbilityInteraction.CODEC.decode(
                        BsonDocument.parse("{ \"Slot\": \"Ability1\" }"), new ExtraInfo()));

        assertTrue(error.getMessage().contains("TameworkAvatarFlightCombatAbility"));
    }
}
