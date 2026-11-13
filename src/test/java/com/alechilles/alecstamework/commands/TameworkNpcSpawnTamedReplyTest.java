package com.alechilles.alecstamework.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import org.junit.jupiter.api.Test;

/** The console spawn's population refusal says which check refused and with what numbers. */
class TameworkNpcSpawnTamedReplyTest {
    private static final TwGlobalConfig.PerPlayerLimitScope SCOPE = TwGlobalConfig.PerPlayerLimitScope.GLOBAL;

    @Test
    void aReachedOwnedLimitGivesTheNumbersTheCheckUsed() {
        var counted = new OwnerPopulationCapService.Decision(false, true, 5, 5, 0, SCOPE, "owner-cap-reached");
        var precheck = new OwnerPopulationCapService.Decision(false, true, 5, -1, 0, SCOPE, "owner-cap-reached",
                "tamework.ui.population.ownedLimit");

        assertEquals(" detail=owned-limit owned=5 cap=5", TameworkNpcSpawnTamedCommand.populationCapDetail(counted));
        assertEquals(" detail=owned-limit key=tamework.ui.population.ownedLimit cap=5",
                TameworkNpcSpawnTamedCommand.populationCapDetail(precheck));
    }

    @Test
    void aProviderDenialNamesTheProvidersMessageAndNoOwnedLimit() {
        var denied = new OwnerPopulationCapService.Decision(false, true, 20, -1, 0, SCOPE,
                OwnerPopulationCapService.REASON_PROVIDER_DENIED, "runehusbandry.admission.family_locked");

        assertEquals(" detail=provider-denied key=runehusbandry.admission.family_locked",
                TameworkNpcSpawnTamedCommand.populationCapDetail(denied));
    }
}
