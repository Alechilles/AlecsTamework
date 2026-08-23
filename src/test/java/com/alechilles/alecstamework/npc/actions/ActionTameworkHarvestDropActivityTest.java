package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.npc.compat.NpcSupportTestFixture;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Regression checks for harvest context carried by drop-based activities. */
class ActionTameworkHarvestDropActivityTest {
    @AfterEach
    void clearNpcSupport() {
        NpcSupportTestFixture.clear();
    }

    @Test
    void manualHarvestUsesRoleContextButPassiveOutputDoesNot() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("HarvestInteractionContext", "Shear");
        Role role = NpcSupportTestFixture.bindRoleWithSensorScope(scope);

        assertEquals(
                "Shear",
                ActionTameworkHarvestDrop.resolveActivityContext(role, true)
        );
        assertNull(ActionTameworkHarvestDrop.resolveActivityContext(role, false));
    }
}
