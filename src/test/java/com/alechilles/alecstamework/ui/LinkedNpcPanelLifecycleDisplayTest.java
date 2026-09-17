package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkedNpcPanelLifecycleDisplayTest {
    @Test
    void preservesLifecycleAcrossPresentationCopiesAndDoesNotFormatFrozenInfinity() {
        LinkedNpcEntry source = entry(false).withAnimalLifecycle(
                new AnimalProgressionService.Presentation("Prime", true, true, false,
                        Long.MAX_VALUE, 1.0));

        LinkedNpcEntry copied = source.withRoleSubtitle("Sheep").withPortraitIcon("portrait");
        LinkedNpcPanelCardBinder.LifecycleDisplay display =
                LinkedNpcPanelCardBinder.resolveLifecycleDisplay(copied, "en-US");

        assertTrue(copied.animalLifecycle().active());
        assertTrue(copied.animalLifecycle().prime());
        assertFalse(display.countdownText().contains("922337"));
    }

    @Test
    void capturedLifecycleUsesPausedStatus() {
        LinkedNpcEntry captured = entry(true).withAnimalLifecycle(
                new AnimalProgressionService.Presentation("Adult", false, false, false,
                        60_000L, 0.5));

        LinkedNpcPanelCardBinder.LifecycleDisplay display =
                LinkedNpcPanelCardBinder.resolveLifecycleDisplay(captured, "en-US");

        assertFalse(display.countdownText().isBlank());
    }

    private static LinkedNpcEntry entry(boolean captured) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 10, 10, 0, 0, "",
                0, 0, 0, 0, false, false, false, captured, false, false, 0L,
                LinkedNpcTraitIndicator.EMPTY);
    }
}
