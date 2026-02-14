package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for Interaction parameter resolution order. */
class InteractionParamResolverTest {

    @Test
    void paramResolverUsesExpectedScopeOrder() throws Exception {
        StdScope primary = new StdScope(null);
        primary.addConst("Param", "Primary");
        StdScope global = new StdScope(null);
        global.addConst("Param", "Global");
        StdScope exec = new StdScope(null);
        exec.addConst("Param", "Exec");
        StdScope sensor = new StdScope(null);
        sensor.addConst("Param", "Sensor");

        InteractionParamResolver resolver = new InteractionParamResolver(global, exec, sensor);
        Role roleWithPrimary = newRoleWithScope(primary);
        assertEquals("Primary", resolver.getStringParam(roleWithPrimary, null, "Param"));

        Role roleWithoutPrimary = newRoleWithScope(new StdScope(null));
        assertEquals("Global", resolver.getStringParam(roleWithoutPrimary, null, "Param"));

        InteractionParamResolver resolverNoGlobal = new InteractionParamResolver(null, exec, sensor);
        assertEquals("Exec", resolverNoGlobal.getStringParam(roleWithoutPrimary, null, "Param"));
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

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
