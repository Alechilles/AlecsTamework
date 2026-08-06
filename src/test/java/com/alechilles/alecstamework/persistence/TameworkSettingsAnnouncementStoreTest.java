package com.alechilles.alecstamework.persistence;

import com.alechilles.alecstamework.persistence.TameworkSettingsAnnouncementStore.AnnouncementOptOutState;
import com.alechilles.alecstamework.persistence.TameworkSettingsAnnouncementStore.ResolvedAnnouncement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsAnnouncementStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadResolvedAnnouncementCreatesDefaultTemplateWhenFileMissing() throws Exception {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path announcementFile = TameworkSettingsAnnouncementStore.resolveAnnouncementConfigFile(tameworkRoot);

        assertFalse(Files.exists(announcementFile));

        ResolvedAnnouncement announcement = TameworkSettingsAnnouncementStore.loadResolvedAnnouncement(announcementFile, null);

        assertTrue(Files.isRegularFile(announcementFile));
        assertTrue(announcement.enabled());
        assertEquals(TameworkSettingsAnnouncementStore.BUILT_IN_ANNOUNCEMENT_ID, announcement.announcementId());
        assertTrue(announcement.useBuiltInText());
        assertEquals("Alec's Tamework 3.0: Persistence Rework", announcement.title());
        assertEquals(
                "Tamework's persistence system has been completely reworked.",
                announcement.subtitle()
        );
        assertTrue(announcement.bodyLines().size() >= 3);
        assertEquals("Don't show again until next announcement", announcement.optOutLabel());

        String raw = Files.readString(announcementFile);
        assertTrue(raw.contains("\"useBuiltInAnnouncementId\": true"));
        assertTrue(raw.contains("\"useBuiltInText\": true"));
        assertFalse(raw.contains("\"bodyLines\""));
        assertFalse(raw.contains("\"subtitle\""));
    }

    @Test
    void loadResolvedAnnouncementUsesCustomTextWhileFollowingBuiltInAnnouncementId() throws Exception {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path announcementFile = TameworkSettingsAnnouncementStore.resolveAnnouncementConfigFile(tameworkRoot);
        Files.createDirectories(announcementFile.getParent());
        Files.writeString(
                announcementFile,
                """
                        {
                          "enabled": true,
                          "useBuiltInAnnouncementId": true,
                          "useBuiltInText": false,
                          "announcementId": "server-campaign-1",
                          "title": "Server Review Required",
                          "subtitle": "Please review these server settings.",
                          "bodyLines": [
                            "Check the ownership defaults.",
                            "Check the needs damage defaults."
                          ],
                          "optOutLabel": "Don't show again until next announcement"
                        }
                        """.stripIndent()
        );

        ResolvedAnnouncement announcement = TameworkSettingsAnnouncementStore.loadResolvedAnnouncement(announcementFile, null);

        assertTrue(announcement.enabled());
        assertEquals(TameworkSettingsAnnouncementStore.BUILT_IN_ANNOUNCEMENT_ID, announcement.announcementId());
        assertFalse(announcement.useBuiltInText());
        assertEquals("Server Review Required", announcement.title());
        assertEquals("Please review these server settings.", announcement.subtitle());
        assertEquals(List.of("Check the ownership defaults.", "Check the needs damage defaults."), announcement.bodyLines());
        assertEquals("Don't show again until next announcement", announcement.optOutLabel());
    }

    @Test
    void loadResolvedAnnouncementUsesCustomAnnouncementIdWhenBuiltInTrackingIsDisabled() throws Exception {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path announcementFile = TameworkSettingsAnnouncementStore.resolveAnnouncementConfigFile(tameworkRoot);
        Files.createDirectories(announcementFile.getParent());
        Files.writeString(
                announcementFile,
                """
                        {
                          "enabled": true,
                          "useBuiltInAnnouncementId": false,
                          "useBuiltInText": false,
                          "announcementId": "server-campaign-2",
                          "title": "Custom Server Announcement"
                        }
                        """.stripIndent()
        );

        ResolvedAnnouncement announcement = TameworkSettingsAnnouncementStore.loadResolvedAnnouncement(announcementFile, null);

        assertEquals("server-campaign-2", announcement.announcementId());
        assertFalse(announcement.useBuiltInText());
        assertEquals("Custom Server Announcement", announcement.title());
        assertEquals(
                "Tamework's persistence system has been completely reworked.",
                announcement.subtitle()
        );
        assertFalse(announcement.bodyLines().isEmpty());
    }

    @Test
    void recordOptOutRoundTripsPerPlayerAnnouncementState() {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path stateFile = TameworkSettingsAnnouncementStore.resolveAnnouncementStateFile(tameworkRoot);
        UUID playerUuid = UUID.randomUUID();

        assertTrue(TameworkSettingsAnnouncementStore.recordOptOut(
                stateFile,
                playerUuid,
                "settings-review-v1",
                null
        ));

        AnnouncementOptOutState state = TameworkSettingsAnnouncementStore.loadAnnouncementState(stateFile, null);

        assertEquals("settings-review-v1", state.lastOptedOutAnnouncementIdByPlayerUuid().get(playerUuid));
        assertTrue(TameworkSettingsAnnouncementStore.hasOptedOut(state, playerUuid, "settings-review-v1"));
        assertFalse(TameworkSettingsAnnouncementStore.hasOptedOut(state, playerUuid, "settings-review-v2"));
    }

    @Test
    void shouldShowAnnouncementRequiresEnabledAnnouncementAndNoMatchingOptOut() {
        UUID playerUuid = UUID.randomUUID();
        ResolvedAnnouncement announcement = new ResolvedAnnouncement(
                true,
                "settings-review-v1",
                true,
                "Review",
                "Subtitle",
                List.of("Line 1"),
                "Opt out"
        );
        AnnouncementOptOutState state = new AnnouncementOptOutState(Map.of(playerUuid, "settings-review-v1"));

        assertFalse(TameworkSettingsAnnouncementStore.shouldShowAnnouncement(announcement, state, playerUuid));
        assertTrue(TameworkSettingsAnnouncementStore.shouldShowAnnouncement(
                new ResolvedAnnouncement(true, "settings-review-v2", true, "Review", "Subtitle", List.of("Line 1"), "Opt out"),
                state,
                playerUuid
        ));
        assertFalse(TameworkSettingsAnnouncementStore.shouldShowAnnouncement(
                new ResolvedAnnouncement(false, "settings-review-v2", true, "Review", "Subtitle", List.of("Line 1"), "Opt out"),
                state,
                playerUuid
        ));
    }

    @Test
    void selectAnnouncementShowsWelcomeForPlayerWithNoAnnouncementHistory() {
        UUID playerUuid = UUID.randomUUID();
        ResolvedAnnouncement updateAnnouncement = updateAnnouncement("settings-review-v2");
        AnnouncementOptOutState state = new AnnouncementOptOutState(Map.of(), Map.of(), Map.of());

        ResolvedAnnouncement selected = TameworkSettingsAnnouncementStore.selectAnnouncementForPlayer(
                updateAnnouncement,
                state,
                playerUuid,
                "2.11.2",
                false
        );

        assertEquals(TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID, selected.announcementId());
    }

    @Test
    void selectAnnouncementShowsUpdateForPlayerRecordedOnOlderVersion() {
        UUID playerUuid = UUID.randomUUID();
        ResolvedAnnouncement updateAnnouncement = updateAnnouncement("settings-review-v2");
        AnnouncementOptOutState state = new AnnouncementOptOutState(
                Map.of(),
                Map.of(playerUuid, TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID),
                Map.of(playerUuid, "2.11.1")
        );

        ResolvedAnnouncement selected = TameworkSettingsAnnouncementStore.selectAnnouncementForPlayer(
                updateAnnouncement,
                state,
                playerUuid,
                "2.11.2",
                false
        );

        assertEquals("settings-review-v2", selected.announcementId());
    }

    @Test
    void selectAnnouncementTreatsLegacyOptOutStateAsExistingInstallHistory() {
        UUID playerUuid = UUID.randomUUID();
        ResolvedAnnouncement updateAnnouncement = updateAnnouncement("settings-review-v3");
        AnnouncementOptOutState state = new AnnouncementOptOutState(Map.of(playerUuid, "settings-review-v2"));

        ResolvedAnnouncement selected = TameworkSettingsAnnouncementStore.selectAnnouncementForPlayer(
                updateAnnouncement,
                state,
                playerUuid,
                "2.11.3",
                false
        );

        assertEquals("settings-review-v3", selected.announcementId());
    }

    @Test
    void selectAnnouncementDoesNotReplaySeenAnnouncementAfterVersionUpdate() {
        UUID playerUuid = UUID.randomUUID();
        ResolvedAnnouncement updateAnnouncement = updateAnnouncement("settings-review-v2");
        AnnouncementOptOutState state = new AnnouncementOptOutState(
                Map.of(),
                Map.of(playerUuid, "settings-review-v2"),
                Map.of(playerUuid, "2.11.2")
        );

        ResolvedAnnouncement selected = TameworkSettingsAnnouncementStore.selectAnnouncementForPlayer(
                updateAnnouncement,
                state,
                playerUuid,
                "3.0.2",
                false
        );

        assertNull(selected);
    }

    @Test
    void recordAnnouncementSeenRoundTripsPlayerVersionHistory() {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path stateFile = TameworkSettingsAnnouncementStore.resolveAnnouncementStateFile(tameworkRoot);
        UUID playerUuid = UUID.randomUUID();

        assertTrue(TameworkSettingsAnnouncementStore.recordAnnouncementSeen(
                stateFile,
                playerUuid,
                TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID,
                "2.11.2",
                null
        ));

        AnnouncementOptOutState state = TameworkSettingsAnnouncementStore.loadAnnouncementState(stateFile, null);

        assertEquals(
                TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID,
                state.lastShownAnnouncementIdByPlayerUuid().get(playerUuid)
        );
        assertEquals("2.11.2", state.lastSeenTameworkVersionByPlayerUuid().get(playerUuid));
    }

    private static ResolvedAnnouncement updateAnnouncement(String announcementId) {
        return new ResolvedAnnouncement(
                true,
                announcementId,
                true,
                "Review",
                "Subtitle",
                List.of("Line 1"),
                "Opt out"
        );
    }
}
