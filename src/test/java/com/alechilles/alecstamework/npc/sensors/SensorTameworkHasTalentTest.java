package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests self-targeted purchased-talent matching. */
class SensorTameworkHasTalentTest {

    @Test
    void matchingNpcPurchasedTalentMatchesConfiguredId() {
        TameworkTalentsComponent npcTalents = new TameworkTalentsComponent(
                "miniwyvern", 1, new String[] {"DraconicProjectile"}
        );

        assertTrue(SensorTameworkHasTalent.matchesTalent(npcTalents, "DraconicProjectile"));
    }

    @Test
    void anotherNpcsPurchasedTalentDoesNotMatchThisNpc() {
        TameworkTalentsComponent thisNpcTalents = new TameworkTalentsComponent(
                "miniwyvern", 0, new String[0]
        );
        TameworkTalentsComponent anotherNpcTalents = new TameworkTalentsComponent(
                "miniwyvern", 1, new String[] {"DraconicProjectile"}
        );

        assertFalse(SensorTameworkHasTalent.matchesTalent(thisNpcTalents, "DraconicProjectile"));
        assertTrue(SensorTameworkHasTalent.matchesTalent(anotherNpcTalents, "DraconicProjectile"));
    }

    @Test
    void missingTalentsOrBlankTalentIdFailClosed() {
        TameworkTalentsComponent npcTalents = new TameworkTalentsComponent(
                "miniwyvern", 1, new String[] {"DraconicProjectile"}
        );

        assertFalse(SensorTameworkHasTalent.matchesTalent(null, "DraconicProjectile"));
        assertFalse(SensorTameworkHasTalent.matchesTalent(npcTalents, " "));
    }
}
