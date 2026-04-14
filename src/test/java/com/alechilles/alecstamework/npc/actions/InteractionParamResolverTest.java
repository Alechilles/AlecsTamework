package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void stringArrayParamLookupCachesScalarFallbackAfterFirstMiss() throws Exception {
        CountingStringFallbackScope primary = new CountingStringFallbackScope();
        InteractionParamResolver resolver = new InteractionParamResolver(null, null, null);
        Role role = newRoleWithScope(primary);

        assertEquals("test:item", resolver.getStringArrayParam(role, null, "FeedItems")[0]);
        assertEquals("test:item", resolver.getStringArrayParam(role, null, "FeedItems")[0]);
        assertEquals(1, primary.stringArrayAttempts);
        assertEquals(2, primary.stringAttempts);
    }

    @Test
    void stringArrayParamLookupCachesMissingParamAfterFirstFailure() throws Exception {
        CountingMissingScope primary = new CountingMissingScope();
        InteractionParamResolver resolver = new InteractionParamResolver(null, null, null);
        Role role = newRoleWithScope(primary);

        assertNull(resolver.getStringArrayParam(role, null, "MissingParam"));
        assertNull(resolver.getStringArrayParam(role, null, "MissingParam"));
        assertEquals(1, primary.stringArrayAttempts);
        assertEquals(1, primary.stringAttempts);
    }

    @Test
    void stringParamLookupCachesMissingParamAfterFirstFailure() throws Exception {
        CountingMissingScope primary = new CountingMissingScope();
        InteractionParamResolver resolver = new InteractionParamResolver(null, null, null);
        Role role = newRoleWithScope(primary);

        assertNull(resolver.getStringParam(role, null, "MissingStringParam"));
        assertNull(resolver.getStringParam(role, null, "MissingStringParam"));
        assertEquals(1, primary.stringAttempts);
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

    private static final class CountingStringFallbackScope extends StdScope {
        private int stringArrayAttempts;
        private int stringAttempts;

        private CountingStringFallbackScope() {
            super(null);
        }

        @Override
        public Supplier<String[]> getStringArraySupplier(String name) {
            stringArrayAttempts++;
            throw new IllegalStateException("Not a string array");
        }

        @Override
        public Supplier<String> getStringSupplier(String name) {
            stringAttempts++;
            return () -> "test:item";
        }
    }

    private static final class CountingMissingScope extends StdScope {
        private int stringArrayAttempts;
        private int stringAttempts;

        private CountingMissingScope() {
            super(null);
        }

        @Override
        public Supplier<String[]> getStringArraySupplier(String name) {
            stringArrayAttempts++;
            throw new IllegalStateException("Missing symbol");
        }

        @Override
        public Supplier<String> getStringSupplier(String name) {
            stringAttempts++;
            throw new IllegalStateException("Missing symbol");
        }
    }
}
