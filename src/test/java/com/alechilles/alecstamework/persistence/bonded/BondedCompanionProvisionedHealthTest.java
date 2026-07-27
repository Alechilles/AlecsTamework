package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotPresentationMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderModifier;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.asset.builder.holder.IntHolder;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards newly provisioned bonded companions from losing their full-health panel data. */
class BondedCompanionProvisionedHealthTest {

    @Test
    void productionRoleReaderUsesConfiguredBuilderMaxHealthWithoutBuildingRole() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        BuilderRole role = roleWithMaxHealth(unsafe, 80);

        assertEquals(80.0D,
                HytaleBondedCompanionRoleHealthResolver.readMaximumHealth(
                        role, new BuilderManager(), new ExecutionContext()));
    }

    @Test
    void productionResolverUsesVariantModifierScopeWithoutBuildingLiveRole()
            throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        BuilderParameters parameters = parametersWithMaxHealth(100);
        BuilderRole base = roleWithComputedMaxHealth(unsafe, parameters);
        RoleBuilderFixtureManager manager = new RoleBuilderFixtureManager(base);
        BuilderRoleVariant variant = new BuilderRoleVariant();
        setField(variant, "referenceIndex", 42);
        setField(variant, "modifier", maxHealthModifier(parameters, 80));
        setField(variant, "builderManager", manager);

        assertEquals(80.0D,
                HytaleBondedCompanionRoleHealthResolver.resolveLoadedRole(
                        variant, manager));
    }

    @Test
    void miniwyvernProvisionStartsAtConfiguredFullHealthInPanelData() {
        var prepared = new BondedCompanionProvisioningSupport(roleId -> {
            assertEquals("Tamed_Wyvern_Mini", roleId);
            return 80.0D;
        }).prepare(
                new BondedCompanionProvisionRequest(
                        "test", "mini-health", UUID.randomUUID(),
                        "hydragon:horn", "Tamed_Wyvern_Mini",
                        "Ember", "Miniwyvern", null, Map.of(),
                        "hydragon:miniwyvern"),
                10L
        );

        var panel = new BondedCompanionSnapshotPresentationMapper(
                ignored -> new BondedCompanionSnapshotPresentationMapper
                        .RolePresentation(null, null, null, Map.of()))
                .map(prepared.snapshot());

        assertEquals("80.0", panel.data().get("currentHealth"));
        assertEquals("80.0", panel.data().get("maxHealth"));
        assertEquals("100.0", panel.data().get("healthPercent"));
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static BuilderRole roleWithMaxHealth(
            sun.misc.Unsafe unsafe, int health
    ) throws Exception {
        IntHolder maxHealth = new IntHolder();
        maxHealth.readJSON(new JsonPrimitive(health), null,
                "MaxHealth", null);
        BuilderRole role = BuilderRole.class.cast(
                unsafe.allocateInstance(BuilderRole.class));
        unsafe.putObject(role, unsafe.objectFieldOffset(
                field(BuilderRole.class, "maxHealth")), maxHealth);
        return role;
    }

    private static BuilderRole roleWithComputedMaxHealth(
            sun.misc.Unsafe unsafe, BuilderParameters parameters
    ) throws Exception {
        IntHolder maxHealth = new IntHolder();
        JsonObject expression = new JsonObject();
        expression.addProperty("Compute", "MaxHealth");
        maxHealth.readJSON(expression, null, "MaxHealth", parameters);
        BuilderRole role = BuilderRole.class.cast(
                unsafe.allocateInstance(BuilderRole.class));
        unsafe.putObject(role, unsafe.objectFieldOffset(
                field(BuilderRole.class, "maxHealth")), maxHealth);
        setField(role, "builderParameters", parameters);
        return role;
    }

    private static BuilderParameters parametersWithMaxHealth(int health)
            throws Exception {
        var constructor = BuilderParameters.class.getDeclaredConstructor(
                StdScope.class, String.class, String.class);
        constructor.setAccessible(true);
        BuilderParameters parameters = constructor.newInstance(
                new StdScope(null), "test", null);
        JsonObject root = new JsonObject();
        JsonObject entries = new JsonObject();
        JsonObject maxHealth = new JsonObject();
        maxHealth.addProperty("Value", health);
        entries.add("MaxHealth", maxHealth);
        root.add("Parameters", entries);
        parameters.readJSON(root, new StateMappingHelper());
        parameters.addParametersToScope();
        return parameters;
    }

    private static BuilderModifier maxHealthModifier(
            BuilderParameters parameters, int health
    ) {
        JsonObject root = new JsonObject();
        JsonObject modify = new JsonObject();
        modify.addProperty("MaxHealth", health);
        root.add("Modify", modify);
        return BuilderModifier.fromJSON(root, parameters,
                new StateMappingHelper(), new ExtraInfo());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = field(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RoleBuilderFixtureManager extends BuilderManager {
        private final BuilderRole role;

        private RoleBuilderFixtureManager(BuilderRole role) {
            this.role = role;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> com.hypixel.hytale.server.npc.asset.builder.Builder<T>
        getCachedBuilder(int index, Class<?> category) {
            return index == 42 && category == Role.class
                    ? (com.hypixel.hytale.server.npc.asset.builder.Builder<T>) role
                    : null;
        }
    }
}
