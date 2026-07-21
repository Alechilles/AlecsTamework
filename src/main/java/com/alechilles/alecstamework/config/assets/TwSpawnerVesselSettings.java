package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bonded-vessel section of a {@link TwSpawnerConfig}. */
public final class TwSpawnerVesselSettings {
    public static final BuilderCodec<TwSpawnerVesselSettings> CODEC = BuilderCodec.builder(
            TwSpawnerVesselSettings.class, TwSpawnerVesselSettings::new)
        .<String>append(
            new KeyedCodec<>("Mode", Codec.STRING),
            (settings, value) -> settings.mode = parseMode(value),
            settings -> settings.mode == BondedVesselMode.BONDED ? "Bonded" : "Disposable")
        .documentation("Disposable preserves legacy spawner behavior; Bonded enables durable one-item/one-profile binding.")
        .add()
        .<Map<String, String>>append(
            new KeyedCodec<>("StateItemIds", MapCodec.STRING_HASH_MAP_CODEC),
            (settings, value) -> settings.stateItemIds = immutableStateItemIds(value),
            settings -> settings.stateItemIds)
        .documentation("Optional Stored, Active, Dead, Lost, and Unavailable item IDs. An explicit map replaces the parent map.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("TransitionCooldownMs", Codec.INTEGER),
            (settings, value) -> settings.transitionCooldownMs = value,
            settings -> settings.transitionCooldownMs)
        .documentation("Non-negative durable cooldown started after a successful summon or store.")
        .add()
        .<Double>append(
            new KeyedCodec<>("StoreMaxDistance", Codec.DOUBLE),
            (settings, value) -> settings.storeMaxDistance = value,
            settings -> settings.storeMaxDistance)
        .documentation("Maximum store distance. Zero delegates to the existing spawn/capture distance policy.")
        .add()
        .<String>append(
            new KeyedCodec<>("StoreParticleSystem", Codec.STRING),
            (settings, value) -> settings.storeParticleSystem = normalizeBlank(value),
            settings -> settings.storeParticleSystem)
        .documentation("Optional particle system played after a committed store transition.")
        .add()
        .<String>append(
            new KeyedCodec<>("StoreSoundEvent", Codec.STRING),
            (settings, value) -> settings.storeSoundEvent = normalizeBlank(value),
            settings -> settings.storeSoundEvent)
        .documentation("Optional sound event played after a committed store transition.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireOwner = value,
            settings -> settings.requireOwner)
        .documentation("Whether only the canonical profile owner may use or store this vessel.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("AllowStoreInCombat", Codec.BOOLEAN),
            (settings, value) -> settings.allowStoreInCombat = value,
            settings -> settings.allowStoreInCombat)
        .documentation("Whether an active companion may be stored while authoritative combat state is present.")
        .add()
        .build();

    private BondedVesselMode mode = BondedVesselMode.DISPOSABLE;
    private Map<String, String> stateItemIds = Collections.emptyMap();
    private int transitionCooldownMs;
    private double storeMaxDistance;
    private String storeParticleSystem;
    private String storeSoundEvent;
    private boolean requireOwner = true;
    private boolean allowStoreInCombat;

    public void inheritMissingFrom(@Nonnull TwSpawnerVesselSettings parent,
                                   @Nullable Set<String> explicitNestedKeys) {
        Objects.requireNonNull(parent, "parent");
        if (explicitNestedKeys == null) return;
        if (!explicitNestedKeys.contains("Mode")) mode = parent.mode;
        if (!explicitNestedKeys.contains("StateItemIds")) stateItemIds = parent.stateItemIds;
        if (!explicitNestedKeys.contains("TransitionCooldownMs")) {
            transitionCooldownMs = parent.transitionCooldownMs;
        }
        if (!explicitNestedKeys.contains("StoreMaxDistance")) storeMaxDistance = parent.storeMaxDistance;
        if (!explicitNestedKeys.contains("StoreParticleSystem")) {
            storeParticleSystem = parent.storeParticleSystem;
        }
        if (!explicitNestedKeys.contains("StoreSoundEvent")) storeSoundEvent = parent.storeSoundEvent;
        if (!explicitNestedKeys.contains("RequireOwner")) requireOwner = parent.requireOwner;
        if (!explicitNestedKeys.contains("AllowStoreInCombat")) {
            allowStoreInCombat = parent.allowStoreInCombat;
        }
    }

    public void validate(@Nullable String configId,
                         @Nullable String emptyItemId,
                         @Nullable String filledItemId) {
        if (transitionCooldownMs < 0) {
            throw new IllegalArgumentException("Vessel.TransitionCooldownMs cannot be negative.");
        }
        if (!Double.isFinite(storeMaxDistance) || storeMaxDistance < 0.0D) {
            throw new IllegalArgumentException("Vessel.StoreMaxDistance must be finite and non-negative.");
        }
        for (Map.Entry<String, String> entry : stateItemIds.entrySet()) {
            canonicalStateKey(entry.getKey());
            if (normalizeBlank(entry.getValue()) == null) {
                throw new IllegalArgumentException("Vessel.StateItemIds values cannot be blank.");
            }
        }
        if (mode == BondedVesselMode.BONDED) {
            requireText(configId, "Spawner config ID");
            requireText(emptyItemId, "EmptyItemId");
            requireText(filledItemId, "FilledItemId");
        }
    }

