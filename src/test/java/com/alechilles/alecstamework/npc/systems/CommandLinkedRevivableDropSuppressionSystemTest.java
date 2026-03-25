package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedRevivableDropSuppressionSystemTest {

    @Test
    void suppressesDropsWhenNpcIsLinkedAndDeadRespawnEnabled() {
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(
                UUID.randomUUID(),
                new String[]{"whistle-main"}
        );

        boolean suppressed = CommandLinkedRevivableDropSuppressionSystem.shouldSuppressDrops(links, true);

        assertTrue(suppressed);
    }

    @Test
    void doesNotSuppressDropsWhenDeadRespawnIsDisabled() {
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(
                UUID.randomUUID(),
                new String[]{"whistle-main"}
        );

        boolean suppressed = CommandLinkedRevivableDropSuppressionSystem.shouldSuppressDrops(links, false);

        assertFalse(suppressed);
    }

    @Test
    void doesNotSuppressDropsWhenNpcHasNoLinkedTools() {
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(
                UUID.randomUUID(),
                new String[0]
        );

        boolean suppressed = CommandLinkedRevivableDropSuppressionSystem.shouldSuppressDrops(links, true);

        assertFalse(suppressed);
    }
}
