package com.alechilles.alecstamework.api.commandhud;

import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for detached command HUD identifiers and contributions. */
class CommandHudContractTest {
    @Test
    void idsNormalizeAndRejectReservedOrUnnamespacedValues() {
        assertEquals("runeteria:hud",
                CommandHudRendererId.of(" Runeteria:HUD ").value());
        assertEquals("runeteria:badge",
                CommandHudContributorId.of("Runeteria:Badge").value());

        assertThrows(IllegalArgumentException.class,
                () -> CommandHudRendererId.of("tamework:internal"));
        assertThrows(IllegalArgumentException.class,
                () -> CommandHudContributorId.of("not-namespaced"));
        assertFalse(CommandHudRendererId.tryParse("tamework:internal").isPresent());
    }

    @Test
    void contributionCopiesCallerDataAndExposesItAsImmutable() {
        Map<String, CommandUiValue> data = new LinkedHashMap<>();
        data.put("ready", CommandUiValue.of(true));

        CommandHudContribution contribution = CommandHudContribution.available(
                CommandHudContributorId.of("runeteria:target"), data);
        data.put("ready", CommandUiValue.of(false));
        data.put("other", CommandUiValue.of(true));

        assertTrue(contribution.data().get("ready").booleanValue());
        assertThrows(UnsupportedOperationException.class,
                () -> contribution.data().put("other", CommandUiValue.of(true)));
        assertEquals(CommandHudContributionStatus.AVAILABLE, contribution.status());
    }

    @Test
    void dirtyScopePromotesMoreThan256PathsToFullRefresh() {
        Set<String> paths = new LinkedHashSet<>();
        for (int index = 0; index < 257; index++) {
            paths.add("indicator/" + index);
        }

        CommandHudDirtyScope scope = CommandHudDirtyScope.paths(paths);

        assertTrue(scope.fullRefresh());
        assertTrue(scope.paths().isEmpty());
    }
}
