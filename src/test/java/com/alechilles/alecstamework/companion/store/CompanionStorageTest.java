package com.alechilles.alecstamework.companion.store;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void anExistingStoreIsReadyEvenWithOldFilesBeside() {
        Set<Path> present = Set.of(DATA.resolve("tamework-state.sqlite"), CompanionStorage.metaFile(ROOT));
        assertEquals(CompanionStorage.Status.READY, CompanionStorage.detect(ROOT, List.of(DATA), present::contains));
    }
}
