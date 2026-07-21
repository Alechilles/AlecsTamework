package com.alechilles.alecstamework.config.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class TwSpawnerVesselConfigTest {
    @Test
    void omittedVesselRemainsDisposableAndDoesNotChangeLegacySpawnerMechanics() {
        ItemFeatureConfig runtime = new TwSpawnerConfig().toItemFeatureConfig();

        assertEquals(BondedVesselMode.DISPOSABLE, runtime.getVesselMechanics().mode());
        assertEquals(0L, runtime.getVesselMechanics().transitionCooldownMs());
        assertTrue(runtime.getVesselMechanics().requireOwner());
        assertFalse(runtime.getVesselMechanics().allowStoreInCombat());
    }

    @Test
    void codecMapsBondedSettingsAndFallsBackMissingStateItemsToFilledItem() throws Exception {
        TwSpawnerConfig config = TwSpawnerConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "EmptyItemId": "Draconic_Stone",
                  "FilledItemId": "Draconic_Stone_Filled",
                  "Vessel": {
                    "Mode": "Bonded",
                    "StateItemIds": {
                      "Active": "Draconic_Stone_Active",
                      "Dead": "Draconic_Stone_Damaged"
                    },
                    "TransitionCooldownMs": 10000,
                    "StoreMaxDistance": 12.0,
                    "StoreParticleSystem": "HyDragon_Store",
                    "StoreSoundEvent": "SFX_HyDragon_Store",
                    "RequireOwner": true,
                    "AllowStoreInCombat": false
                  }
                }
                """), new ExtraInfo());
        setField(config, "id", "TwSpawnerConfig_Draconic_Stone");

        SpawnerVesselConfigView view = config.toVesselConfigView(7L);

        assertEquals(BondedVesselMode.BONDED, view.mode());
        assertEquals("Draconic_Stone_Filled", view.storedItemId());
        assertEquals("Draconic_Stone_Active", view.activeItemId());
        assertEquals("Draconic_Stone_Damaged", view.deadItemId());
        assertEquals("Draconic_Stone_Filled", view.lostItemId());
        assertEquals("Draconic_Stone_Filled", view.unavailableItemId());
        assertEquals(10_000L, view.transitionCooldownMs());
        assertEquals(12.0D, view.storeMaxDistance());
        assertTrue(config.matchesVesselItemId("Draconic_Stone_Damaged"));
        assertFalse(config.matchesVesselItemId("Some_Other_Item"));
    }

    @Test
    void partialVesselSectionInheritsScalarsButExplicitStateMapReplacesParent() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();
        TwSpawnerVesselSettings parentVessel = vessel(BondedVesselMode.BONDED,
                Map.of("Stored", "Parent_Stored", "Dead", "Parent_Dead"), 10_000, 12.0D);
        TwSpawnerVesselSettings childVessel = vessel(BondedVesselMode.DISPOSABLE,
                Map.of("Dead", "Child_Dead"), 0, 0.0D);
        setField(parent, "vessel", parentVessel);
        setField(child, "vessel", childVessel);

        child.inheritMissingTopLevelFrom(parent, Set.of("Vessel"),
                Map.of("Vessel", Set.of("Mode", "StateItemIds")));

        assertEquals(BondedVesselMode.DISPOSABLE, child.getVessel().getMode());
        assertEquals(Map.of("Dead", "Child_Dead"), child.getVessel().getStateItemIds());
        assertEquals(10_000, child.getVessel().getTransitionCooldownMs());
        assertEquals(12.0D, child.getVessel().getStoreMaxDistance());
    }

    @Test
    void omittedVesselSectionInheritsCompleteParentObject() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();
        TwSpawnerVesselSettings parentVessel = vessel(BondedVesselMode.BONDED,
                Map.of("Stored", "Parent_Stored"), 10_000, 12.0D);
        setField(parent, "vessel", parentVessel);

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertSame(parentVessel, child.getVessel());
    }

    @Test
    void invalidModeStateKeyAndNegativePolicyValuesFailClosed() {
        assertThrows(RuntimeException.class, () -> TwSpawnerConfig.CODEC.decode(
                BsonDocument.parse("{\"Vessel\":{\"Mode\":\"Reusable\"}}"), new ExtraInfo()));
        assertThrows(RuntimeException.class, () -> TwSpawnerConfig.CODEC.decode(
                BsonDocument.parse("{\"Vessel\":{\"StateItemIds\":{\"Broken\":\"Item\"}}}"),
                new ExtraInfo()));
        TwSpawnerVesselSettings negative = new TwSpawnerVesselSettings();
        assertThrows(IllegalArgumentException.class, () -> {
            setField(negative, "transitionCooldownMs", -1);
            negative.validate("config", "empty", "filled");
        });
    }

    private static TwSpawnerVesselSettings vessel(BondedVesselMode mode,
                                                   Map<String, String> stateItems,
                                                   int cooldownMs,
                                                   double distance) throws Exception {
        TwSpawnerVesselSettings settings = new TwSpawnerVesselSettings();
        setField(settings, "mode", mode);
        setField(settings, "stateItemIds", stateItems);
        setField(settings, "transitionCooldownMs", cooldownMs);
        setField(settings, "storeMaxDistance", distance);
        return settings;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
