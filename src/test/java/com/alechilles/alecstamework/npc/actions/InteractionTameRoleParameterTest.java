package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import org.junit.jupiter.api.Test;

/** Prevents taming a variant with the generic role when its parameter is not exported. */
class InteractionTameRoleParameterTest {
    @Test
    void interactionResolvesDeclaredTameRoleAlongsideEffectiveGlobalParameters() {
        BuilderActionTameworkInteract builder = new BuilderActionTameworkInteract() {
            private final BuilderParameters parameters = new TestRoleParameters();

            @Override
            public BuilderParameters getBuilderParameters() {
                return parameters;
            }
        };
        StdScope global = new StdScope(null);
        global.addConst("HarvestInteractionContext", "Shear");
        BuilderSupport support = new BuilderSupport(
                new BuilderManager(), new NPCEntity(), null,
                new ExecutionContext(), builder, new RoleStats());
        support.setGlobalScope(global);

        ActionTameworkInteract interaction = new ActionTameworkInteract(builder, support, false);

        assertEquals("Cat_Bobtail_Pet", interaction.getRoleStringParam(null, "TamedRoleId"));
        assertEquals("Shear", interaction.getRoleStringParam(null, "HarvestInteractionContext"));
    }

    private static final class TestRoleParameters extends BuilderParameters {
        private TestRoleParameters() {
            super(new StdScope(null), "TestBobtail", null);
            getScope().addConst("TamedRoleId", "Cat_Bobtail_Pet");
        }
    }
}
