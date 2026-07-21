package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionRevivePolicyTest {
    @Test
    void reviveRequiresEffectiveFeatureAndEitherCommandOrCanonicalAuthority() {
        UUID owner = UUID.randomUUID();
        TameworkCommandLinksComponent linked =
                new TameworkCommandLinksComponent(owner, new String[]{"tool-a"});
        TameworkCommandLinksComponent empty =
                new TameworkCommandLinksComponent(owner, new String[0]);

        assertTrue(CompanionRevivePolicy.supportsRevive(linked, true));
        assertFalse(CompanionRevivePolicy.supportsRevive(linked, false));
        assertFalse(CompanionRevivePolicy.supportsRevive(empty, true));
        assertFalse(CompanionRevivePolicy.supportsRevive(null, true));
        assertTrue(CompanionRevivePolicy.supportsRevive(empty, true, true));
        assertFalse(CompanionRevivePolicy.supportsRevive(empty, false, true));
    }
}
