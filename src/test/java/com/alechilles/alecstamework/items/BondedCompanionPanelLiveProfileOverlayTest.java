package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers live presentation fields that temporarily supersede a stored snapshot. */
class BondedCompanionPanelLiveProfileOverlayTest {
    @Test
    void activeNameImmediatelySupersedesTheStoredProfileName() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("level", "10"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withDisplayName(
                profile, "Wyatt");

        assertEquals("Wyatt", updated.displayName());
        assertEquals(profile.profileId(), updated.profileId());
        assertEquals(profile.revision(), updated.revision());
        assertEquals(profile.snapshotPresentationData(),
                updated.snapshotPresentationData());
    }

    @Test
    void blankLiveNameKeepsTheDurableProfileView() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of());

        assertSame(profile, BondedCompanionPanelLiveProfileOverlay
                .withDisplayName(profile, "  "));
    }

    @Test
    void activeHealthImmediatelySupersedesTheStoredHealthSnapshot() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("currentHealth", "400.0", "maxHealth", "400.0",
                        "healthPercent", "100.0"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withHealth(profile,
                new CompanionHealthStateService.HealthSnapshot(
                        125.0, 250.0, 50.0));

        assertEquals("125.0", updated.snapshotPresentationData().get("currentHealth"));
        assertEquals("250.0", updated.snapshotPresentationData().get("maxHealth"));
        assertEquals("50.0", updated.snapshotPresentationData().get("healthPercent"));
        assertEquals(profile.revision(), updated.revision());
    }
}
