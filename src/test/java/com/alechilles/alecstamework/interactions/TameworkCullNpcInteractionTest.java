package com.alechilles.alecstamework.interactions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Verifies the safe defaults for reusable culling item interactions. */
class TameworkCullNpcInteractionTest {
    @Test
    void defaultsToOwnedAndTamedTargets() {
        TameworkCullNpcInteraction interaction =
                TameworkCullNpcInteraction.CODEC.decode(
                        BsonDocument.parse("{}"), new ExtraInfo());

        assertTrue(interaction.requiresOwner());
        assertTrue(interaction.requiresTamed());
    }
}
