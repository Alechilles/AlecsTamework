package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Generic managed-activity content profile stored under
 * {@code Server/Tamework/ManagedActivities}.
 *
 * <p>Object sections inherit omitted nested fields. Arrays and maps replace
 * the parent section when explicitly authored. The registry performs the
 * cross-asset validation against population-group definitions.</p>
 */
public final class TwManagedActivityConfig
        implements JsonAssetWithMap<
                String,
                DefaultAssetMap<String, TwManagedActivityConfig>
                >,
        TwParentFallbackAsset<TwManagedActivityConfig> {
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*:[A-Za-z0-9][A-Za-z0-9_./:-]*"
    );
    private static final DomainEntry[] EMPTY_DOMAINS = new DomainEntry[0];
    private static final FamilyEntry[] EMPTY_FAMILIES = new FamilyEntry[0];
    private static final String[] EMPTY_CAPABILITIES =
            ArrayUtil.EMPTY_STRING_ARRAY;

    public static final BuilderCodec<DomainEntry> DOMAIN_CODEC =
            BuilderCodec.builder(DomainEntry.class, DomainEntry::new)
                    .<String>append(
                            new KeyedCodec<>("DomainId", Codec.STRING),
                            (entry, value) -> entry.domainId = value,
                            entry -> entry.domainId
                    )
                    .documentation(
                            "Stable namespaced capacity-domain identity."
                    )
                    .add()
                    .<Boolean>append(
                            new KeyedCodec<>("Owned", Codec.BOOLEAN),
                            (entry, value) -> entry.owned = value != null && value,
                            entry -> entry.owned
                    )
                    .documentation(
                            "When true, this domain charges owned capacity."
                    )
                    .add()
                    .<Boolean>append(
                            new KeyedCodec<>("Deployable", Codec.BOOLEAN),
                            (entry, value) -> entry.deployable = value != null && value,
                            entry -> entry.deployable
                    )
                    .documentation(
                            "When true, this domain charges deployable capacity."
                    )
                    .add()
                    .build();

    public static final ArrayCodec<DomainEntry> DOMAIN_ARRAY_CODEC =
            new ArrayCodec<>(DOMAIN_CODEC, DomainEntry[]::new);

    public static final BuilderCodec<FamilyEntry> FAMILY_CODEC =
            BuilderCodec.builder(FamilyEntry.class, FamilyEntry::new)
                    .<String>append(
                            new KeyedCodec<>("GroupId", Codec.STRING),
                            (entry, value) -> entry.groupId = value,
                            entry -> entry.groupId
                    )
                    .documentation(
                            "Existing Tamework population-group ID backing this family."
                    )
                    .add()
                    .<String>append(
                            new KeyedCodec<>("GateKey", Codec.STRING),
                            (entry, value) -> entry.gateKey = value,
                            entry -> entry.gateKey
                    )
                    .documentation(
                            "Namespaced player-facing progression gate identity."
                    )
                    .add()
                    .<Integer>append(
                            new KeyedCodec<>("Weight", Codec.INTEGER),
                            (entry, value) -> entry.weight = value == null ? 0 : value,
                            entry -> entry.weight
                    )
                    .documentation(
                            "Positive capacity weight for this family."
                    )
                    .add()
                    .build();

    public static final ArrayCodec<FamilyEntry> FAMILY_ARRAY_CODEC =
            new ArrayCodec<>(FAMILY_CODEC, FamilyEntry[]::new);

    public static final BuilderCodec<ActivitySettings> ACTIVITIES_CODEC =
            BuilderCodec.builder(ActivitySettings.class, ActivitySettings::new)
                    .<String>append(
                            new KeyedCodec<>("Feed", Codec.STRING),
                            (settings, value) -> settings.feed = value,
                            settings -> settings.feed
                    )
                    .documentation(
                            "Namespaced activity emitted after qualified feeding."
                    )
                    .add()
                    .<Map<String, String>>append(
                            new KeyedCodec<>(
                                    "HarvestContexts",
                                    MapCodec.STRING_HASH_MAP_CODEC
                            ),
                            (settings, value) -> settings.harvestContexts =
                                    value == null ? Map.of() : value,
                            settings -> settings.harvestContexts
                    )
                    .documentation(
                            "Map of harvest context IDs to namespaced activities."
                    )
                    .add()
                    .<Map<String, String>>append(
                            new KeyedCodec<>(
                                    "PendingOutputItems",
                                    MapCodec.STRING_HASH_MAP_CODEC
                            ),
                            (settings, value) -> settings.pendingOutputItems =
                                    value == null ? Map.of() : value,
                            settings -> settings.pendingOutputItems
                    )
                    .documentation(
                            "Map of pending item IDs to namespaced activities."
                    )
                    .add()
                    .<String>append(
                            new KeyedCodec<>("BreedingSuccess", Codec.STRING),
                            (settings, value) -> settings.breedingSuccess = value,
                            settings -> settings.breedingSuccess
                    )
                    .documentation(
                            "Namespaced activity emitted after a successful breeding operation."
                    )
                    .add()
                    .build();

    public static final AssetBuilderCodec<String, TwManagedActivityConfig>
            CODEC = AssetBuilderCodec.builder(
                    TwManagedActivityConfig.class,
                    TwManagedActivityConfig::new,
                    Codec.STRING,
                    (asset, id) -> asset.id = id,
                    asset -> asset.id,
                    (asset, data) -> asset.data = data,
                    asset -> asset.data
            )
            .documentation(
                    "Provider-neutral managed activity content profile. "
                            + "Omitted object sections inherit; explicit arrays "
                            + "and maps replace their parent sections."
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled
            )
            .documentation(
                    "Disabled profiles are inert and unavailable to runtime consumers."
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation(
                    "Higher priority wins for duplicate profile IDs; asset ID breaks ties."
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("ProfileId", Codec.STRING),
                    (asset, value) -> asset.profileId = value,
                    asset -> asset.profileId
            )
            .documentation("Stable namespaced profile identity.")
            .add()
            .<String>append(
                    new KeyedCodec<>("ProviderId", Codec.STRING),
                    (asset, value) -> asset.providerId = value,
                    asset -> asset.providerId
            )
            .documentation("Stable namespaced external provider identity.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ProviderContractVersion", Codec.INTEGER),
                    (asset, value) -> asset.providerContractVersion =
                            value == null ? 0 : value,
                    asset -> asset.providerContractVersion
            )
            .documentation("Positive provider contract version.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RequiredCapabilities", Codec.STRING_ARRAY),
                    (asset, value) -> asset.requiredCapabilities = value,
                    asset -> asset.requiredCapabilities
            )
            .documentation(
                    "Tamework API capability names. Explicit arrays replace parent values."
            )
            .add()
            .<DomainEntry[]>append(
                    new KeyedCodec<>("Domains", DOMAIN_ARRAY_CODEC),
                    (asset, value) -> asset.domains = value,
                    asset -> asset.domains
            )
            .documentation(
                    "Named capacity domains. Omitted arrays inherit; explicit arrays replace."
            )
            .add()
            .<FamilyEntry[]>append(
                    new KeyedCodec<>("Families", FAMILY_ARRAY_CODEC),
                    (asset, value) -> asset.families = value,
                    asset -> asset.families
            )
            .documentation(
                    "Population-backed gate families. Omitted arrays inherit; explicit arrays replace."
            )
            .add()
            .<ActivitySettings>append(
                    new KeyedCodec<>("Activities", ACTIVITIES_CODEC),
                    (asset, value) -> asset.activities = value,
                    asset -> asset.activities
            )
            .documentation(
                    "Activity IDs and mappings. Omitted object inherits; explicit nested fields override and missing fields inherit."
            )
            .add()
            .build();

    private static AssetStore<
            String,
            TwManagedActivityConfig,
            DefaultAssetMap<String, TwManagedActivityConfig>
            > assetStore;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean inheritanceCacheDirty = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String profileId;
    private String providerId;
    private int providerContractVersion;
    private String[] requiredCapabilities;
    private DomainEntry[] domains;
    private FamilyEntry[] families;
    private ActivitySettings activities;

    private TwManagedActivityConfig() {
    }

    public static AssetStore<
            String,
            TwManagedActivityConfig,
            DefaultAssetMap<String, TwManagedActivityConfig>
            > getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(
                    TwManagedActivityConfig.class
            );
        }
        return assetStore;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static DefaultAssetMap<String, TwManagedActivityConfig>
            getAssetMap() {
        AssetStore<
                String,
                TwManagedActivityConfig,
                DefaultAssetMap<String, TwManagedActivityConfig>
                > store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwManagedActivityConfig> map =
                (DefaultAssetMap<String, TwManagedActivityConfig>)
                        store.getAssetMap();
        ensureInheritanceFallbackApplied(map);
        return map;
    }

    public static void clearInheritanceFallbackCache() {
        inheritanceCacheDirty = true;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwManagedActivityConfig> map
    ) {
        if (!inheritanceCacheDirty || map == null || map.getAssetMap() == null) {
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
            @Nonnull TwManagedActivityConfig parent,
            @Nonnull Set<String> explicitTopLevelKeys
    ) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(
            @Nonnull TwManagedActivityConfig parent,
            @Nonnull Set<String> explicitTopLevelKeys,
            @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel
    ) {
        if (!explicitTopLevelKeys.contains("Enabled")) {
            enabled = parent.enabled;
        }
        if (!explicitTopLevelKeys.contains("Priority")) {
            priority = parent.priority;
        }
        if (!explicitTopLevelKeys.contains("ProfileId")) {
            profileId = parent.profileId;
        }
        if (!explicitTopLevelKeys.contains("ProviderId")) {
            providerId = parent.providerId;
        }
        if (!explicitTopLevelKeys.contains("ProviderContractVersion")) {
            providerContractVersion = parent.providerContractVersion;
        }
        if (!explicitTopLevelKeys.contains("RequiredCapabilities")) {
            requiredCapabilities = parent.requiredCapabilities;
        }
        if (!explicitTopLevelKeys.contains("Domains")) {
            domains = parent.domains;
        }
        if (!explicitTopLevelKeys.contains("Families")) {
            families = parent.families;
        }
        if (!explicitTopLevelKeys.contains("Activities")) {
            activities = parent.activities;
            return;
        }
        Set<String> nested = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("Activities");
        inheritActivities(parent, nested);
    }

    private void inheritActivities(
            TwManagedActivityConfig parent,
            @Nullable Set<String> nested
    ) {
        if (parent.activities == null) {
            return;
        }
        if (activities == null || nested == null) {
            activities = parent.activities;
            return;
        }
        if (!nested.contains("Feed")) {
            activities.feed = parent.activities.feed;
        }
        if (!nested.contains("HarvestContexts")) {
            activities.harvestContexts = parent.activities.harvestContexts;
        }
        if (!nested.contains("PendingOutputItems")) {
            activities.pendingOutputItems = parent.activities.pendingOutputItems;
        }
        if (!nested.contains("BreedingSuccess")) {
            activities.breedingSuccess = parent.activities.breedingSuccess;
        }
    }

    /** Validates fields that do not require the population-group registry. */
    public void validateOrThrow() {
        String configId = requireText(id, "config id");
        if (!enabled) {
            return;
        }
        String profile = requireNamespaced(profileId, "ProfileId", configId);
        requireNamespaced(providerId, "ProviderId", configId);
        if (providerContractVersion <= 0) {
            throw new IllegalArgumentException(
                    "ProviderContractVersion must be positive in " + configId
            );
        }
        String[] capabilities = getRequiredCapabilities();
        Set<String> capabilityIds = new HashSet<>();
        for (String capability : capabilities) {
            String normalized = requireText(capability, "required capability");
            if (!capabilityIds.add(normalized.toUpperCase())) {
                throw new IllegalArgumentException(
                        "Duplicate required capability in " + configId
                );
            }
        }
        DomainEntry[] domainEntries = getDomains();
        if (domainEntries.length == 0) {
            throw new IllegalArgumentException(
                    "Enabled managed profile " + profile
                            + " requires at least one domain"
            );
        }
        Set<String> domainIds = new HashSet<>();
        for (DomainEntry domain : domainEntries) {
            if (domain == null) {
                throw new IllegalArgumentException(
                        "Null domain in managed profile " + profile
                );
            }
            String domainId = requireNamespaced(
                    domain.domainId,
                    "DomainId",
                    configId
            );
            if (!domain.owned && !domain.deployable) {
                throw new IllegalArgumentException(
                        "Domain must apply to owned or deployable capacity: "
                                + domainId
                );
            }
            if (!domainIds.add(domainId)) {
                throw new IllegalArgumentException(
                        "Duplicate domain ID in " + configId + ": " + domainId
                );
            }
        }
        FamilyEntry[] familyEntries = getFamilies();
        if (familyEntries.length == 0) {
            throw new IllegalArgumentException(
                    "Enabled managed profile " + profile
                            + " requires at least one family"
            );
        }
        Set<String> groupIds = new HashSet<>();
        for (FamilyEntry family : familyEntries) {
            if (family == null) {
                throw new IllegalArgumentException(
                        "Null family in managed profile " + profile
                );
            }
            String groupId = requireNamespaced(
                    family.groupId,
                    "GroupId",
                    configId
            );
            requireNamespaced(family.gateKey, "GateKey", configId);
            if (family.weight <= 0) {
                throw new IllegalArgumentException(
                        "Family weight must be positive in " + configId
                );
            }
            if (!groupIds.add(groupId)) {
                throw new IllegalArgumentException(
                        "Duplicate family GroupId in " + configId + ": " + groupId
                );
            }
        }
        validateActivities(configId, activities);
    }

    private static void validateActivities(
            String configId,
            @Nullable ActivitySettings settings
    ) {
        if (settings == null) {
            throw new IllegalArgumentException(
                    "Activities are required in " + configId
            );
        }
        requireNamespaced(settings.feed, "Feed", configId);
        requireNamespaced(settings.breedingSuccess, "BreedingSuccess", configId);
        validateMapping(
                settings.harvestContexts,
                "HarvestContexts",
                configId
        );
        validateMapping(
                settings.pendingOutputItems,
                "PendingOutputItems",
                configId
        );
    }

    private static void validateMapping(
            @Nullable Map<String, String> mapping,
            String field,
            String configId
    ) {
        if (mapping == null || mapping.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " are required in " + configId
            );
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            requireText(entry.getKey(), field + " key");
            requireNamespaced(entry.getValue(), field + " value", configId);
        }
    }

    private static String requireNamespaced(
            @Nullable String value,
            String field,
            String configId
    ) {
        String normalized = requireText(value, field + " in " + configId);
        if (!NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be namespaced in " + configId + ": " + normalized
            );
        }
        return normalized;
    }

    private static String requireText(
            @Nullable String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    @Nullable
    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    @Nullable
    public String getProfileId() {
        return profileId == null ? null : profileId.trim();
    }

    @Nullable
    public String getProviderId() {
        return providerId == null ? null : providerId.trim();
    }

    public int getProviderContractVersion() {
        return providerContractVersion;
    }

    public String[] getRequiredCapabilities() {
        return requiredCapabilities == null
                ? EMPTY_CAPABILITIES.clone()
                : requiredCapabilities.clone();
    }

    public DomainEntry[] getDomains() {
        return domains == null ? EMPTY_DOMAINS.clone() : domains.clone();
    }

    public FamilyEntry[] getFamilies() {
        return families == null ? EMPTY_FAMILIES.clone() : families.clone();
    }

    @Nullable
    public ActivitySettings getActivities() {
        return activities;
    }

    /** Returns whether the raw profile supplied a required-capability section. */
    public boolean hasRequiredCapabilities() {
        return requiredCapabilities != null;
    }

    /** Named capacity domain entry. */
    public static final class DomainEntry {
        private String domainId;
        private boolean owned;
        private boolean deployable;

        private DomainEntry() {
        }

        public String getDomainId() {
            return domainId;
        }

        public boolean isOwned() {
            return owned;
        }

        public boolean isDeployable() {
            return deployable;
        }
    }

    /** Population-group-backed family entry. */
    public static final class FamilyEntry {
        private String groupId;
        private String gateKey;
        private int weight;

        private FamilyEntry() {
        }

        public String getGroupId() {
            return groupId;
        }

        public String getGateKey() {
            return gateKey;
        }

        public int getWeight() {
            return weight;
        }
    }

    /** Activity IDs and maps consumed by generic activity runtime. */
    public static final class ActivitySettings {
        private String feed;
        private Map<String, String> harvestContexts = Map.of();
        private Map<String, String> pendingOutputItems = Map.of();
        private String breedingSuccess;

        private ActivitySettings() {
        }

        public String getFeed() {
            return feed;
        }

        public Map<String, String> getHarvestContexts() {
            return harvestContexts == null
                    ? Map.of()
                    : Map.copyOf(harvestContexts);
        }

        public Map<String, String> getPendingOutputItems() {
            return pendingOutputItems == null
                    ? Map.of()
                    : Map.copyOf(pendingOutputItems);
        }

        public String getBreedingSuccess() {
            return breedingSuccess;
        }
    }
}