    @Nonnull
    public SpawnerVesselConfigView toView(@Nonnull String configId,
                                          long configRevision,
                                          @Nullable String emptyItemId,
                                          @Nullable String filledItemId) {
        validate(configId, emptyItemId, filledItemId);
        return new SpawnerVesselConfigView(
                configId, configRevision, mode, emptyItemId,
                resolveStateItemId(BondedVesselState.STORED, filledItemId, emptyItemId),
                resolveStateItemId(BondedVesselState.ACTIVE, filledItemId, emptyItemId),
                resolveStateItemId(BondedVesselState.DEAD, filledItemId, emptyItemId),
                resolveStateItemId(BondedVesselState.LOST, filledItemId, emptyItemId),
                stateItemId("Unavailable", filledItemId),
                transitionCooldownMs, storeMaxDistance, storeParticleSystem, storeSoundEvent,
                requireOwner, allowStoreInCombat);
    }

    @Nonnull
    public ItemFeatureConfig.VesselItemMechanics toRuntimeMechanics(
            @Nullable String emptyItemId,
            @Nullable String filledItemId) {
        validate(mode == BondedVesselMode.BONDED ? "runtime" : null, emptyItemId, filledItemId);
        return new ItemFeatureConfig.VesselItemMechanics(
                mode, emptyItemId,
                resolveStateItemId(BondedVesselState.STORED, filledItemId, emptyItemId),
                resolveStateItemId(BondedVesselState.ACTIVE, filledItemId, emptyItemId),
                resolveStateItemId(BondedVesselState.DEAD, filledItemId, emptyItemId),
                resolveStateItemId(BondedVesselState.LOST, filledItemId, emptyItemId),
                stateItemId("Unavailable", filledItemId),
                transitionCooldownMs, storeMaxDistance, storeParticleSystem, storeSoundEvent,
                requireOwner, allowStoreInCombat);
    }

    @Nullable
    public String resolveStateItemId(@Nonnull BondedVesselState state,
                                     @Nullable String filledItemId,
                                     @Nullable String emptyItemId) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case STORED, STORING -> stateItemId("Stored", filledItemId);
            case ACTIVE, SUMMONING -> stateItemId("Active", filledItemId);
            case DEAD -> stateItemId("Dead", filledItemId);
            case LOST -> stateItemId("Lost", filledItemId);
            case RELEASING, RELEASED -> normalizeBlank(emptyItemId);
        };
    }

    public BondedVesselMode getMode() {
        return mode;
    }

    public Map<String, String> getStateItemIds() {
        return stateItemIds;
    }

    public int getTransitionCooldownMs() {
        return transitionCooldownMs;
    }

    public double getStoreMaxDistance() {
        return storeMaxDistance;
    }

    public String getStoreParticleSystem() {
        return storeParticleSystem;
    }

    public String getStoreSoundEvent() {
        return storeSoundEvent;
    }

    public boolean isRequireOwner() {
        return requireOwner;
    }

    public boolean isAllowStoreInCombat() {
        return allowStoreInCombat;
    }

    @Nullable
    private String stateItemId(@Nonnull String key, @Nullable String fallback) {
        String explicit = stateItemIds.get(key);
        return explicit == null ? normalizeBlank(fallback) : explicit;
    }

    @Nonnull
    private static Map<String, String> immutableStateItemIds(@Nullable Map<String, String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = canonicalStateKey(entry.getKey());
            String value = requireText(entry.getValue(), "Vessel.StateItemIds." + key);
            if (normalized.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate vessel state item key: " + key);
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    @Nonnull
    private static String canonicalStateKey(@Nullable String raw) {
        String normalized = requireText(raw, "Vessel.StateItemIds key").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "stored" -> "Stored";
            case "active" -> "Active";
            case "dead" -> "Dead";
            case "lost" -> "Lost";
            case "unavailable" -> "Unavailable";
            default -> throw new IllegalArgumentException("Unsupported vessel state item key: " + raw);
        };
    }

    @Nonnull
    private static BondedVesselMode parseMode(@Nullable String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("Disposable")) {
            return BondedVesselMode.DISPOSABLE;
        }
        if (value.equalsIgnoreCase("Bonded")) return BondedVesselMode.BONDED;
        throw new IllegalArgumentException("Unsupported Vessel.Mode: " + value);
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static String requireText(@Nullable String value, @Nonnull String field) {
        String normalized = normalizeBlank(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
