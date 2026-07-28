package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ensures bonded durable health fields reach the existing linked-NPC card. */
class BondedCompanionPanelHealthFidelityTest {

    @Test
    void storedBondedCardUsesExactHealthFieldsInsteadOfDefaultingToOneHundred() {
        var profile = BondedPanelTestFixtures.profile(
                "dragon-health", 1L, BondedCompanionStateView.STORED, null,
                Map.of("currentHealth", "250.0", "maxHealth", "400.0",
                        "healthPercent", "62.5"));
        var api = BondedPanelTestFixtures.api(List.of(profile));
        var source = new BondedCompanionPanelEntrySourceService(
                BondedPanelTestFixtures.cache(api),
                new BondedCompanionPanelRecordSource(),
                new BondedCompanionPanelFeaturePresentationSource(
                        () -> 0L));

        var entries = source.buildSnapshot(BondedPanelTestFixtures.OWNER,
                "world", new BondedCompanionPanelRecordSource().ready(
                        BondedPanelTestFixtures.OWNER, "hydragon:dragons",
                        List.of(profile))).entries();

        assertEquals(250, entries.getFirst().currentHealth());
        assertEquals(400, entries.getFirst().maxHealth());
    }
}
