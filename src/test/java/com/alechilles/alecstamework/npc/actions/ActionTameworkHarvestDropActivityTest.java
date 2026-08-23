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
    void manualHarvestUsesCapturedRoleParametersWhenLiveScopeHasNoContext() throws Exception {
        StdScope liveScope = new StdScope(null);
        StdScope roleParameters = new StdScope(null);
        roleParameters.addConst("HarvestInteractionContext", "Shear");
        Role role = NpcSupportTestFixture.bindRoleWithSensorScope(liveScope);

        assertEquals(
                "Shear",
                ActionTameworkHarvestDrop.resolveActivityContext(role, true, roleParameters)
        );
        assertNull(ActionTameworkHarvestDrop.resolveActivityContext(role, false, roleParameters));
    }
}
