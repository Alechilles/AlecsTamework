package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void activeProgressionSupersedesDurableLevelXpAndTalentPoints() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("levelingConfigId", "saved-leveling", "level", "10",
                        "currentXp", "20.0", "talentConfigId", "saved-talents",
                        "talentSpentPoints", "1"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(profile,
                new BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot(
                        "wyvern-leveling", 12, 43.5, "wyvern-talents", 3));

        assertEquals("wyvern-leveling", updated.snapshotPresentationData().get(
                "levelingConfigId"));
        assertEquals("12", updated.snapshotPresentationData().get("level"));
        assertEquals("43.5", updated.snapshotPresentationData().get("currentXp"));
        assertEquals("wyvern-talents", updated.snapshotPresentationData().get(
                "talentConfigId"));
        assertEquals("3", updated.snapshotPresentationData().get("talentSpentPoints"));
        assertEquals(profile.profileId(), updated.profileId());
        assertEquals(profile.revision(), updated.revision());
        assertEquals(profile.state(), updated.state());
        assertEquals(profile.activeLease(), updated.activeLease());
    }

    /** Regression: a role change must not combine the live tree with stale purchased IDs. */
    @Test
    void activeProgressionSupersedesTheCompleteDurableTalentAllocation() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("levelingConfigId", "wyvern-leveling", "level", "12",
                        "currentXp", "43.5", "talentConfigId", "wild-talents",
                        "talentSpentPoints", "3", "talentAllocationRevision", "1",
                        "talents", "shared-root, shared-upgrade"));
        var live = new BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot(
                "wyvern-leveling", 12, 43.5, "fire-talents", 1, 7L,
                List.of("fire-root"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(
                profile, live);

        assertEquals("fire-talents", updated.snapshotPresentationData().get(
                "talentConfigId"));
        assertEquals("1", updated.snapshotPresentationData().get(
                "talentSpentPoints"));
        assertEquals("7", updated.snapshotPresentationData().get(
                "talentAllocationRevision"));
        assertEquals("fire-root", updated.snapshotPresentationData().get("talents"));
    }

    @Test
    void unchangedProgressionReturnsTheOriginalProfileView() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("levelingConfigId", "wyvern-leveling", "level", "12",
                        "currentXp", "43.5", "talentConfigId", "wyvern-talents",
                        "talentSpentPoints", "3"));

        assertSame(profile, BondedCompanionPanelLiveProfileOverlay.withProgression(profile,
                new BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot(
                        "wyvern-leveling", 12, 43.5, "wyvern-talents", 3)));
    }

    @Test
    void missingTalentProjectionKeepsDurableTalentFieldsWhileUpdatingLeveling() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("levelingConfigId", "saved-leveling", "level", "10",
                        "currentXp", "20.0", "talentConfigId", "saved-talents",
                        "talentSpentPoints", "1"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(profile,
                new BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot(
                        "wyvern-leveling", 12, 43.5, null, null));

        assertEquals("wyvern-leveling", updated.snapshotPresentationData().get(
                "levelingConfigId"));
        assertEquals("12", updated.snapshotPresentationData().get("level"));
        assertEquals("43.5", updated.snapshotPresentationData().get("currentXp"));
        assertEquals("saved-talents", updated.snapshotPresentationData().get(
                "talentConfigId"));
        assertEquals("1", updated.snapshotPresentationData().get("talentSpentPoints"));
    }

    @Test
    void absentProgressionKeepsTheDurableProfileView() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("level", "10"));

        assertSame(profile, BondedCompanionPanelLiveProfileOverlay.withProgression(
                profile, null));
    }

    @Test
    void flightModeIsTransientAndLeavesDurableProfileFieldsUntouched() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("level", "10"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withFlightMode(
                profile, Optional.of(false));

        assertEquals("true", updated.snapshotPresentationData().get(
                "bonded.flightToggle.available"));
        assertEquals("false", updated.snapshotPresentationData().get(
                "bonded.flightToggle.airborne"));
        assertEquals(profile.profileId(), updated.profileId());
        assertEquals(profile.revision(), updated.revision());
        assertEquals(profile.state(), updated.state());
        assertEquals(profile.roleId(), updated.roleId());
        assertEquals(profile.activeLease(), updated.activeLease());
        assertEquals(Map.of("level", "10"), profile.snapshotPresentationData());
    }

    @Test
    void unavailableFlightModeRemovesStaleTransientAttributes() {
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE,
                UUID.fromString("71000000-0000-0000-0000-000000000009"),
                Map.of("bonded.flightToggle.available", "true",
                        "bonded.flightToggle.airborne", "true", "level", "10"));

        var updated = BondedCompanionPanelLiveProfileOverlay.withFlightMode(
                profile, Optional.empty());

        assertEquals(Map.of("level", "10"), updated.snapshotPresentationData());
    }
}
