package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class CommandRespawnServiceTest {
    @Test
    void createRespawnNeedsComponentResetsTransientNeedsState() throws Exception {
        Constructor<TwNeedsConfig> constructor = TwNeedsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwNeedsConfig config = constructor.newInstance();

        TameworkNeedsComponent component = CommandRespawnService.createRespawnNeedsComponent(config, 1234L);

        assertEquals(config.getId(), component.getConfigId());
        assertEquals(config.getValues().getHungerDefault(), component.getHunger(), 0.000001);
        assertEquals(config.getValues().getThirstDefault(), component.getThirst(), 0.000001);
        assertEquals(0.0, component.getAppliedHappinessPenalty(), 0.000001);
        assertEquals(0.0, component.getPendingNeedsDamage(), 0.000001);
        assertEquals(1234L, component.getLastUpdateMs());
        assertEquals(1234L, component.getLastPassiveSweepMs());
        assertEquals(-1.0, component.getRegenSuppressionBaselineHealth(), 0.000001);
        assertEquals(0.0, component.getRegenSuppressionAllowedHeal(), 0.000001);
        assertEquals(-1.0, component.getLastManagedHealth(), 0.000001);
    }
}
