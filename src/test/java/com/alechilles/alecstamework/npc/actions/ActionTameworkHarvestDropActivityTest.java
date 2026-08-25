package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.npc.compat.NpcSupportTestFixture;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
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

    @Test
    void manualHarvestUsesEffectiveVariantScope() throws Exception {
        StdScope effectiveScope = new StdScope(null);
        effectiveScope.addConst("HarvestInteractionContext", "Shear");
        BuilderActionTameworkHarvestDrop builder = new BuilderActionTameworkHarvestDrop();
        BuilderSupport support = new BuilderSupport(
                new BuilderManager(),
                new NPCEntity(),
                null,
                new ExecutionContext(),
                builder,
                new RoleStats()
        );
        support.setGlobalScope(effectiveScope);
        Role role = NpcSupportTestFixture.bindRoleWithSensorScope(new StdScope(null));

        assertEquals(
                "Shear",
                ActionTameworkHarvestDrop.resolveActivityContext(
                        role,
                        true,
                        InteractionRoleParameterScope.snapshot(support)
                )
        );
    }
}
