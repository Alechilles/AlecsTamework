package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.embedded.PatchworkMacroProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the stable, host-owned Patchwork contribution metadata. */
class TameworkPatchworkContributionTest {
    @Test
    void exposesStableHostMetadataAndImmutableProviders() {
        TameworkPatchworkContribution contribution = new TameworkPatchworkContribution("3.0.0-test");

        assertEquals("Alechilles:Alec's Tamework!", contribution.hostPluginIdentifier());
        assertEquals("3.0.0-test", contribution.contributionVersion());
        assertEquals(
                List.of("TameworkInteractionBridge", "TameworkHookInstruction", "TameworkStateInstruction"),
                contribution.macroProviders().stream().map(PatchworkMacroProvider::macroId).toList()
        );
        assertEquals(List.of(), contribution.targetAdapters());
        assertThrows(UnsupportedOperationException.class, () -> contribution.macroProviders().clear());
        assertThrows(UnsupportedOperationException.class, () -> contribution.targetAdapters().clear());
    }
}
