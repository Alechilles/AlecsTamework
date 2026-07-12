package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Resolves exact managed-coop authority from Tamework config without invoking vanilla admission. */
public final class ManagedCoopAuthorityResolver {
    private final ConfigLookup configLookup;
    private final VanillaCoopLookup vanillaCoopLookup;

    public ManagedCoopAuthorityResolver() {
        this(new AssetConfigLookup(), new AssetVanillaCoopLookup());
    }

    ManagedCoopAuthorityResolver(
            @Nonnull ConfigLookup configLookup,
            @Nonnull VanillaCoopLookup vanillaCoopLookup
    ) {
        this.configLookup = Objects.requireNonNull(configLookup, "configLookup");
        this.vanillaCoopLookup = Objects.requireNonNull(
                vanillaCoopLookup, "vanillaCoopLookup");
    }

    /**
     * Returns a Tamework-owned context only when an authority-eligible config resolves for the
     * supplied coop evidence and exposes a non-blank canonical coop ID.
     */
    @Nullable
    public ManagedCoopContext resolve(@Nullable String worldName,
                                      @Nullable String rawBlockTypeId,
                                      @Nullable String rawCoopAssetId,
                                      @Nullable Vector3i block,
                                      int blockRotationIndex,
                                      @Nullable ItemContainer container) {
        String normalizedWorld = normalizeIdentifier(worldName);
        if (normalizedWorld == null || block == null) {
            return null;
        }
        TwCoopConfig config = resolveConfig(rawCoopAssetId, rawBlockTypeId);
        if (config == null || !config.isManagedAuthorityEnabled()) {
            return null;
        }
        String coopId = normalizeIdentifier(config.getCoopId());
        if (coopId == null || !Boolean.FALSE.equals(
                vanillaCoopLookup.capturesWildNpcsAutomatically(coopId))) {
            return null;
        }
        return new ManagedCoopContext(
                new ManagedCoopAuthorityKey(normalizedWorld, block.x, block.y, block.z),
                coopId,
                blockRotationIndex,
                config,
                container
        );
    }

    @Nullable
    private TwCoopConfig resolveConfig(@Nullable String rawCoopAssetId,
                                       @Nullable String rawBlockTypeId) {
        TwCoopConfig byAsset = resolveIdentifier(normalizeIdentifier(rawCoopAssetId));
        if (byAsset != null) {
            return byAsset;
        }
        return resolveIdentifier(normalizeBlockTypeId(rawBlockTypeId));
    }

    @Nullable
    private TwCoopConfig resolveIdentifier(@Nullable String normalized) {
        if (normalized == null) {
            return null;
        }
        TwCoopConfig config = firstEligible(
                configLookup.forBlockType(normalized),
                configLookup.forCoop(normalized)
        );
        if (config != null) {
            return config;
        }
        String trailing = trailingIdentifier(normalized);
        if (trailing == null || trailing.equals(normalized)) {
            return null;
        }
        return firstEligible(configLookup.forBlockType(trailing), configLookup.forCoop(trailing));
    }

    @Nullable
    private TwCoopConfig firstEligible(@Nullable TwCoopConfig first, @Nullable TwCoopConfig second) {
        if (first != null && first.isManagedAuthorityEnabled()) {
            return first;
        }
        return second != null && second.isManagedAuthorityEnabled() ? second : null;
    }

    @Nullable
    static String normalizeBlockTypeId(@Nullable String value) {
        String normalized = normalizeIdentifier(value);
        if (normalized == null) {
            return null;
        }
        while (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        int bracketIndex = normalized.indexOf('[');
        if (bracketIndex > 0) {
            normalized = normalized.substring(0, bracketIndex);
        }
        normalized = stripStateVariantSuffix(normalized);
        return normalized.isBlank() ? null : normalized;
    }

    @Nonnull
    private static String stripStateVariantSuffix(@Nonnull String value) {
        int marker = value.indexOf("_state_definitions_");
        if (marker <= 0) {
            marker = value.indexOf(".state_definitions.");
        }
        if (marker <= 0) {
            marker = value.indexOf("#state_definitions.");
        }
        return marker > 0 ? value.substring(0, marker) : value;
    }

    @Nullable
    private static String trailingIdentifier(@Nonnull String value) {
        int separator = Math.max(value.lastIndexOf('/'), Math.max(value.lastIndexOf(':'), value.lastIndexOf('.')));
        return separator < 0 || separator + 1 >= value.length() ? null : value.substring(separator + 1);
    }

    @Nullable
    private static String normalizeIdentifier(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    interface ConfigLookup {
        @Nullable
        TwCoopConfig forBlockType(@Nonnull String blockTypeId);

        @Nullable
        TwCoopConfig forCoop(@Nonnull String coopId);
    }

    /** Reports the vanilla automatic-intake flag, or null when the base asset is unavailable. */
    interface VanillaCoopLookup {
        @Nullable
        Boolean capturesWildNpcsAutomatically(@Nonnull String coopId);
    }

    private static final class AssetConfigLookup implements ConfigLookup {
        @Nullable
        @Override
        public TwCoopConfig forBlockType(@Nonnull String blockTypeId) {
            return TwCoopConfig.resolveForBlockType(blockTypeId);
        }

        @Nullable
        @Override
        public TwCoopConfig forCoop(@Nonnull String coopId) {
            return TwCoopConfig.resolveForCoop(coopId);
        }
    }

    /** Fails closed unless the targeted base coop explicitly disables its own wild intake. */
    private static final class AssetVanillaCoopLookup implements VanillaCoopLookup {
        @Nullable
        @Override
        public Boolean capturesWildNpcsAutomatically(@Nonnull String coopId) {
            try {
                if (FarmingCoopAsset.getAssetMap() == null
                        || FarmingCoopAsset.getAssetMap().getAssetMap() == null) {
                    return null;
                }
                for (FarmingCoopAsset asset
                        : FarmingCoopAsset.getAssetMap().getAssetMap().values()) {
                    if (asset != null && asset.getId() != null
                            && coopId.equalsIgnoreCase(asset.getId())) {
                        return asset.getCaptureWildNPCsInRange();
                    }
                }
            } catch (RuntimeException exception) {
                return null;
            }
            return null;
        }
    }
}
