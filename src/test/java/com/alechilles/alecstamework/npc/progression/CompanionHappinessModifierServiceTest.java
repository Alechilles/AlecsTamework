package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests disposition scaling behavior for happiness equilibrium modifiers.
 */
class CompanionHappinessModifierServiceTest {
    @Test
    void applyDispositionToOffsetScalesPositiveGainsDirectly() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(10.0, 1.2);
        assertEquals(12.0, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetSoftensDetractorsWhenDispositionIsHigh() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(-10.0, 1.2);
        assertEquals(-8.333333, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetAmplifiesDetractorsWhenDispositionIsLow() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(-10.0, 0.8);
        assertEquals(-12.5, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetFallsBackToNeutralForInvalidMultiplier() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(10.0, -1.0);
        assertEquals(10.0, adjusted, 0.000001);
    }

    @Test
    void husbandryDispositionScalesPositiveAndNegativeOffsets() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Ref<EntityStore> npcRef = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(UUID.fromString("50000000-0000-0000-0000-000000000001"));
            npc.setRoleName("RoleWithoutHappinessConfig");
            store.put(npcRef, NPCEntity.getComponentType(), npc);

            HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
            registry.register(context -> {
                assertEquals(HusbandryOutcomeKind.HAPPINESS_DISPOSITION, context.kind());
                return new HusbandryOutcomeModifiers(1.0, 1.30, 0.0, 0.0, 1.0);
            });
            installRuntime(registry);
            try {
                TwHappinessConfig positiveConfig = happinessConfig(true);
                setOwnerNearbyOffset(positiveConfig, 10.0);
                CompanionHappinessModifierService.ModifierSnapshot positive =
                        CompanionHappinessModifierService.resolve(npcRef, store, positiveConfig);
                assertEquals(13.0, positive.modifiers().get(0).value(), 0.000001);

                TwHappinessConfig negativeConfig = happinessConfig(true);
                setOwnerNearbyOffset(negativeConfig, -10.0);
                CompanionHappinessModifierService.ModifierSnapshot negative =
                        CompanionHappinessModifierService.resolve(npcRef, store, negativeConfig);
                assertEquals(-10.0 / 1.30, negative.modifiers().get(0).value(), 0.000001);
            } finally {
                clearRuntime(registry);
                registry.close();
            }
        }
    }

    @Test
    void runtimeHappinessGateRequiresEnabledConfigAndEnabledSettings() throws Exception {
        TwHappinessConfig enabledConfig = happinessConfig(true);
        TwHappinessConfig disabledConfig = happinessConfig(false);
        TameworkRuntimeSettings enabledSettings = TameworkRuntimeSettings.from(settingsWithHappinessEnabled(true));
        TameworkRuntimeSettings disabledSettings = TameworkRuntimeSettings.from(settingsWithHappinessEnabled(false));

        assertTrue(HappinessConfigResolver.isRuntimeEnabled(enabledConfig, enabledSettings));
        assertFalse(HappinessConfigResolver.isRuntimeEnabled(enabledConfig, disabledSettings));
        assertFalse(HappinessConfigResolver.isRuntimeEnabled(disabledConfig, enabledSettings));
        assertFalse(HappinessConfigResolver.isRuntimeEnabled(null, enabledSettings));
    }

