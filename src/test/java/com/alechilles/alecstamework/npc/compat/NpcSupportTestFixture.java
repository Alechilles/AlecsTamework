package com.alechilles.alecstamework.npc.compat;

import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Provides a scoped Update 6 entity-support binding for role parameter tests. */
public final class NpcSupportTestFixture {
    private NpcSupportTestFixture() {
    }

    public static Role bindRoleWithSensorScope(StdScope scope) throws ReflectiveOperationException {
        Role role = (Role) unsafe().allocateInstance(Role.class);
        EntitySupport entitySupport = new EntitySupport();
        setField(EntitySupport.class, entitySupport, "sensorScope", scope);
        ExecutionSupport support = new ExecutionSupport();
        setField(ExecutionSupport.class, support, "entitySupport", entitySupport);
        NpcSupportAccess.pushBound(role, support);
        return role;
    }

    public static void clear() {
        NpcSupportAccess.restoreBound(null);
    }

    private static void setField(Class<?> owner,
                                 Object target,
                                 String name,
                                 Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
