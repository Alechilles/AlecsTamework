package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Asset-backed local defaults for persistence feature circuit breakers. */
public final class TwPersistenceConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwPersistenceConfig>>,
        TwParentFallbackAsset<TwPersistenceConfig> {
    private static final BuilderCodec<CircuitSection> CIRCUIT_CODEC = BuilderCodec.builder(
            CircuitSection.class, CircuitSection::new)
            .<Boolean>append(new KeyedCodec<>("AllPersistenceMutations", Codec.BOOLEAN),
                    (value, enabled) -> value.allPersistence = enabled,
                    value -> value.allPersistence).add()
            .<Boolean>append(new KeyedCodec<>("TamingOwnership", Codec.BOOLEAN),
                    (value, enabled) -> value.tamingOwnership = enabled,
                    value -> value.tamingOwnership).add()
            .<Boolean>append(new KeyedCodec<>("AdminTamedSpawn", Codec.BOOLEAN),
                    (value, enabled) -> value.adminTamedSpawn = enabled,
                    value -> value.adminTamedSpawn).add()
            .<Boolean>append(new KeyedCodec<>("CaptureIntake", Codec.BOOLEAN),
                    (value, enabled) -> value.captureIntake = enabled,
                    value -> value.captureIntake).add()
            .<Boolean>append(new KeyedCodec<>("CaptureRelease", Codec.BOOLEAN),
                    (value, enabled) -> value.captureRelease = enabled,
                    value -> value.captureRelease).add()
            .<Boolean>append(new KeyedCodec<>("ManagedCoopIntake", Codec.BOOLEAN),
                    (value, enabled) -> value.managedCoopIntake = enabled,
                    value -> value.managedCoopIntake).add()
            .<Boolean>append(new KeyedCodec<>("ManagedCoopRelease", Codec.BOOLEAN),
                    (value, enabled) -> value.managedCoopRelease = enabled,
                    value -> value.managedCoopRelease).add()
            .<Boolean>append(new KeyedCodec<>("ManagedCoopAutomation", Codec.BOOLEAN),
                    (value, enabled) -> value.managedCoopAutomation = enabled,
                    value -> value.managedCoopAutomation).add()
            .<Boolean>append(new KeyedCodec<>("BreedingPairing", Codec.BOOLEAN),
                    (value, enabled) -> value.breedingPairing = enabled,
                    value -> value.breedingPairing).add()
            .<Boolean>append(new KeyedCodec<>("BreedingBirth", Codec.BOOLEAN),
                    (value, enabled) -> value.breedingBirth = enabled,
                    value -> value.breedingBirth).add()
            .<Boolean>append(new KeyedCodec<>("RecallRelocation", Codec.BOOLEAN),
                    (value, enabled) -> value.recallRelocation = enabled,
                    value -> value.recallRelocation).add()
            .<Boolean>append(new KeyedCodec<>("DeathLostRecovery", Codec.BOOLEAN),
                    (value, enabled) -> value.deathLostRecovery = enabled,
                    value -> value.deathLostRecovery).add()
            .<Boolean>append(new KeyedCodec<>("AutomaticScopedRecovery", Codec.BOOLEAN),
                    (value, enabled) -> value.automaticScopedRecovery = enabled,
                    value -> value.automaticScopedRecovery).add()
            .build();

    public static final AssetBuilderCodec<String, TwPersistenceConfig> CODEC =
            AssetBuilderCodec.builder(
                    TwPersistenceConfig.class, TwPersistenceConfig::new, Codec.STRING,
                    (asset, id) -> asset.id = id, asset -> asset.id,
                    (asset, data) -> asset.data = data, asset -> asset.data)
                    .documentation("Local persistence safety defaults for Alec's Tamework.")
                    .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                            (asset, enabled) -> asset.enabled = enabled,
                            asset -> asset.enabled).add()
                    .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                            (asset, priority) -> asset.priority = priority,
                            asset -> asset.priority).add()
                    .<CircuitSection>append(new KeyedCodec<>("FeatureCircuits", CIRCUIT_CODEC),
                            (asset, circuits) -> asset.circuits = circuits == null
                                    ? new CircuitSection() : circuits,
                            asset -> asset.circuits)
                    .documentation("Defaults for new feature work; durable administrator overrides take precedence.")
                    .add()
                    .build();

    private static AssetStore<String, TwPersistenceConfig,
            DefaultAssetMap<String, TwPersistenceConfig>> assetStore;
    private static final Object inheritanceLock = new Object();
    private static volatile boolean inheritanceDirty = true;
    private static volatile boolean cacheDirty = true;
    private static volatile TwPersistenceConfig activeConfig;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private CircuitSection circuits = new CircuitSection();

    protected TwPersistenceConfig() {
    }

    @SuppressWarnings("unchecked")
    public static AssetStore<String, TwPersistenceConfig,
            DefaultAssetMap<String, TwPersistenceConfig>> getAssetStore() {
        if (assetStore == null) assetStore = AssetRegistry.getAssetStore(TwPersistenceConfig.class);
        return assetStore;
    }

    @Nullable
    public static DefaultAssetMap<String, TwPersistenceConfig> getAssetMap() {
        AssetStore<String, TwPersistenceConfig, DefaultAssetMap<String, TwPersistenceConfig>> store =
                getAssetStore();
        DefaultAssetMap<String, TwPersistenceConfig> assets = store == null
                ? null : (DefaultAssetMap<String, TwPersistenceConfig>) store.getAssetMap();
        ensureInheritance(assets);
        return assets;
    }

    public static void clearCache() {
        inheritanceDirty = true;
        cacheDirty = true;
    }

    @Nonnull
    public static TwPersistenceConfig resolveActive() {
        TwPersistenceConfig cached = activeConfig;
        if (!cacheDirty && cached != null) return cached;
        synchronized (TwPersistenceConfig.class) {
            if (!cacheDirty && activeConfig != null) return activeConfig;
            activeConfig = selectBest(getAssetMap());
            cacheDirty = false;
            return activeConfig == null ? new TwPersistenceConfig() : activeConfig;
        }
    }

    @Nullable
    private static TwPersistenceConfig selectBest(
            @Nullable DefaultAssetMap<String, TwPersistenceConfig> assets) {
        if (assets == null || assets.getAssetMap() == null) return null;
        TwPersistenceConfig best = null;
        for (TwPersistenceConfig candidate : assets.getAssetMap().values()) {
            if (candidate == null || !candidate.enabled) continue;
            if (best == null || candidate.priority > best.priority
                    || candidate.priority == best.priority && compareIds(candidate.id, best.id) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static int compareIds(String left, String right) {
        return (left == null ? "" : left).compareToIgnoreCase(right == null ? "" : right);
    }

    private static void ensureInheritance(
            @Nullable DefaultAssetMap<String, TwPersistenceConfig> assets) {
        if (!inheritanceDirty || assets == null || assets.getAssetMap() == null) return;
        synchronized (inheritanceLock) {
            if (!inheritanceDirty) return;
            TwAssetInheritanceFallback.repairAll(assets);
            inheritanceDirty = false;
        }
    }

    @Nonnull
    public Map<PersistenceDomain, Boolean> featureCircuitDefaults() {
        CircuitSection value = circuits == null ? new CircuitSection() : circuits;
        return Map.ofEntries(
                Map.entry(PersistenceDomain.ALL_PERSISTENCE, value.allPersistence),
                Map.entry(PersistenceDomain.TAMING_OWNERSHIP, value.tamingOwnership),
                Map.entry(PersistenceDomain.ADMIN_TAMED_SPAWN, value.adminTamedSpawn),
                Map.entry(PersistenceDomain.CAPTURE_INTAKE, value.captureIntake),
                Map.entry(PersistenceDomain.CAPTURE_RELEASE, value.captureRelease),
                Map.entry(PersistenceDomain.MANAGED_COOP_INTAKE, value.managedCoopIntake),
                Map.entry(PersistenceDomain.MANAGED_COOP_RELEASE, value.managedCoopRelease),
                Map.entry(PersistenceDomain.MANAGED_COOP_AUTOMATION, value.managedCoopAutomation),
                Map.entry(PersistenceDomain.BREEDING_PAIRING, value.breedingPairing),
                Map.entry(PersistenceDomain.BREEDING_BIRTH, value.breedingBirth),
                Map.entry(PersistenceDomain.RECALL_RELOCATION, value.recallRelocation),
                Map.entry(PersistenceDomain.DEATH_LOST_RECOVERY, value.deathLostRecovery),
                Map.entry(PersistenceDomain.AUTOMATIC_SCOPED_RECOVERY, value.automaticScopedRecovery));
    }

    @Nullable
    public String getId() {
        return id;
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) return null;
        String parent = data.getParentKey().toString();
        return parent == null || parent.isBlank() ? null : parent;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwPersistenceConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(
            @Nonnull TwPersistenceConfig parent,
            @Nonnull Set<String> explicitTopLevelKeys,
            @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("FeatureCircuits")) {
            circuits = parent.circuits.copy();
            return;
        }
        Set<String> nested = explicitNestedKeysByTopLevel == null
                ? null : explicitNestedKeysByTopLevel.get("FeatureCircuits");
        if (nested != null) circuits.inherit(parent.circuits, nested);
    }

    /** Boolean defaults intentionally start enabled for the single public cutover. */
    private static final class CircuitSection {
        private boolean allPersistence = true;
        private boolean tamingOwnership = true;
        private boolean adminTamedSpawn = true;
        private boolean captureIntake = true;
        private boolean captureRelease = true;
        private boolean managedCoopIntake = true;
        private boolean managedCoopRelease = true;
        private boolean managedCoopAutomation = true;
        private boolean breedingPairing = true;
        private boolean breedingBirth = true;
        private boolean recallRelocation = true;
        private boolean deathLostRecovery = true;
        private boolean automaticScopedRecovery = true;

        private CircuitSection copy() {
            CircuitSection copy = new CircuitSection();
            copy.inherit(this, Set.of());
            return copy;
        }

        private void inherit(CircuitSection parent, Set<String> explicit) {
            if (!explicit.contains("AllPersistenceMutations")) allPersistence = parent.allPersistence;
            if (!explicit.contains("TamingOwnership")) tamingOwnership = parent.tamingOwnership;
            if (!explicit.contains("AdminTamedSpawn")) adminTamedSpawn = parent.adminTamedSpawn;
            if (!explicit.contains("CaptureIntake")) captureIntake = parent.captureIntake;
            if (!explicit.contains("CaptureRelease")) captureRelease = parent.captureRelease;
            if (!explicit.contains("ManagedCoopIntake")) managedCoopIntake = parent.managedCoopIntake;
            if (!explicit.contains("ManagedCoopRelease")) managedCoopRelease = parent.managedCoopRelease;
            if (!explicit.contains("ManagedCoopAutomation")) managedCoopAutomation = parent.managedCoopAutomation;
            if (!explicit.contains("BreedingPairing")) breedingPairing = parent.breedingPairing;
            if (!explicit.contains("BreedingBirth")) breedingBirth = parent.breedingBirth;
            if (!explicit.contains("RecallRelocation")) recallRelocation = parent.recallRelocation;
            if (!explicit.contains("DeathLostRecovery")) deathLostRecovery = parent.deathLostRecovery;
            if (!explicit.contains("AutomaticScopedRecovery")) {
                automaticScopedRecovery = parent.automaticScopedRecovery;
            }
        }
    }
}