    @Test
    void clearCacheInvalidatesPopulationSnapshot() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install()) {
            CompanionPopulationSpatialIndex index = CompanionPopulationSpatialIndex.shared();
            UUID sourceId = UUID.randomUUID();

            try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
                index.remove(store);
                addNpc(store, sourceId, "Cat_Adult", 0.0, 0.0, 0.0);
                TwBreedingConfig breedingConfig = breedingConfig();

                assertEquals(0, index.countNearby(
                        store,
                        sourceId,
                        new Vector3d(0.0, 0.0, 0.0),
                        4.0,
                        "cat_adult",
                        breedingConfig
                ));
                addNpc(store, UUID.randomUUID(), "Cat_Adult", 1.0, 0.0, 0.0);
                assertEquals(0, index.countNearby(
                        store,
                        sourceId,
                        new Vector3d(0.0, 0.0, 0.0),
                        4.0,
                        "cat_adult",
                        breedingConfig
                ));

                CompanionHappinessModifierService.clearCache();

                assertEquals(1, index.countNearby(
                        store,
                        sourceId,
                        new Vector3d(0.0, 0.0, 0.0),
                        4.0,
                        "cat_adult",
                        breedingConfig
                ));
                index.remove(store);
            }
        }
    }

    private static Ref<EntityStore> addNpc(TestEntityComponentStore store,
                                            UUID uuid,
                                            String roleId,
                                            double x,
                                            double y,
                                            double z) {
        Ref<EntityStore> reference = store.createReference();
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(uuid);
        npc.setRoleName(roleId);
        store.put(reference, NPCEntity.getComponentType(), npc);
        store.put(reference, TransformComponent.getComponentType(),
                new TransformComponent(new Vector3d(x, y, z), new Rotation3f()));
        return reference;
    }

    private static TwBreedingConfig breedingConfig() throws Exception {
        var ctor = TwBreedingConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static TwHappinessConfig happinessConfig(boolean enabled) throws Exception {
        var ctor = TwHappinessConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwHappinessConfig config = ctor.newInstance();
        setField(config, "enabled", enabled);
        return config;
    }

    private static void setOwnerNearbyOffset(TwHappinessConfig config, double offset) throws Exception {
        setField(config.getModifiers(), "ownerNearbyOffset", offset);
    }

    private static void installRuntime(HusbandryOutcomeApi api) throws Exception {
        invokeRuntime("install", api);
    }

    private static void clearRuntime(HusbandryOutcomeApi api) throws Exception {
        invokeRuntime("clear", api);
    }

    private static void invokeRuntime(String methodName, HusbandryOutcomeApi api) throws Exception {
        Method method = HusbandryOutcomeRuntime.class.getDeclaredMethod(
                methodName, HusbandryOutcomeApi.class);
        method.setAccessible(true);
        method.invoke(null, api);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ResolvedTameworkSettings settingsWithHappinessEnabled(boolean enabled) {
        ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
        return new ResolvedTameworkSettings(
                defaults.populationLimitPerPlayerOwnedTotal(),
                defaults.populationPerPlayerLimitScope(),
                defaults.simpleClaimsEnabled(),
                defaults.simpleClaimsLimitPerClaimChunk(),
                defaults.simpleClaimsLimitPerClaimTotal(),
                defaults.simpleClaimsBreedingRequiresClaim(),
                defaults.simpleClaimsProtectTamedFromNonMembers(),
                defaults.blockOwnerDamage(),
                defaults.blockAllPlayerDamageIfOwned(),
                defaults.invulnerableIfOwned(),
                defaults.captureClearsOwner(),
                defaults.spawnSetsOwner(),
                defaults.captureRequiresOwner(),
                defaults.spawnRequiresOwner(),
                defaults.interactionRequiresOwner(),
                defaults.linkingRequiresOwner(),
                defaults.needsEnabled(),
                defaults.needsResourceMode(),
                defaults.needsTickPolicyMode(),
                defaults.needsOwnerOfflineGraceHours(),
                defaults.needsOwnerOfflineDecayMultiplier(),
                defaults.needsDamageEnabled(),
                defaults.needsDamageModel(),
                defaults.needsDamageDualNeedRule(),
                defaults.needsStarvationDamagePerMinute(),
                defaults.needsDehydrationDamagePerMinute(),
                defaults.needsDamageLethal(),
                enabled,
                defaults.passiveBreedingEnabled(),
                defaults.breedingRequiresHappiness(),
                defaults.breedingGenderEnabled(),
                defaults.traitsEnabled(),
                defaults.levelingEnabled(),
                defaults.talentsEnabled(),
                defaults.reviveSystemEnabled(),
                defaults.recallTeleportingEnabled(),
                defaults.telemetryEnabled(),
                defaults.telemetryBreadcrumbsEnabled()
        );
    }
}

