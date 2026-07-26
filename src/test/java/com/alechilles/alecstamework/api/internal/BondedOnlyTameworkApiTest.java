package com.alechilles.alecstamework.api.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the emergency API exposed when generic persistence cannot compose. */
class BondedOnlyTameworkApiTest {
    @TempDir Path temporaryDirectory;

    @Test
    void advertisesAndReturnsBondedOnlyWhenItsIntegrationsAreReady()
            throws Exception {
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(), null, () -> -10L
                );
        BondedOnlyTameworkApi api = new BondedOnlyTameworkApi(composition.api());
        try {
            assertFalse(api.getCapabilities().contains(
                    TameworkApiCapability.BONDED_COMPANIONS));
            try (AutoCloseable capture =
                         composition.registerCaptureIntegration();
                 AutoCloseable panel = composition.registerPanelIntegration()) {
                assertTrue(api.getCapabilities().contains(
                        TameworkApiCapability.BONDED_COMPANIONS));
                assertSame(composition.api(), api.bondedCompanions());
            }
        } finally {
            composition.close();
        }
    }
}
