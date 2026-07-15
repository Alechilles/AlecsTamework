package com.alechilles.alecstamework.items;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpawnerSourceSlotResolverTest {
    private final String expected = "filled_lantern";
    private final String other = "other_item";

    @Test
    void followsAnExactSourceMovedFromItsPreferredSlot() {
        List<String> hotbar = List.of(other, other, expected, other);

        assertEquals(2, SpawnerSourceSlotResolver.resolve(
                hotbar.size(), hotbar::get, expected, 0
        ));
    }

    @Test
    void preferredExactSourceWinsEvenWhenAnotherIdenticalStackExists() {
        List<String> hotbar = List.of(expected, other, expected);

        assertEquals(2, SpawnerSourceSlotResolver.resolve(
                hotbar.size(), hotbar::get, expected, 2
        ));
    }

    @Test
    void refusesAmbiguousMovedSources() {
        List<String> hotbar = List.of(expected, other, expected);

        assertNull(SpawnerSourceSlotResolver.resolve(
                hotbar.size(), hotbar::get, expected, 1
        ));
    }

    @Test
    void returnsNullWhenTheSourceNoLongerExists() {
        List<String> hotbar = List.of(other, other);

        assertNull(SpawnerSourceSlotResolver.resolve(
                hotbar.size(), hotbar::get, expected, 0
        ));
    }
}
