package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderModifier;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.StdScope;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelCooldownSnapshotServiceTest {

    @Test
    void harvestCooldownRatioUsesNegativeWorldTimeWindow() {
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot snapshot =
                CommandLinkedPanelCooldownSnapshotService.fromAlarmWindow(
                        true,
                        true,
                        -5_000L,
                        10_000L,
                        -10_000L,
                        20_000L,
                        null
                );

        assertTrue(snapshot.known);
        assertTrue(snapshot.active);
        assertEquals(0.25, snapshot.ratio, 0.0001);
    }

    @Test
    void inactiveHarvestCooldownSnapshotsAreKnownAndReady() {
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot snapshot =
                CommandLinkedPanelCooldownSnapshotService.fromAlarmWindow(
                        true,
                        false,
                        10_000L,
                        10_000L,
                        -10_000L,
                        20_000L,
                        null
                );

        assertTrue(snapshot.known);
        assertFalse(snapshot.active);
        assertEquals(0L, snapshot.remainingMs);
        assertEquals(1.0, snapshot.ratio, 0.0001);
    }

    @Test
    void activeSignedWindowSaturatesRemainingDurationWithoutWrapping() {
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot snapshot =
                CommandLinkedPanelCooldownSnapshotService.fromAlarmWindow(
                        true,
                        true,
                        Long.MIN_VALUE,
                        Long.MAX_VALUE,
                        Long.MIN_VALUE + 1L,
                        Long.MAX_VALUE,
                        null
                );

        assertTrue(snapshot.active);
        assertEquals(
                BreedingTimeService.toEstimatedRealDurationMs(Long.MAX_VALUE, null),
                snapshot.remainingMs
        );
        assertEquals(0.0, snapshot.ratio, 0.0001);
    }

    @Test
    void harvestReadinessRequiresAnEnabledSupportedHarvestInteraction() {
        TwInteractionConfig config = configWith("{\"Type\":\"Harvest\"}");
        assertFalse(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(config, false));
        assertTrue(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(config, true));
        assertFalse(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(
                configWith("{\"Type\":\"Harvest\",\"Enabled\":false}"), true));
    }

    @Test
    void harvestInteractionCanExplicitlyOptOutOfTheHarvestabilityRequirement() {
        assertTrue(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(
                configWith("{\"Type\":\"Harvest\",\"RequireHarvestable\":false}"), false));
    }

    @Test
    void harvestCapabilityReadsDeclaredRoleParametersWhenSensorScopeOmitsThem() {
        var service = new CommandLinkedPanelCooldownSnapshotService();
        var sensor = new StdScope(null);
        var parameters = new StdScope(null);
        parameters.addConst("IsHarvestable", true);
        var config = configWith("{\"Type\":\"Harvest\"}");
        assertTrue(service.hasEnabledHarvestCapability(config, sensor, parameters, "IsHarvestable"));
        sensor.addConst("IsHarvestable", false);
        assertFalse(new CommandLinkedPanelCooldownSnapshotService()
                .hasEnabledHarvestCapability(config, sensor, parameters, "IsHarvestable"));
        assertFalse(service.hasEnabledHarvestCapability(config, null, null, "IsHarvestable"));
    }

    @Test
    void harvestCapabilityEvaluatesComputedVariantHarvestability() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        BuilderParameters parameters = parametersWithHarvestable(false);
        BuilderRole base = BuilderRole.class.cast(
                unsafe.allocateInstance(BuilderRole.class));
        setField(base, "builderParameters", parameters);
        RoleBuilderFixtureManager manager = new RoleBuilderFixtureManager(base);

        BuilderRoleVariant variant = new BuilderRoleVariant();
        setField(variant, "referenceIndex", 42);
        BuilderParameters variantParameters = parametersWithHarvestable(true);
        setField(variant, "builderParameters", variantParameters);
        setField(variant, "modifier", harvestabilityModifier(variantParameters));
        setField(variant, "builderManager", manager);

        var service = new CommandLinkedPanelCooldownSnapshotService();
        var config = configWith("{\"Type\":\"Harvest\"}");
        assertFalse(service.hasEnabledHarvestCapability(
                config, null, CommandLinkedPanelCooldownSnapshotService
                        .resolveRoleParameterScope(base), "IsHarvestable"));
        assertTrue(service.hasEnabledHarvestCapability(
                config, null, CommandLinkedPanelCooldownSnapshotService
                        .resolveRoleParameterScope(variant), "IsHarvestable"));
    }

    @Test
    void invalidRoleExpressionsCannotEscapeTheOptionalHarvestLookup() {
        BuilderRoleVariant invalid = new BuilderRoleVariant() {
            @Override
            public com.hypixel.hytale.server.npc.util.expression.Scope createExecutionScope() {
                throw new IllegalStateException("Invalid role expression");
            }
        };
        org.junit.jupiter.api.Assertions.assertNull(
                CommandLinkedPanelCooldownSnapshotService.resolveRoleParameterScope(invalid));
    }

    private static BuilderParameters parametersWithHarvestable(boolean harvestable)
            throws Exception {
        var constructor = BuilderParameters.class.getDeclaredConstructor(
                StdScope.class, String.class, String.class);
        constructor.setAccessible(true);
        BuilderParameters parameters = constructor.newInstance(
                new StdScope(null), "test", null);
        JsonObject root = new JsonObject();
        JsonObject entries = new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("Value", harvestable);
        entries.add("IsHarvestable", value);
        root.add("Parameters", entries);
        parameters.readJSON(root, new StateMappingHelper());
        parameters.addParametersToScope();
        return parameters;
    }

    private static BuilderModifier harvestabilityModifier(
            BuilderParameters parameters) {
        JsonObject root = new JsonObject();
        JsonObject modify = new JsonObject();
        JsonObject computed = new JsonObject();
        computed.addProperty("Compute", "IsHarvestable");
        modify.add("IsHarvestable", computed);
        root.add("Modify", modify);
        return BuilderModifier.fromJSON(root, parameters,
                new StateMappingHelper(), new ExtraInfo());
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
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

    private static TwInteractionConfig configWith(String entry) {
        return TwInteractionConfig.CODEC.decode(org.bson.BsonDocument.parse(
                "{\"Enabled\":true,\"Interactions\":[" + entry + "]}"), new com.hypixel.hytale.codec.ExtraInfo());
    }
}
