package com.alechilles.alecstamework.runtime;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkRuntimeActivationCoordinatorTest {
    private static final Path CURRENT = Path.of("universe", "Tamework", "Data");
    private static final Path OLDER = Path.of("mods", "Tamework");

    /** Without persistence neither the importer nor /tw persistence start-fresh can run on an old world. */
    @Test
    void anyOldSaveFileAloneStartsCompanionPersistence() {
        for (String name : List.of("tamework-state.sqlite", "bonded-companions.sqlite", "tamework.sqlite",
                "CommandLinkedNpcDeaths.dat")) {
            assertTrue(TameworkRuntimeActivationCoordinator.legacyDataEvidence(List.of(CURRENT, OLDER),
                    OLDER.resolve(name)::equals).hasDurableWork(), name);
        }
        assertFalse(TameworkRuntimeActivationCoordinator.legacyDataEvidence(List.of(CURRENT, OLDER),
                CURRENT.resolve("tamework-state.sqlite.v1-backup.1.sqlite")::equals).hasDurableWork());
        assertFalse(TameworkRuntimeActivationCoordinator.legacyDataEvidence(List.of(CURRENT, OLDER), p -> false)
                .hasDurableWork());
    }
}
