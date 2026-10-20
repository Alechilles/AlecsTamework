package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.companion.store.CompanionStorage.LegacyKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TameworkSettingsAnnouncementServiceTest {
    @Test
    void peopleWhoCanConvertTheWorldGetTheConversionNoticeWhileOneIsPending() {
        LegacyKind pending = LegacyKind.LEGACY_2X;
        assertEquals(pending, TameworkSettingsAnnouncementService.migrationNoticeFor(pending, true, false, false));
        assertEquals(pending, TameworkSettingsAnnouncementService.migrationNoticeFor(pending, false, true, false));
        assertEquals(pending, TameworkSettingsAnnouncementService.migrationNoticeFor(pending, false, false, true),
                "the local singleplayer owner is the one who has to convert the world");
        assertNull(TameworkSettingsAnnouncementService.migrationNoticeFor(pending, false, false, false),
                "regular players get nothing");
        assertNull(TameworkSettingsAnnouncementService.migrationNoticeFor(null, true, true, true));
    }
}
