package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for Interaction context matching. */
class InteractionContextMatchTest {

    @Test
    void contextParamOverridesContextValue() throws Exception {
        ActionTameworkInteract owner = newInteract();
        InteractionParamAccess paramAccess = newParamAccess();
        InteractionAlarmHelper alarmHelper = new InteractionAlarmHelper(owner);
        InteractionMatchHelpers helpers = new InteractionMatchHelpers(owner, paramAccess, alarmHelper);

        InteractionContextRequirement requirement = new InteractionContextRequirement();
        setField(requirement, "context", "DefaultContext");
        setField(requirement, "contextParam", "CtxParam");

        StdScope scope = new StdScope(null);
        scope.addConst("CtxParam", "ParamContext");
        Role role = newRoleWithScope(scope);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, new StdScope[] { scope });

        Method method = InteractionMatchHelpers.class.getDeclaredMethod(
                "resolveInteractionContextParam",
                InteractionContextRequirement.class,
                Role.class,
                InteractionContextSnapshot.class
        );
        method.setAccessible(true);
        String resolved = (String) method.invoke(helpers, requirement, role, ctx);
        assertEquals("ParamContext", resolved);
    }

    @Test
    void blankContextRespectsAllowBlankFlag() throws Exception {
        ActionTameworkInteract owner = newInteract();
        InteractionParamAccess paramAccess = newParamAccess();
        InteractionAlarmHelper alarmHelper = new InteractionAlarmHelper(owner);
        InteractionMatchHelpers helpers = new InteractionMatchHelpers(owner, paramAccess, alarmHelper);

        assertTrue(helpers.matchesInteractionContext("", null, null, true));
        assertFalse(helpers.matchesInteractionContext("", null, null, false));
    }

    private static InteractionParamAccess newParamAccess() {
        InteractionParamResolver resolver = new InteractionParamResolver(null, null, null);
        return new InteractionParamAccess(
                resolver,
                false,
                null,
                null,
                null,
                "LovedItems",
                "IsHarvestable",
                "IsMountable"
        );
    }

    private static ActionTameworkInteract newInteract() throws Exception {
        Unsafe unsafe = getUnsafe();
        return (ActionTameworkInteract) unsafe.allocateInstance(ActionTameworkInteract.class);
    }

    private static Role newRoleWithScope(StdScope scope) throws Exception {
        Unsafe unsafe = getUnsafe();
        Role role = (Role) unsafe.allocateInstance(Role.class);
        EntitySupport entitySupport = (EntitySupport) unsafe.allocateInstance(EntitySupport.class);

        Field sensorScopeField = EntitySupport.class.getDeclaredField("sensorScope");
        sensorScopeField.setAccessible(true);
        sensorScopeField.set(entitySupport, scope);

        Field entitySupportField = Role.class.getDeclaredField("entitySupport");
        entitySupportField.setAccessible(true);
        entitySupportField.set(role, entitySupport);

        return role;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
