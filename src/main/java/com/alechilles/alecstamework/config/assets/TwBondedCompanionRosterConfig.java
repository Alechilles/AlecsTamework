package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Separate bonded-companion policy stored under
 * {@code Server/Tamework/BondedCompanions/Rosters}.
 */
public final class TwBondedCompanionRosterConfig implements
        JsonAssetWithMap<
                String,
                DefaultAssetMap<String, TwBondedCompanionRosterConfig>
                >,
        TwParentFallbackAsset<TwBondedCompanionRosterConfig> {
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*:[A-Za-z0-9][A-Za-z0-9_./:-]*"
    );

    public static final AssetBuilderCodec<
            String,
            TwBondedCompanionRosterConfig
            > CODEC = TwBondedCompanionRosterCodecs.CODEC;

    private static AssetStore<
            String,
            TwBondedCompanionRosterConfig,
            DefaultAssetMap<String, TwBondedCompanionRosterConfig>
            > assetStore;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean inheritanceCacheDirty = true;

    AssetExtraInfo.Data data;
    String id;
    int priority;
    String rosterId;
    String familyId;
    String[] allowedRoles = ArrayUtil.EMPTY_STRING_ARRAY;
    int maximumOwned;
    int maximumActive;
    long sessionDurationSeconds;
    long summonCooldownSeconds;
    long reviveCooldownSeconds;
    String summonAuraEffectId;
    String expiryWarningEffectId;
    RevivePriceDefinition revivePrice;
    RoleRevivePriceDefinition[] revivePriceByRole = RoleRevivePriceDefinition.EMPTY_ARRAY;
    FeatureToggles features = new FeatureToggles();

    protected TwBondedCompanionRosterConfig() {
    }

    public static AssetStore<
            String,
            TwBondedCompanionRosterConfig,
            DefaultAssetMap<String, TwBondedCompanionRosterConfig>
            > getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(
                    TwBondedCompanionRosterConfig.class
            );
        }
        return assetStore;
    }

    @Nullable
    public static DefaultAssetMap<String, TwBondedCompanionRosterConfig>
            getAssetMap() {
        AssetStore<
                String,
                TwBondedCompanionRosterConfig,
                DefaultAssetMap<String, TwBondedCompanionRosterConfig>
                > store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwBondedCompanionRosterConfig> map =
                (DefaultAssetMap<String, TwBondedCompanionRosterConfig>)
                        store.getAssetMap();
        ensureInheritanceFallbackApplied(map);
        return map;
    }

    public static void clearInheritanceFallbackCache() {
        inheritanceCacheDirty = true;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<
                    String,
                    TwBondedCompanionRosterConfig
                    > map
    ) {
        if (!inheritanceCacheDirty || map == null
                || map.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!inheritanceCacheDirty || map.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(map);
            inheritanceCacheDirty = false;
        }
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parent = data.getParentKey().toString();
        return parent == null || parent.isBlank() ? null : parent;
    }

    @Override
    public void inheritMissingTopLevelFrom(
            @Nonnull TwBondedCompanionRosterConfig parent,
            @Nonnull Set<String> explicitTopLevelKeys
    ) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(
            @Nonnull TwBondedCompanionRosterConfig parent,
            @Nonnull Set<String> explicitTopLevelKeys,
            @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel
    ) {
        if (!explicitTopLevelKeys.contains("Priority")) {
            priority = parent.priority;
        }
        if (!explicitTopLevelKeys.contains("RosterId")) {
            rosterId = parent.rosterId;
        }
        if (!explicitTopLevelKeys.contains("FamilyId")) {
            familyId = parent.familyId;
        }
        if (!explicitTopLevelKeys.contains("AllowedRoles")) {
            allowedRoles = parent.allowedRoles;
        }
        if (!explicitTopLevelKeys.contains("MaximumOwned")) {
            maximumOwned = parent.maximumOwned;
        }
        if (!explicitTopLevelKeys.contains("MaximumActive")) {
            maximumActive = parent.maximumActive;
        }
        if (!explicitTopLevelKeys.contains("SessionDurationSeconds")) {
            sessionDurationSeconds = parent.sessionDurationSeconds;
        }
        if (!explicitTopLevelKeys.contains("SummonCooldownSeconds")) {
            summonCooldownSeconds = parent.summonCooldownSeconds;
        }
        if (!explicitTopLevelKeys.contains("ReviveCooldownSeconds")) {
            reviveCooldownSeconds = parent.reviveCooldownSeconds;
        }
        if (!explicitTopLevelKeys.contains("SummonAuraEffectId")) {
            summonAuraEffectId = parent.summonAuraEffectId;
        }
        if (!explicitTopLevelKeys.contains("ExpiryWarningEffectId")) {
            expiryWarningEffectId = parent.expiryWarningEffectId;
        }
        inheritRevivePrice(parent, explicitTopLevelKeys,
                explicitNestedKeysByTopLevel);
        if (!explicitTopLevelKeys.contains("RevivePriceByRole")) {
            revivePriceByRole = parent.revivePriceByRole;
        }
        inheritFeatures(parent, explicitTopLevelKeys,
                explicitNestedKeysByTopLevel);
    }

    private void inheritRevivePrice(
            TwBondedCompanionRosterConfig parent,
            Set<String> topLevel,
            @Nullable Map<String, Set<String>> nestedByTopLevel
    ) {
        if (!topLevel.contains("RevivePrice")) {
            revivePrice = parent.revivePrice;
            return;
        }
        Set<String> nested = nestedByTopLevel == null
                ? null
                : nestedByTopLevel.get("RevivePrice");
        if (nested == null || parent.revivePrice == null) {
            return;
        }
        if (revivePrice == null) {
            revivePrice = parent.revivePrice;
            return;
        }
        if (!nested.contains("Costs")) {
            revivePrice.costs = parent.revivePrice.getCosts();
        }
    }

    private void inheritFeatures(
            TwBondedCompanionRosterConfig parent,
            Set<String> topLevel,
            @Nullable Map<String, Set<String>> nestedByTopLevel
    ) {
        if (!topLevel.contains("Features")) {
            features = parent.features;
            return;
        }
        Set<String> nested = nestedByTopLevel == null
                ? null
                : nestedByTopLevel.get("Features");
        if (nested == null || parent.features == null) {
            return;
        }
        if (features == null) {
            features = parent.features;
            return;
        }
        if (!nested.contains("Capture")) features.capture = parent.features.capture;
        if (!nested.contains("Provision")) features.provision = parent.features.provision;
        if (!nested.contains("Summon")) features.summon = parent.features.summon;
        if (!nested.contains("Dismiss")) features.dismiss = parent.features.dismiss;
        if (!nested.contains("Revive")) features.revive = parent.features.revive;
    }

    public void validateOrThrow() {
        String configId = requireText(id, "config id");
        requireNamespaced(rosterId, "RosterId", configId);
        requireNamespaced(familyId, "FamilyId", configId);
        if (allowedRoles == null || allowedRoles.length == 0) {
            throw new IllegalArgumentException(
                    "Bonded roster " + configId
                            + " requires at least one AllowedRole."
            );
        }
        HashSet<String> roles = new HashSet<>();
        for (String roleId : allowedRoles) {
            String role = requireText(roleId, "AllowedRole");
            if (!roles.add(role)) {
                throw new IllegalArgumentException(
                        "Bonded roster " + configId
                                + " repeats role " + role + '.'
                );
            }
        }
        if (maximumOwned < 0 || maximumActive < 0
                || sessionDurationSeconds < 0L
                || summonCooldownSeconds < 0L
                || reviveCooldownSeconds < 0L) {
            throw new IllegalArgumentException(
                    "Bonded roster counts and timers cannot be negative: "
                            + configId
            );
        }
        validateRevivePrice(configId);
        validateRoleRevivePrices(configId);
        if (features == null) {
            throw new IllegalArgumentException(
                    "Bonded roster Features are required: " + configId
            );
        }
    }

    private void validateRevivePrice(String configId) {
        if (revivePrice == null) {
            return;
        }
        TwItemCostComponent[] costs = revivePrice.getCosts();
        if (costs.length == 0) {
            throw new IllegalArgumentException(
                    "Bonded roster RevivePrice requires a non-empty Costs "
                            + "recipe: " + configId
            );
        }
        TwItemCostComponent.validateAndCopy(costs);
    }

    private void validateRoleRevivePrices(String configId) {
        HashSet<String> roles = new HashSet<>();
        for (RoleRevivePriceDefinition entry : getRevivePriceByRole()) {
            if (entry == null) {
                throw new IllegalArgumentException("Bonded roster RevivePriceByRole cannot contain null: " + configId);
            }
            String roleId = requireText(entry.roleId, "RevivePriceByRole RoleId");
            if (!roles.add(roleId)) {
                throw new IllegalArgumentException("Bonded roster RevivePriceByRole repeats role " + roleId + ": " + configId);
            }
            if (!Set.of(getAllowedRoles()).contains(roleId)) {
                throw new IllegalArgumentException("Bonded roster RevivePriceByRole role is not allowed: " + roleId);
            }
            TwItemCostComponent[] costs = entry.getCosts();
            if (costs.length == 0) {
                throw new IllegalArgumentException("Bonded roster RevivePriceByRole requires Costs: " + roleId);
            }
            TwItemCostComponent.validateAndCopy(costs);
        }
    }

    private static void requireNamespaced(
            String value,
            String field,
            String configId
    ) {
        String normalized = requireText(value, field);
        if (!NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Bonded roster " + field + " must be namespaced in "
                            + configId + ": " + normalized
            );
        }
    }

    public String getId() {
        return id;
    }

    public int getPriority() {
        return priority;
    }

    public String getRosterId() {
        return normalize(rosterId);
    }

    public String getFamilyId() {
        return normalize(familyId);
    }

    public String[] getAllowedRoles() {
        return allowedRoles == null
                ? ArrayUtil.EMPTY_STRING_ARRAY
                : allowedRoles.clone();
    }

    public int getMaximumOwned() {
        return maximumOwned;
    }

    public int getMaximumActive() {
        return maximumActive;
    }

    public long getSessionDurationSeconds() {
        return sessionDurationSeconds;
    }

    public long getSummonCooldownSeconds() {
        return summonCooldownSeconds;
    }

    /** Returns the delay between confirmed death and an eligible revive. */
    public long getReviveCooldownSeconds() {
        return reviveCooldownSeconds;
    }

    /** Optional visual effect applied after this roster successfully summons. */
    @Nullable
    public String getSummonAuraEffectId() {
        return normalize(summonAuraEffectId);
    }

    /** Optional visual effect applied to a finite lease at its 30-second warning. */
    @Nullable
    public String getExpiryWarningEffectId() {
        return normalize(expiryWarningEffectId);
    }

    @Nullable
    public RevivePriceDefinition getRevivePrice() {
        return revivePrice;
    }

    @Nonnull
    public RoleRevivePriceDefinition[] getRevivePriceByRole() {
        return revivePriceByRole == null ? RoleRevivePriceDefinition.EMPTY_ARRAY : revivePriceByRole.clone();
    }

    @Nonnull
    public FeatureToggles getFeatures() {
        return features == null ? new FeatureToggles() : features;
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Mutable codec target for the optional ordered bonded revive recipe. */
    public static final class RevivePriceDefinition {
        TwItemCostComponent[] costs = TwItemCostComponent.EMPTY_ARRAY;

        @Nonnull
        public TwItemCostComponent[] getCosts() {
            return TwItemCostComponent.validateAndCopy(costs);
        }
    }

    /** Mutable codec target for a role-specific revive recipe. */
    public static final class RoleRevivePriceDefinition {
        static final RoleRevivePriceDefinition[] EMPTY_ARRAY = new RoleRevivePriceDefinition[0];
        String roleId;
        TwItemCostComponent[] costs = TwItemCostComponent.EMPTY_ARRAY;

        @Nullable public String getRoleId() { return normalize(roleId); }
        @Nonnull public TwItemCostComponent[] getCosts() { return TwItemCostComponent.validateAndCopy(costs); }
    }

    /** Mutable codec target for per-roster bonded feature toggles. */
    public static final class FeatureToggles {
        boolean capture = true;
        boolean provision = true;
        boolean summon = true;
        boolean dismiss = true;
        boolean revive = true;

        public boolean isCapture() {
            return capture;
        }

        public boolean isProvision() {
            return provision;
        }

        public boolean isSummon() {
            return summon;
        }

        public boolean isDismiss() {
            return dismiss;
        }

        public boolean isRevive() {
            return revive;
        }
    }
}
