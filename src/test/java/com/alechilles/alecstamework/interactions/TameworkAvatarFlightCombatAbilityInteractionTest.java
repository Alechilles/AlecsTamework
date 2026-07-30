package com.alechilles.alecstamework.interactions;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the native interaction bridge's single delegated root execution. */
class TameworkAvatarFlightCombatAbilityInteractionTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/interactions/"
                    + "TameworkAvatarFlightCombatAbilityInteraction.java");

    @Test
    void unavailableAbilityFinishesWithoutLookingUpOrExecutingARoot() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("if (!resolution.isAvailable())"));
        assertTrue(source.contains("InteractionState.Failed"));
        assertTrue(source.indexOf("if (!resolution.isAvailable())")
                < source.indexOf("RootInteraction.getRootInteractionOrUnknown"));
    }

    @Test
    void configuredAbilityDelegatesToOneNestedRoot() throws Exception {
        String source = Files.readString(SOURCE);

        assertEquals(1, occurrences(source, "context.execute("));
        assertTrue(source.contains("context.execute(RootInteraction.getRootInteractionOrUnknown(rootId))"));
    }

    @Test
    void acceptsOnlyAbility2AndAbility3AssetValues() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("AvatarFlightCombatAbilitySlot.fromSerializedKey"));
        assertTrue(source.contains("Ability2 or Ability3"));
        assertFalse(source.contains("KeyCode"));
    }

    @Test
    void invalidSlotFailsDecodingWithTheInteractionTypeInItsError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TameworkAvatarFlightCombatAbilityInteraction.CODEC.decode(
                        BsonDocument.parse("{ \"Slot\": \"Ability1\" }"), new ExtraInfo()));

        assertTrue(error.getMessage().contains("TameworkAvatarFlightCombatAbility"));
    }

    private static int occurrences(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
