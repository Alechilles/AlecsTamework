package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
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
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** Role-scoped capture difficulty stored under {@code Server/Tamework/CapturePolicies}. */
public final class TwCapturePolicyConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCapturePolicyConfig>>,
        TwParentFallbackAsset<TwCapturePolicyConfig> {
    private static final int MAX_JSON_PAYLOAD_LENGTH = 8_192;
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*:[A-Za-z0-9][A-Za-z0-9_./:-]*"
    );

    public static final BuilderCodec<DifficultySettings> DIFFICULTY_CODEC = BuilderCodec.builder(
            DifficultySettings.class, DifficultySettings::new
    )
            .<Integer>append(new KeyedCodec<>("MinimumPower", Codec.INTEGER),
                    (value, decoded) -> value.minimumPower = decoded, value -> value.minimumPower)
            .documentation("Minimum eligible item power. Must be non-negative.").add()
            .<Double>append(new KeyedCodec<>("Resistance", Codec.DOUBLE),
                    (value, decoded) -> value.resistance = decoded, value -> value.resistance)
            .documentation("Finite non-negative chance removed before multiplication.").add()
            .<Double>append(new KeyedCodec<>("ChanceMultiplier", Codec.DOUBLE),
                    (value, decoded) -> value.chanceMultiplier = decoded, value -> value.chanceMultiplier)
            .documentation("Finite non-negative multiplier applied to adjusted capture chance.").add()
            .<Double>append(new KeyedCodec<>("MissingHealthBonus", Codec.DOUBLE),
                    (value, decoded) -> value.missingHealthBonus = decoded, value -> value.missingHealthBonus)
            .documentation("Finite non-negative maximum bonus from target missing-health fraction.").add()
            .<Integer>append(new KeyedCodec<>("GuaranteedAtPower", Codec.INTEGER),
                    (value, decoded) -> value.guaranteedAtPower = decoded, value -> value.guaranteedAtPower)
            .documentation("Optional non-negative power at which an eligible capture succeeds without entropy.").add()
            .build();

    public static final BuilderCodec<RequirementSettings> REQUIREMENT_CODEC = BuilderCodec.builder(
            RequirementSettings.class, RequirementSettings::new
    )
            .<String>append(new KeyedCodec<>("Id", Codec.STRING),
                    (value, decoded) -> value.id = decoded, value -> value.id)
            .documentation("Namespaced capture-requirement handler ID.").add()
            .<String>append(new KeyedCodec<>("Param", Codec.STRING),
                    (value, decoded) -> value.param = decoded, value -> value.param)
            .documentation("Optional short handler parameter.").add()
            .<String[]>append(new KeyedCodec<>("Values", Codec.STRING_ARRAY),
                    (value, decoded) -> value.values = decoded == null ? ArrayUtil.EMPTY_STRING_ARRAY : decoded,
                    value -> value.values)
            .documentation("Immutable handler values. Explicit arrays replace inherited Requirements as a whole.").add()
            .<String>append(new KeyedCodec<>("JsonPayload", Codec.STRING),
                    (value, decoded) -> value.jsonPayload = decoded, value -> value.jsonPayload)
            .documentation("Optional bounded valid JSON object/array/scalar text supplied as immutable config.").add()
            .build();

    public static final ArrayCodec<RequirementSettings> REQUIREMENTS_CODEC =
            new ArrayCodec<>(REQUIREMENT_CODEC, RequirementSettings[]::new);

    public static final AssetBuilderCodec<String, TwCapturePolicyConfig> CODEC = AssetBuilderCodec.builder(
            TwCapturePolicyConfig.class,
            TwCapturePolicyConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped capture difficulty and side-effect-free requirements.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value, asset -> asset.enabled)
            .documentation("Disabled assets are inert. Omitted value inherits from parent.").add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value, asset -> asset.priority)
            .documentation("Higher priority wins; deterministic asset-ID ordering breaks ties. Omitted inherits.").add()
            .<String[]>append(new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds)
            .documentation("Exact source role IDs. Omitted inherits; an explicit array replaces parent (no merge).")
            .add()
            .<DifficultySettings>append(new KeyedCodec<>("Difficulty", DIFFICULTY_CODEC),
                    (asset, value) -> asset.difficulty = value == null ? new DifficultySettings() : value,
                    asset -> asset.difficulty)
            .documentation("Difficulty policy. Omitted inherits the parent object; when explicit, missing nested fields inherit.")
            .add()
            .<RequirementSettings[]>append(new KeyedCodec<>("Requirements", REQUIREMENTS_CODEC),
                    (asset, value) -> asset.requirements = value == null ? new RequirementSettings[0] : value,
                    asset -> asset.requirements)
            .documentation("Side-effect-free extension requirements. Omitted inherits; an explicit array replaces parent.")
            .add()
            .build();

    private static AssetStore<String, TwCapturePolicyConfig, DefaultAssetMap<String, TwCapturePolicyConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private DifficultySettings difficulty = new DifficultySettings();
    private RequirementSettings[] requirements = new RequirementSettings[0];

    protected TwCapturePolicyConfig() {
    }

    public static AssetStore<String, TwCapturePolicyConfig, DefaultAssetMap<String, TwCapturePolicyConfig>> getAssetStore() {
        if (ASSET_STORE == null) ASSET_STORE = AssetRegistry.getAssetStore(TwCapturePolicyConfig.class);
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCapturePolicyConfig> getAssetMap() {
        AssetStore<String, TwCapturePolicyConfig, DefaultAssetMap<String, TwCapturePolicyConfig>> store = getAssetStore();
        if (store == null) return null;
        DefaultAssetMap<String, TwCapturePolicyConfig> map =
                (DefaultAssetMap<String, TwCapturePolicyConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(map);
        return map;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwCapturePolicyConfig> map) {
        if (!INHERITANCE_CACHE_DIRTY || map == null || map.getAssetMap() == null) return;
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || map.getAssetMap() == null) return;
            TwAssetInheritanceFallback.repairAll(map);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) return null;
        String parent = data.getParentKey().toString();
        return parent == null || parent.isBlank() ? null : parent;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCapturePolicyConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCapturePolicyConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Difficulty")) {
            difficulty = parent.difficulty;
        } else {
            Set<String> nested = explicitNestedKeysByTopLevel == null
                    ? null : explicitNestedKeysByTopLevel.get("Difficulty");
            inheritDifficulty(parent, nested);
        }
        if (!explicitTopLevelKeys.contains("Requirements")) requirements = parent.requirements;
    }

    private void inheritDifficulty(TwCapturePolicyConfig parent, @Nullable Set<String> nested) {
        if (nested == null || parent.difficulty == null) return;
        if (difficulty == null) {
            difficulty = parent.difficulty;
            return;
        }
        if (!nested.contains("MinimumPower")) difficulty.minimumPower = parent.difficulty.minimumPower;
        if (!nested.contains("Resistance")) difficulty.resistance = parent.difficulty.resistance;
        if (!nested.contains("ChanceMultiplier")) difficulty.chanceMultiplier = parent.difficulty.chanceMultiplier;
        if (!nested.contains("MissingHealthBonus")) difficulty.missingHealthBonus = parent.difficulty.missingHealthBonus;
        if (!nested.contains("GuaranteedAtPower")) difficulty.guaranteedAtPower = parent.difficulty.guaranteedAtPower;
    }

    public void validateOrThrow() {
        String configId = requireText(id, "config id");
        if (!enabled) return;
        String[] roles = getRoleIds();
        if (roles.length == 0) {
            throw new IllegalArgumentException("Enabled capture policy " + configId + " requires at least one RoleId.");
        }
        HashSet<String> unique = new HashSet<>();
        for (String role : roles) {
            String normalized = requireText(role, "role id");
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException("Capture policy " + configId + " repeats role " + normalized + '.');
            }
        }
        DifficultySettings policy = getDifficulty();
        if (policy.minimumPower < 0 || (policy.guaranteedAtPower != null && policy.guaranteedAtPower < 0)) {
            throw new IllegalArgumentException("Capture policy power values cannot be negative: " + configId);
        }
        requireFiniteNonNegative(policy.resistance, "Resistance", configId);
        requireFiniteNonNegative(policy.chanceMultiplier, "ChanceMultiplier", configId);
        requireFiniteNonNegative(policy.missingHealthBonus, "MissingHealthBonus", configId);
        for (RequirementSettings requirement : getRequirements()) validateRequirement(configId, requirement);
    }

    private static void validateRequirement(String configId, @Nullable RequirementSettings requirement) {
        if (requirement == null) throw new IllegalArgumentException("Null requirement in capture policy " + configId);
        String requirementId = requireText(requirement.id, "requirement id");
        if (!NAMESPACED_ID.matcher(requirementId).matches()) {
            throw new IllegalArgumentException("Capture requirement ID must be namespaced: " + requirementId);
        }
        if (requirement.param != null && requirement.param.length() > 512) {
            throw new IllegalArgumentException("Capture requirement Param exceeds 512 characters: " + requirementId);
        }
        if (requirement.jsonPayload != null) {
            if (requirement.jsonPayload.length() > MAX_JSON_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("Capture requirement JsonPayload exceeds 8192 characters: " + requirementId);
            }
            try {
                BsonDocument.parse("{\"value\":" + requirement.jsonPayload + '}');
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Capture requirement JsonPayload is invalid JSON: " + requirementId, invalid);
            }
        }
    }

    private static void requireFiniteNonNegative(double value, String field, String configId) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(field + " must be finite and non-negative in " + configId);
        }
    }

    public CapturePolicyConfigView toView(long revision) {
        validateOrThrow();
        List<CaptureRequirementSpec> specs = new ArrayList<>();
        for (RequirementSettings requirement : getRequirements()) specs.add(requirement.toSpec());
        DifficultySettings policy = getDifficulty();
        return new CapturePolicyConfigView(
                id, revision, priority, Set.copyOf(List.of(getRoleIds())),
                policy.minimumPower, policy.resistance, policy.chanceMultiplier,
                policy.missingHealthBonus, policy.guaranteedAtPower, specs
        );
    }

    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public int getPriority() { return priority; }
    public String[] getRoleIds() { return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds.clone(); }
    public DifficultySettings getDifficulty() { return difficulty == null ? new DifficultySettings() : difficulty; }
    public RequirementSettings[] getRequirements() {
        return requirements == null ? new RequirementSettings[0] : requirements.clone();
    }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
        return value.trim();
    }

    public static final class DifficultySettings {
        private int minimumPower;
        private double resistance;
        private double chanceMultiplier = 1.0D;
        private double missingHealthBonus;
        private Integer guaranteedAtPower;

        public int getMinimumPower() { return minimumPower; }
        public double getResistance() { return resistance; }
        public double getChanceMultiplier() { return chanceMultiplier; }
        public double getMissingHealthBonus() { return missingHealthBonus; }
        @Nullable public Integer getGuaranteedAtPower() { return guaranteedAtPower; }
    }

    public static final class RequirementSettings {
        private String id;
        private String param;
        private String[] values = ArrayUtil.EMPTY_STRING_ARRAY;
        private String jsonPayload;

        public String getId() { return id; }
        @Nullable public String getParam() { return param; }
        public String[] getValues() { return values == null ? ArrayUtil.EMPTY_STRING_ARRAY : values.clone(); }
        @Nullable public String getJsonPayload() { return jsonPayload; }
        public CaptureRequirementSpec toSpec() {
            return new CaptureRequirementSpec(id, param, List.of(getValues()), jsonPayload);
        }
    }
}
