package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InteractionInventoryEffectsTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffects.java"
    );

    @Test
    void inventoryTransactionsRequireEmptyRemainderForSuccess() throws Exception {
        String source = Files.readString(SOURCE);

        assertFalse(
                source.contains("remainder.getQuantity() < quantity"),
                "Partial inventory transactions must not count as complete interaction effects"
        );
        assertTrue(
                source.contains("remainder == null || remainder.isEmpty()"),
                "Inventory transaction success should require no remainder"
        );
    }

    @Test
    void addInventoryDoesNotSilentlyDropRemainder() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(
                source.contains("handleAddRemainder("),
                "Add inventory effects must explicitly handle the unadded remainder"
        );
        assertTrue(
                source.contains("private boolean handleAddRemainder"),
                "Remainder handling should be a named policy point"
        );
    }
}
