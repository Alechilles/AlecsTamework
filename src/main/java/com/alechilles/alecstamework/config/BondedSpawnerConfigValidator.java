package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure validation for atomically compiling bonded spawner item definitions. */
public final class BondedSpawnerConfigValidator {
    private BondedSpawnerConfigValidator() {
    }

    @Nonnull
    public static ValidationResult validate(@Nonnull Collection<SpawnerVesselConfigView> configs,
                                            @Nonnull ItemDefinitionLookup items) {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(items, "items");
        List<String> errors = new ArrayList<>();
        Map<String, String> ownerByItemId = new LinkedHashMap<>();
        for (SpawnerVesselConfigView config : configs) {
            if (config == null || config.mode() != BondedVesselMode.BONDED) continue;
            Set<String> uniqueIds = lifecycleItemIds(config);
            for (String itemId : uniqueIds) {
                String previous = ownerByItemId.putIfAbsent(itemId, config.configId());
                if (previous != null && !previous.equals(config.configId())) {
                    errors.add("vessel-item-cross-config-collision:" + itemId + ":" + previous
                            + ":" + config.configId());
                    continue;
                }
                Optional<ItemDefinition> definition = items.find(itemId);
                if (definition.isEmpty()) {
                    errors.add("vessel-item-missing:" + config.configId() + ":" + itemId);
                    continue;
                }
                ItemDefinition resolved = definition.get();
                if (resolved.maxStackSize() != 1) {
                    errors.add("vessel-item-must-be-non-stackable:" + config.configId() + ":" + itemId);
                }
                if (resolved.boundSpawnerConfigId() != null
                        && !resolved.boundSpawnerConfigId().equals(config.configId())) {
                    errors.add("vessel-item-incompatible-spawner-config:" + config.configId() + ":"
                            + itemId + ":" + resolved.boundSpawnerConfigId());
                }
            }
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    @Nonnull
    private static Set<String> lifecycleItemIds(SpawnerVesselConfigView config) {
        Set<String> ids = new LinkedHashSet<>();
        add(ids, config.emptyItemId());
        add(ids, config.storedItemId());
        add(ids, config.activeItemId());
        add(ids, config.deadItemId());
        add(ids, config.lostItemId());
        add(ids, config.unavailableItemId());
        return ids;
    }

    private static void add(Set<String> ids, @Nullable String itemId) {
        if (itemId != null && !itemId.isBlank()) ids.add(itemId.trim());
    }

    public record ItemDefinition(int maxStackSize, @Nullable String boundSpawnerConfigId) {
        public ItemDefinition {
            if (maxStackSize <= 0) throw new IllegalArgumentException("maxStackSize must be positive");
            boundSpawnerConfigId = boundSpawnerConfigId == null || boundSpawnerConfigId.isBlank()
                    ? null : boundSpawnerConfigId.trim();
        }
    }

    @FunctionalInterface
    public interface ItemDefinitionLookup {
        @Nonnull
        Optional<ItemDefinition> find(@Nonnull String itemId);
    }

    public record ValidationResult(boolean valid, @Nonnull List<String> errors) {
        public ValidationResult {
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
            if (valid != errors.isEmpty()) {
                throw new IllegalArgumentException("valid must match whether errors are empty");
            }
        }
    }
}
