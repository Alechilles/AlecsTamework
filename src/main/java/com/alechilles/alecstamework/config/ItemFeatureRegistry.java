package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Atomic registry for item feature configs and their compiled capture/vessel projections.
 */
public final class ItemFeatureRegistry {
    private volatile State state = State.empty();

    /** Legacy/test registration path. Production reloads use {@link #replaceSpawnerConfigs}. */
    public synchronized void register(String itemId, ItemFeatureConfig config) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        State current = state;
        Map<String, ItemFeatureConfig> updated = new HashMap<>(current.configsByItemId());
        if (updated.putIfAbsent(itemId, config) != null) {
            throw new IllegalArgumentException("Duplicate item feature binding for item ID: " + itemId);
        }
        state = current.withItemConfigs(updated);
    }

    public ItemFeatureConfig get(String itemId) {
        if (itemId == null) return null;
        Map<String, ItemFeatureConfig> configs = state.configsByItemId();
        ItemFeatureConfig config = configs.get(itemId);
        if (config != null) return config;
        // Preserve legacy disposable state-item normalization. Bonded dispatch uses the exact
        // compiled vessel index below and never depends on this naming convention.
        String normalized = normalizeStateItemId(itemId);
        return normalized != null && !normalized.equals(itemId) ? configs.get(normalized) : null;
    }

    @Nonnull
    public Optional<SpawnerVesselConfigView> getVesselByConfigId(@Nonnull String configId) {
        Objects.requireNonNull(configId, "configId");
        return Optional.ofNullable(state.vesselsByConfigId().get(configId));
    }

    @Nonnull
    public Optional<SpawnerVesselConfigView> resolveVesselForItemId(@Nonnull String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        return itemId.isBlank()
                ? Optional.empty() : Optional.ofNullable(state.vesselsByItemId().get(itemId));
    }

    @Nonnull
    public Optional<SpawnerCaptureMechanicsView> getCaptureByConfigId(@Nonnull String configId) {
        Objects.requireNonNull(configId, "configId");
        return Optional.ofNullable(state.captureByConfigId().get(configId));
    }

    @Nonnull
    public Optional<SpawnerCaptureMechanicsView> resolveCaptureForItemId(@Nonnull String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        return itemId.isBlank()
                ? Optional.empty() : Optional.ofNullable(state.captureByItemId().get(itemId));
    }

    /**
     * Installs one fully compiled generation. A stale compiler cannot overwrite a newer reload.
     */
    public synchronized boolean replaceSpawnerConfigs(long expectedRevision,
                                                       @Nonnull Collection<CompiledSpawnerConfig> configs) {
        Objects.requireNonNull(configs, "configs");
        State current = state;
        if (current.revision() != expectedRevision) return false;
        long nextRevision = Math.addExact(expectedRevision, 1L);
        Map<String, ItemFeatureConfig> byEmptyItem = new LinkedHashMap<>();
        Map<String, SpawnerCaptureMechanicsView> captureByConfig = new LinkedHashMap<>();
        Map<String, SpawnerCaptureMechanicsView> captureByItem = new LinkedHashMap<>();
        Map<String, SpawnerVesselConfigView> vesselByConfig = new LinkedHashMap<>();
        Map<String, SpawnerVesselConfigView> vesselByItem = new LinkedHashMap<>();
        for (CompiledSpawnerConfig compiled : configs) {
            if (compiled.capture().configRevision() != nextRevision
                    || compiled.vessel().configRevision() != nextRevision) {
                throw new IllegalArgumentException("Compiled spawner revision does not match install generation.");
            }
            putUnique(byEmptyItem, compiled.emptyItemId(), compiled.itemFeature(),
                    "Duplicate item feature binding for item ID: ");
            putUnique(captureByConfig, compiled.configId(), compiled.capture(),
                    "Duplicate spawner config ID: ");
            captureByItem.putIfAbsent(compiled.emptyItemId(), compiled.capture());
            String filledItemId = compiled.itemFeature().getSpawnerFilledItemId();
            if (filledItemId != null && !filledItemId.isBlank()) {
                captureByItem.putIfAbsent(filledItemId, compiled.capture());
            }
            putUnique(vesselByConfig, compiled.configId(), compiled.vessel(),
                    "Duplicate spawner config ID: ");
            if (compiled.vessel().mode() == BondedVesselMode.BONDED) {
                indexVesselItem(vesselByItem, compiled.vessel().emptyItemId(), compiled.vessel());
                indexVesselItem(vesselByItem, compiled.vessel().storedItemId(), compiled.vessel());
                indexVesselItem(vesselByItem, compiled.vessel().activeItemId(), compiled.vessel());
                indexVesselItem(vesselByItem, compiled.vessel().deadItemId(), compiled.vessel());
                indexVesselItem(vesselByItem, compiled.vessel().lostItemId(), compiled.vessel());
                indexVesselItem(vesselByItem, compiled.vessel().unavailableItemId(), compiled.vessel());
            }
        }
        state = new State(nextRevision, byEmptyItem, captureByConfig, captureByItem,
                vesselByConfig, vesselByItem);
        return true;
    }

    private static <T> void putUnique(Map<String, T> target, String id, T value, String message) {
        if (target.putIfAbsent(id, value) != null) throw new IllegalArgumentException(message + id);
    }

    private static void indexVesselItem(Map<String, SpawnerVesselConfigView> target,
                                        String itemId,
                                        SpawnerVesselConfigView config) {
        if (itemId == null || itemId.isBlank()) return;
        SpawnerVesselConfigView previous = target.putIfAbsent(itemId, config);
        if (previous != null && !previous.configId().equals(config.configId())) {
            throw new IllegalArgumentException("Duplicate bonded vessel item binding: " + itemId);
        }
    }

    public static String normalizeStateItemId(String itemId) {
        if (itemId == null) return null;
        String trimmed = itemId.startsWith("*") ? itemId.substring(1) : itemId;
        int stateIndex = trimmed.indexOf("_State_");
        return stateIndex > 0 ? trimmed.substring(0, stateIndex) : itemId;
    }

    public Map<String, ItemFeatureConfig> snapshot() {
        return state.configsByItemId();
    }

    public synchronized void clear() {
        state = State.empty(Math.addExact(state.revision(), 1L));
    }

    /** Monotonic item-config generation pinned by durable operations. */
    public long revision() {
        return state.revision();
    }

    public void registerDefaults() {
        // No code-driven defaults; all item feature configs come from JSON.
    }

    public record CompiledSpawnerConfig(@Nonnull String configId,
                                        @Nonnull String emptyItemId,
                                        @Nonnull ItemFeatureConfig itemFeature,
                                        @Nonnull SpawnerCaptureMechanicsView capture,
                                        @Nonnull SpawnerVesselConfigView vessel) {
        public CompiledSpawnerConfig {
            configId = requireText(configId, "configId");
            emptyItemId = requireText(emptyItemId, "emptyItemId");
            Objects.requireNonNull(itemFeature, "itemFeature");
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(vessel, "vessel");
        }
    }

    private record State(long revision,
                         @Nonnull Map<String, ItemFeatureConfig> configsByItemId,
                         @Nonnull Map<String, SpawnerCaptureMechanicsView> captureByConfigId,
                         @Nonnull Map<String, SpawnerCaptureMechanicsView> captureByItemId,
                         @Nonnull Map<String, SpawnerVesselConfigView> vesselsByConfigId,
                         @Nonnull Map<String, SpawnerVesselConfigView> vesselsByItemId) {
        private State {
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
            configsByItemId = immutable(configsByItemId);
            captureByConfigId = immutable(captureByConfigId);
            captureByItemId = immutable(captureByItemId);
            vesselsByConfigId = immutable(vesselsByConfigId);
            vesselsByItemId = immutable(vesselsByItemId);
        }

        private static State empty() {
            return empty(0L);
        }

        private static State empty(long revision) {
            return new State(revision, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        private State withItemConfigs(Map<String, ItemFeatureConfig> configs) {
            return new State(revision, configs, captureByConfigId, captureByItemId,
                    vesselsByConfigId, vesselsByItemId);
        }

        private static <K, V> Map<K, V> immutable(Map<K, V> values) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
