package com.alechilles.alecstamework.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BondedSpawnerConfigValidatorTest {
    @Test
    void acceptsSameConfigAliasesWhenEveryDefinitionIsNonStackable() {
        SpawnerVesselConfigView config = config("Dragon", "Dragon_Stored", "Dragon_Stored");
        Map<String, BondedSpawnerConfigValidator.ItemDefinition> definitions = definitions(config, 1, "Dragon");

        BondedSpawnerConfigValidator.ValidationResult result = BondedSpawnerConfigValidator.validate(
                java.util.List.of(config), item -> Optional.ofNullable(definitions.get(item)));

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsMissingStackableIncompatibleAndCrossConfigDefinitions() {
        SpawnerVesselConfigView first = config("Dragon", "Shared_Stored", "Dragon_Dead");
        SpawnerVesselConfigView second = config("Wyvern", "Shared_Stored", "Wyvern_Dead");
        Map<String, BondedSpawnerConfigValidator.ItemDefinition> definitions = definitions(first, 1, "Dragon");
        definitions.put("Dragon_Dead", new BondedSpawnerConfigValidator.ItemDefinition(2, "Other"));

        BondedSpawnerConfigValidator.ValidationResult result = BondedSpawnerConfigValidator.validate(
                java.util.List.of(first, second), item -> Optional.ofNullable(definitions.get(item)));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("vessel-item-cross-config-collision")));
        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("vessel-item-missing")));
        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("vessel-item-must-be-non-stackable")));
        assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("vessel-item-incompatible-spawner-config")));
    }

    private static SpawnerVesselConfigView config(String id, String stored, String dead) {
        return new SpawnerVesselConfigView(
                id, 2L, BondedVesselMode.BONDED, id + "_Empty", stored, stored, dead,
                stored, stored, 10_000L, 12.0D, null, null, true, false);
    }

    private static Map<String, BondedSpawnerConfigValidator.ItemDefinition> definitions(
            SpawnerVesselConfigView config,
            int maxStackSize,
            String boundConfigId) {
        Map<String, BondedSpawnerConfigValidator.ItemDefinition> result = new HashMap<>();
        for (String itemId : java.util.List.of(config.emptyItemId(), config.storedItemId(),
                config.activeItemId(), config.deadItemId(), config.lostItemId(),
                config.unavailableItemId())) {
            result.put(itemId, new BondedSpawnerConfigValidator.ItemDefinition(maxStackSize, boundConfigId));
        }
        return result;
    }
}
