package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
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
}
