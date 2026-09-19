package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionRevivePolicyTest {
    @Test
    void onlyTheFatalOldAgeSourceMakesDeathPermanent() {
        class FatalEvent extends com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent {
            private final com.hypixel.hytale.server.core.modules.entity.damage.Damage damage;
            FatalEvent(String source) {
                damage = new com.hypixel.hytale.server.core.modules.entity.damage.Damage(
                        new com.hypixel.hytale.server.core.modules.entity.damage.Damage.EnvironmentSource(source),
                        0, 1f);
            }
            @Override public com.hypixel.hytale.server.core.modules.entity.damage.Damage getDeathInfo() {
                return damage;
            }
        }
        assertTrue(CompanionRevivePolicy.isOldAgeDeath(new FatalEvent("tamework.old_age")));
        assertFalse(CompanionRevivePolicy.isOldAgeDeath(new FatalEvent("starvation")));
        assertFalse(CompanionRevivePolicy.isOldAgeDeath(null));
    }

    @Test
    void reviveRequiresEffectiveFeatureAndCommandLink() {
        UUID owner = UUID.randomUUID();
        TameworkCommandLinksComponent linked =
                new TameworkCommandLinksComponent(owner, new String[]{"tool-a"});
        TameworkCommandLinksComponent empty =
                new TameworkCommandLinksComponent(owner, new String[0]);

        assertTrue(CompanionRevivePolicy.supportsRevive(linked, true));
        assertFalse(CompanionRevivePolicy.supportsRevive(linked, false));
        assertFalse(CompanionRevivePolicy.supportsRevive(empty, true));
        assertFalse(CompanionRevivePolicy.supportsRevive(null, true));
    }
}
