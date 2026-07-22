package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import javax.annotation.Nonnull;

/** Compiles and validates one complete spawner generation before publishing it atomically. */
public final class SpawnerItemConfigReloadService {
    private final ItemFeatureRegistry registry;
    private final ItemAssetLookup items;

    public SpawnerItemConfigReloadService(@Nonnull ItemFeatureRegistry registry,
                                          @Nonnull ItemAssetLookup items) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.items = Objects.requireNonNull(items, "items");
    }

    /**
     * Failed compilation leaves both the active maps and their revision untouched.
     */
    @Nonnull
    public synchronized ReloadResult reload(@Nonnull Collection<TwSpawnerConfig> sourceConfigs) {
        Objects.requireNonNull(sourceConfigs, "sourceConfigs");
        long activeRevision = registry.revision();
        final long candidateRevision;
        try {
            candidateRevision = Math.addExact(activeRevision, 1L);
        } catch (ArithmeticException overflow) {
            return ReloadResult.rejected(activeRevision, List.of("spawner-config-revision-overflow"));
        }

        List<TwSpawnerConfig> ordered = sourceConfigs.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(SpawnerItemConfigReloadService::stableConfigId,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SpawnerItemConfigReloadService::stableConfigId))
                .toList();
        List<String> errors = new ArrayList<>();
        List<ItemFeatureRegistry.CompiledSpawnerConfig> compiled = new ArrayList<>();
        List<SpawnerVesselConfigView> vessels = new ArrayList<>();
        Map<String, String> configByEmptyItem = new LinkedHashMap<>();
        Map<String, String> seenConfigIds = new LinkedHashMap<>();

        for (TwSpawnerConfig source : ordered) {
            String configId = normalize(source.getId());
            String emptyItemId = normalize(source.getEmptyItemId());
            if (configId == null) {
                errors.add("spawner-config-id-missing");
                continue;
            }
            if (seenConfigIds.putIfAbsent(configId, configId) != null) {
                errors.add("spawner-config-id-duplicate:" + configId);
                continue;
            }
            if (emptyItemId == null) {
                errors.add("spawner-empty-item-missing:" + configId);
                continue;
            }
            String previous = configByEmptyItem.putIfAbsent(emptyItemId, configId);
            if (previous != null) {
                errors.add("spawner-empty-item-collision:" + emptyItemId + ":" + previous
                        + ":" + configId);
                continue;
            }
            try {
                ItemFeatureRegistry.CompiledSpawnerConfig entry =
                        new ItemFeatureRegistry.CompiledSpawnerConfig(
                                configId,
                                emptyItemId,
                                source.toItemFeatureConfig(),
                                source.toCaptureMechanicsView(candidateRevision),
                                source.toVesselConfigView(candidateRevision));
                compiled.add(entry);
                vessels.add(entry.vessel());
            } catch (RuntimeException | LinkageError failure) {
                errors.add("spawner-config-invalid:" + configId + ":" + safeReason(failure));
            }
        }

        if (errors.isEmpty()) {
            BondedSpawnerConfigValidator.ValidationResult vesselValidation =
                    BondedSpawnerConfigValidator.validate(vessels, itemId -> {
                        OptionalInt maxStack = items.maxStackSize(itemId);
                        return maxStack.isEmpty() ? java.util.Optional.empty()
                                : java.util.Optional.of(new BondedSpawnerConfigValidator.ItemDefinition(
                                maxStack.getAsInt(), configByEmptyItem.get(itemId)));
                    });
            errors.addAll(vesselValidation.errors());
        }
        if (!errors.isEmpty()) return ReloadResult.rejected(activeRevision, errors);

        try {
            if (!registry.replaceSpawnerConfigs(activeRevision, compiled)) {
                return ReloadResult.rejected(registry.revision(),
                        List.of("spawner-config-reload-raced-newer-generation"));
            }
        } catch (RuntimeException | LinkageError failure) {
            return ReloadResult.rejected(registry.revision(),
                    List.of("spawner-config-install-failed:" + safeReason(failure)));
        }
        return new ReloadResult(true, compiled.size(), registry.revision(), List.of());
    }

    private static String stableConfigId(TwSpawnerConfig config) {
        String id = normalize(config.getId());
        if (id != null) return id;
        String empty = normalize(config.getEmptyItemId());
        return empty == null ? "" : "~" + empty;
    }

    private static String safeReason(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.replace(':', '-').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @FunctionalInterface
    public interface ItemAssetLookup {
        @Nonnull
        OptionalInt maxStackSize(@Nonnull String itemId);
    }

    public record ReloadResult(boolean applied,
                               int loadedCount,
                               long activeRevision,
                               @Nonnull List<String> errors) {
        public ReloadResult {
            if (loadedCount < 0 || activeRevision < 0L) {
                throw new IllegalArgumentException("Reload counts and revisions cannot be negative.");
            }
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
            if (applied == !errors.isEmpty()) {
                throw new IllegalArgumentException("Applied reloads cannot contain errors.");
            }
        }

        /**
         * Returns whether this rejected generation should be retried after Item assets load.
         * Missing vessel items can be transient while asset packs are still loading; all other
         * validation failures remain terminal until the spawner config itself changes.
         */
        public boolean retryableAfterItemAssetsLoad() {
            return !applied && !errors.isEmpty()
                    && errors.stream().allMatch(error -> error.startsWith("vessel-item-missing:"));
        }

        private static ReloadResult rejected(long activeRevision, List<String> errors) {
            return new ReloadResult(false, 0, activeRevision, errors);
        }
    }
}
