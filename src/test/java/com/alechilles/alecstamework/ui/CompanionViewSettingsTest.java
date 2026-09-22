package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionViewSettingsTest {
    @Test
    void combinesStatusNearbySearchSelectionSpeciesAndGroupsWithoutChangingEntries() {
        LinkedNpcEntry matching = entry("Henrietta", "Chicken", true, true, "barn", "travel");
        LinkedNpcEntry notSelected = entry("Henrietta", "Chicken", false, true, "barn");
        LinkedNpcEntry wrongSpecies = entry("Henrietta", "Pig", true, true, "barn");
        LinkedNpcEntry wrongGroup = entry("Henrietta", "Chicken", true, true, "field");
        LinkedNpcEntry far = entry("Henrietta", "Chicken", true, false, "barn");
        CompanionViewSettings settings = new CompanionViewSettings(
                "All", true, "henri", "Name", true, List.of("Chicken"), List.of("travel", "yard"));

        assertArrayEquals(new LinkedNpcEntry[]{matching}, settings.filter(new LinkedNpcEntry[]{
                matching, notSelected, wrongSpecies, wrongGroup, far}));
        assertTrue(matching.active(), "View filtering is presentation-only and retains the flute selection.");
        assertTrue(settings.hasExtraFilters());
    }

    @Test
    void defaultsAndCopiesBoundUntrustedValues() {
        CompanionViewSettings settings = new CompanionViewSettings(
                "bad", false, "  find me  ", "bad", false,
                List.of(" chicken ", "chicken", "", "pig"), List.of("barn", "barn"));

        assertEquals("InWorld", settings.state());
        assertEquals("Default", settings.sort());
        assertEquals("find me", settings.search());
        assertEquals(List.of("chicken", "pig"), settings.speciesIds());
        assertEquals(List.of("barn"), settings.groupIds());
        assertFalse(CompanionViewSettings.defaults().hasExtraFilters());
        assertEquals("All", settings.withState("all").state());
    }

    @Test
    void speciesFilterSkipsLegacyEntriesWithoutSpecies() {
        LinkedNpcEntry unknown = entry("Unknown", null, false, false);
        LinkedNpcEntry chicken = entry("Chicken", "Chicken", false, false);
        CompanionViewSettings settings = CompanionViewSettings.defaults().withState("All")
                .withExtraFilters(false, List.of("Chicken"), List.of());

        assertArrayEquals(new LinkedNpcEntry[]{chicken}, settings.filter(new LinkedNpcEntry[]{unknown, chicken}));
    }

    private static LinkedNpcEntry entry(String name, String species, boolean active, boolean nearby, String... groups) {
        UUID id = UUID.randomUUID();
        return new LinkedNpcEntry(id, name, 10, 10, 0, 0, 0, null,
                0, 0, 0, 0, true, false, false, false, false, false, 0L, null, null, null,
                new LinkedNpcTraitIndicator[0], false, false, false, false, true, active,
                species, species, null, null, null, false, false, 0L, 0.0, false)
                .withNearby(nearby)
                .withCompanionGroups(id.toString(), java.util.Arrays.stream(groups)
                        .map(group -> new LinkedNpcEntry.GroupMembership(group, group, "#445566")).toList(), true);
    }
}
