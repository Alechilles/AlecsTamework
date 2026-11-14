package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.alechilles.alecstamework.companioncard.CompanionCardRenameMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The card settings survive a save, and a missing file or a bad hand edit falls back to the defaults. */
class CompanionCardSettingsStoreTest {
    @TempDir
    Path directory;

    @Test
    void savedSettingsLoadBack() {
        Path file = directory.resolve(CompanionCardSettingsStore.FILE_NAME);
        // No regions ticked must stay empty (it hides the Random button) rather than fall back to all of them.
        CompanionCardSettingsStore.Settings settings = new CompanionCardSettingsStore.Settings(
                CompanionCardFBehaviour.CYCLE, false, CompanionCardRenameMode.OFF,
                List.of("Pack_Names", "TwNamesDefault"), Set.of());

        CompanionCardSettingsStore.save(file, settings, null);

        assertEquals(settings, CompanionCardSettingsStore.read(file, null));
    }

    @Test
    void missingFileOrUnknownValuesFallBackToTheDefaults() throws Exception {
        Path file = directory.resolve(CompanionCardSettingsStore.FILE_NAME);
        assertEquals(CompanionCardSettingsStore.Settings.DEFAULT, CompanionCardSettingsStore.read(file, null));

        Files.writeString(file, "{\"defaultFBehaviour\": \"WHEEL\", \"renameMode\": \"PAID\"}", StandardCharsets.UTF_8);

        assertEquals(CompanionCardSettingsStore.Settings.DEFAULT, CompanionCardSettingsStore.read(file, null));
    }
}
