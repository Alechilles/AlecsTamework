package com.alechilles.alecstamework.companion.store;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionStorageTest {
    private static final Path ROOT = Path.of("universe", "Tamework", "Companions");
    private static final Path DATA = Path.of("universe", "Tamework", "Data");

    @Test
    void aFreshWorldIsReady() {
        assertEquals(CompanionStorage.Status.READY, CompanionStorage.detect(ROOT, List.of(DATA), p -> false));
    }

    @Test
    void oldSavesWithoutTheNewStoreRequireMigration() {
        Set<Path> present = Set.of(DATA.resolve("tamework-state.sqlite"));
        assertEquals(CompanionStorage.Status.MIGRATION_REQUIRED,
                CompanionStorage.detect(ROOT, List.of(DATA), present::contains));
    }

    @Test
    void anyLegacyFileInAnySourceDirectoryCounts() {
        Path legacyPluginDir = Path.of("mods", "Alechilles_Alec's Tamework!");
        Set<Path> present = Set.of(legacyPluginDir.resolve("CommandLinkedNpcDeaths.dat"));
        assertEquals(CompanionStorage.Status.MIGRATION_REQUIRED,
                CompanionStorage.detect(ROOT, List.of(DATA, legacyPluginDir), present::contains));
    }

    @Test
    void detectionNamesTheKindOfOldSaves() {
        Path legacyPluginDir = Path.of("mods", "Alechilles_Alec's Tamework!");
        List<Path> dirs = List.of(legacyPluginDir, DATA);

        assertNull(CompanionStorage.detectLegacy(ROOT, dirs, p -> false));
        assertEquals(CompanionStorage.LegacyKind.LEGACY_3X_4X,
                CompanionStorage.detectLegacy(ROOT, dirs, Set.of(DATA.resolve("bonded-companions.sqlite"))::contains));
        assertEquals(CompanionStorage.LegacyKind.LEGACY_2X,
                CompanionStorage.detectLegacy(ROOT, dirs, Set.of(DATA.resolve("tamework.sqlite"))::contains));
        assertEquals(CompanionStorage.LegacyKind.LEGACY_2X, CompanionStorage.detectLegacy(ROOT, dirs,
                Set.of(legacyPluginDir.resolve("CommandLinkedNpcCaptures.dat"))::contains));
    }

    @Test
    void newerOldSavesWinOverVersion2SavesInAnyDirectory() {
        Path legacyPluginDir = Path.of("mods", "Alechilles_Alec's Tamework!");
        Set<Path> present = Set.of(legacyPluginDir.resolve("tamework.sqlite"), DATA.resolve("tamework-state.sqlite"));

        assertEquals(CompanionStorage.LegacyKind.LEGACY_3X_4X,
                CompanionStorage.detectLegacy(ROOT, List.of(legacyPluginDir, DATA), present::contains));
    }

    @Test
    void anExistingStoreHasNoBlockingOldSaves() {
        Set<Path> present = Set.of(DATA.resolve("tamework.sqlite"), CompanionStorage.metaFile(ROOT));
        assertNull(CompanionStorage.detectLegacy(ROOT, List.of(DATA), present::contains));
    }

    @Test
    void anExistingStoreIsReadyEvenWithOldFilesBeside() {
        Set<Path> present = Set.of(DATA.resolve("tamework-state.sqlite"), CompanionStorage.metaFile(ROOT));
        assertEquals(CompanionStorage.Status.READY, CompanionStorage.detect(ROOT, List.of(DATA), present::contains));
    }
}
