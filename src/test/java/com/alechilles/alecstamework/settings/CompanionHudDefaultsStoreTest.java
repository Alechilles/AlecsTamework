package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.ui.CompanionHudPlacement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The server's default HUD position survives a save, and a missing or broken file falls back safely. */
class CompanionHudDefaultsStoreTest {
    @TempDir
    Path directory;

    @Test
    void savedDefaultLoadsBack() {
        Path file = directory.resolve(CompanionHudDefaultsStore.FILE_NAME);
        CompanionHudPlacement placement = new CompanionHudPlacement(CompanionHudPlacement.Point.BOTTOM_LEFT, 40, -120);

        CompanionHudDefaultsStore.save(file, placement, null);

        assertEquals(placement, CompanionHudDefaultsStore.load(file, null));
    }

    @Test
    void missingOrUnknownPointFallsBackToTheBuiltInDefault() throws Exception {
        Path file = directory.resolve(CompanionHudDefaultsStore.FILE_NAME);
        assertEquals(CompanionHudPlacement.DEFAULT, CompanionHudDefaultsStore.load(file, null));

        Files.writeString(file, "{\"point\": \"SOMEWHERE\", \"x\": 5, \"y\": 5}", StandardCharsets.UTF_8);

        assertEquals(CompanionHudPlacement.DEFAULT, CompanionHudDefaultsStore.load(file, null));
    }
}
