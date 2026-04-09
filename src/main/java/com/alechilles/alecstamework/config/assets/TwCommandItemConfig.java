package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.math.codec.Vector3dArrayCodec;
import com.hypixel.hytale.math.vector.Vector3d;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonNull;
import org.bson.BsonValue;

/**
 * Asset-backed configuration for command items.
 * Stored under Server/Tamework/Items/Commands.
 */
public class TwCommandItemConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCommandItemConfig>>,
        TwParentFallbackAsset<TwCommandItemConfig> {
    public enum MembershipMode {
        LinkedOnly,
        OwnerScope,
        MasterTarget,
        LinkedOrMasterTarget;

        public static MembershipMode fromString(String value) {
            if (value == null || value.isBlank()) {
                return LinkedOnly;
            }
            for (MembershipMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return LinkedOnly;
        }
    }

    public enum RoleFilterMode {
        AllowAll,
        Allowlist,
        Denylist
    }

    public enum FailurePolicy {
        Continue,
        AbortCommandForNpc,
        AbortAll;

        public static FailurePolicy fromString(String value) {
            if (value == null || value.isBlank()) {
                return Continue;
            }
            for (FailurePolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(value.trim())) {
                    return policy;
                }
            }
            return Continue;
        }
    }

    public enum TargetSource {
        CrosshairTarget,
        LastAttackTarget,
        OwnerPlayer,
        StoredTarget;

        public static TargetSource fromString(String value) {
            if (value == null || value.isBlank()) {
                return CrosshairTarget;
            }
            for (TargetSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
            return CrosshairTarget;
        }
    }

    public enum MoveSource {
        RaycastHit,
        OwnerPosition,
        StoredHome;

        public static MoveSource fromString(String value) {
            if (value == null || value.isBlank()) {
                return RaycastHit;
            }
            for (MoveSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
            return RaycastHit;
        }
    }

    public enum StoreSource {
        RaycastHit,
        OwnerPosition;

        public static StoreSource fromString(String value) {
            if (value == null || value.isBlank()) {
                return RaycastHit;
            }
            for (StoreSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
            return RaycastHit;
        }
    }

    private static final Codec<String[]> NPC_ROLE_ARRAY_CODEC = new TwSilentCodec<>() {
        @Override
        public String[] decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            return TwCodecLenient.asStringArrayOrEmpty(bsonValue);
        }

        @Override
        public BsonValue encode(String[] value, ExtraInfo extraInfo) {
            if (value == null) {
                return new BsonNull();
            }
            return Codec.STRING_ARRAY.encode(value, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            StringSchema roleSchema = new StringSchema();
            roleSchema.setHytaleAssetRef("NPCRole");
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItem(roleSchema);
            return arraySchema;
        }
    };

    private static Codec<String> assetRefCodec(String assetType) {
        return new TwSilentCodec<>() {
            @Override
            public String decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
                return TwCodecLenient.asStringOrNull(bsonValue);
            }

            @Override
            public BsonValue encode(String value, ExtraInfo extraInfo) {
                if (value == null) {
                    return new BsonNull();
                }
                return Codec.STRING.encode(value, extraInfo);
            }

            @Nonnull
            @Override
            public Schema toSchema(@Nonnull SchemaContext context) {
                StringSchema schema = new StringSchema();
                schema.setHytaleAssetRef(assetType);
                return schema;
            }
        };
    }

    private static final Codec<String> PARTICLE_SYSTEM_CODEC = assetRefCodec("ParticleSystem");
    private static final Codec<String> SOUND_EVENT_CODEC = assetRefCodec("SoundEvent");
    private static final Codec<Vector3d> VECTOR3D_CODEC = new Vector3dArrayCodec();

    private static final BuilderCodec<AllowedRoles> ALLOWED_ROLES_BASE_CODEC = BuilderCodec.abstractBuilder(
            AllowedRoles.class
        )
        .build();

    private static final BuilderCodec<AllowAllRoles> ALLOW_ALL_ROLES_CODEC = BuilderCodec.builder(
            AllowAllRoles.class, AllowAllRoles::new, ALLOWED_ROLES_BASE_CODEC
        )
        .build();

    private static final BuilderCodec<AllowlistRoles> ALLOWLIST_ROLES_CODEC = BuilderCodec.builder(
            AllowlistRoles.class, AllowlistRoles::new, ALLOWED_ROLES_BASE_CODEC
        )
        .<String[]>append(
            new KeyedCodec<>("Allowlist", NPC_ROLE_ARRAY_CODEC),
            (roles, value) -> roles.allowlist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            roles -> roles.allowlist
        )
        .documentation("Role IDs that are allowed.")
        .add()
        .build();

    private static final BuilderCodec<DenylistRoles> DENYLIST_ROLES_CODEC = BuilderCodec.builder(
            DenylistRoles.class, DenylistRoles::new, ALLOWED_ROLES_BASE_CODEC
        )
        .<String[]>append(
            new KeyedCodec<>("Denylist", NPC_ROLE_ARRAY_CODEC),
            (roles, value) -> roles.denylist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            roles -> roles.denylist
        )
        .documentation("Role IDs that are denied.")
        .add()
        .build();

    public static final StringCodecMapCodec<AllowedRoles, BuilderCodec<? extends AllowedRoles>> ALLOWED_ROLES_CODEC =
            new StringCodecMapCodec<>("Mode") { };

    static {
        ALLOWED_ROLES_CODEC.register("AllowAll", AllowAllRoles.class, ALLOW_ALL_ROLES_CODEC);
        ALLOWED_ROLES_CODEC.register("Allowlist", AllowlistRoles.class, ALLOWLIST_ROLES_CODEC);
        ALLOWED_ROLES_CODEC.register("Denylist", DenylistRoles.class, DENYLIST_ROLES_CODEC);
    }

    public static final BuilderCodec<ModeMapping> MODE_MAPPING_CODEC = BuilderCodec.builder(
            ModeMapping.class, ModeMapping::new
        )
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (mapping, value) -> mapping.state = value,
            mapping -> mapping.state
        )
        .documentation("State value to apply for this step.")
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (mapping, value) -> mapping.subState = value,
            mapping -> mapping.subState
        )
        .documentation("Sub-state value to apply for this step.")
        .add()
        .<String>append(
            new KeyedCodec<>("Message", Codec.STRING),
            (mapping, value) -> mapping.message = value,
            mapping -> mapping.message
        )
        .documentation("Message text shown when this step executes.")
        .add()
        .build();

    private static final BuilderCodec<CommandStep> COMMAND_STEP_BASE_CODEC = BuilderCodec.abstractBuilder(
            CommandStep.class
        )
        .<String>append(
            new KeyedCodec<>("FailurePolicy", Codec.STRING),
            (step, value) -> step.failurePolicy = FailurePolicy.fromString(value),
            step -> step.failurePolicy.name()
        )
        .documentation("Failure handling policy for this step.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Optional", Codec.BOOLEAN),
            (step, value) -> step.optional = value != null && value,
            step -> step.optional
        )
        .documentation("When true, this step can fail without affecting command flow.")
        .add()
        .build();

    private static final BuilderCodec<SetStateStep> SET_STATE_STEP_CODEC = BuilderCodec.builder(
            SetStateStep.class, SetStateStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (step, value) -> step.state = value,
            step -> step.state
        )
        .documentation("State value to apply for this step.")
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (step, value) -> step.subState = value,
            step -> step.subState
        )
        .documentation("Sub-state value to apply for this step.")
        .add()
        .build();

    private static final BuilderCodec<SetTargetStep> SET_TARGET_STEP_CODEC = BuilderCodec.builder(
            SetTargetStep.class, SetTargetStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("TargetSlot", Codec.STRING),
            (step, value) -> step.targetSlot = value,
            step -> step.targetSlot
        )
        .documentation("Target slot index used by this step.")
        .add()
        .<String>append(
            new KeyedCodec<>("Source", Codec.STRING),
            (step, value) -> step.source = TargetSource.fromString(value),
            step -> step.source.name()
        )
        .documentation("Source channel used by this operation.")
        .add()
        .build();

    private static final BuilderCodec<ClearTargetStep> CLEAR_TARGET_STEP_CODEC = BuilderCodec.builder(
            ClearTargetStep.class, ClearTargetStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("TargetSlot", Codec.STRING),
            (step, value) -> step.targetSlot = value,
            step -> step.targetSlot
        )
        .documentation("Target slot index used by this step.")
        .add()
        .build();

    private static final BuilderCodec<ClearCombatStep> CLEAR_COMBAT_STEP_CODEC = BuilderCodec.builder(
            ClearCombatStep.class, ClearCombatStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (step, value) -> step.state = value,
            step -> step.state
        )
        .documentation("State value to apply for this step.")
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (step, value) -> step.subState = value,
            step -> step.subState
        )
        .documentation("Sub-state value to apply for this step.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("TargetSlots", Codec.STRING_ARRAY),
            (step, value) -> step.targetSlots = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            step -> step.targetSlots
        )
        .documentation("Target slot indexes used by this step.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("AssignOwnerAsMasterTarget", Codec.BOOLEAN),
            (step, value) -> step.assignOwnerAsMasterTarget = value == null || value,
            step -> step.assignOwnerAsMasterTarget
        )
        .documentation("Assigns the owner as master target when executing this step.")
        .add()
        .build();

    private static final BuilderCodec<MoveToPositionStep> MOVE_TO_POSITION_STEP_CODEC = BuilderCodec.builder(
            MoveToPositionStep.class, MoveToPositionStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("Source", Codec.STRING),
            (step, value) -> step.source = MoveSource.fromString(value),
            step -> step.source.name()
        )
        .documentation("Source channel used by this operation.")
        .add()
        .build();

    private static final BuilderCodec<StoreHomeStep> STORE_HOME_STEP_CODEC = BuilderCodec.builder(
            StoreHomeStep.class, StoreHomeStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("Source", Codec.STRING),
            (step, value) -> step.source = StoreSource.fromString(value),
            step -> step.source.name()
        )
        .documentation("Source channel used by this operation.")
        .add()
        .build();

    private static final BuilderCodec<TriggerHookStep> TRIGGER_HOOK_STEP_CODEC = BuilderCodec.builder(
            TriggerHookStep.class, TriggerHookStep::new, COMMAND_STEP_BASE_CODEC
        )
        .<String>append(
            new KeyedCodec<>("HookId", Codec.STRING),
            (step, value) -> step.hookId = value,
            step -> step.hookId
        )
        .documentation("Hook identifier invoked by this step.")
        .add()
        .<Map<String, String>>append(
            new KeyedCodec<>("Payload", MapCodec.STRING_HASH_MAP_CODEC),
            (step, value) -> step.payload = value == null ? Collections.emptyMap() : value,
            step -> step.payload
        )
        .documentation("Custom payload value passed into this operation.")
        .add()
        .build();

    public static final StringCodecMapCodec<CommandStep, BuilderCodec<? extends CommandStep>> COMMAND_STEP_CODEC =
            new StringCodecMapCodec<>("Type") { };

    static {
        COMMAND_STEP_CODEC.register("SetState", SetStateStep.class, SET_STATE_STEP_CODEC);
        COMMAND_STEP_CODEC.register("SetTarget", SetTargetStep.class, SET_TARGET_STEP_CODEC);
        COMMAND_STEP_CODEC.register("ClearTarget", ClearTargetStep.class, CLEAR_TARGET_STEP_CODEC);
        COMMAND_STEP_CODEC.register("ClearCombat", ClearCombatStep.class, CLEAR_COMBAT_STEP_CODEC);
        COMMAND_STEP_CODEC.register("MoveToPosition", MoveToPositionStep.class, MOVE_TO_POSITION_STEP_CODEC);
        COMMAND_STEP_CODEC.register("StoreHome", StoreHomeStep.class, STORE_HOME_STEP_CODEC);
        COMMAND_STEP_CODEC.register("TriggerHook", TriggerHookStep.class, TRIGGER_HOOK_STEP_CODEC);
    }

    public static final ArrayCodec<CommandStep> COMMAND_STEP_ARRAY_CODEC =
            new ArrayCodec<>(COMMAND_STEP_CODEC, CommandStep[]::new);

    private static final CommandEntry[] EMPTY_COMMAND_LIST = new CommandEntry[0];
    private static final CommandStep[] EMPTY_STEPS = new CommandStep[0];

    public static final BuilderCodec<CommandFeedback> COMMAND_FEEDBACK_CODEC = BuilderCodec.builder(
            CommandFeedback.class, CommandFeedback::new
        )
        .<String>append(
            new KeyedCodec<>("ChatMessage", Codec.STRING),
            (feedback, value) -> feedback.chatMessage = value,
            feedback -> feedback.chatMessage
        )
        .documentation("Chat message sent when this step executes.")
        .add()
        .<String>append(
            new KeyedCodec<>("HudMessage", Codec.STRING),
            (feedback, value) -> feedback.hudMessage = value,
            feedback -> feedback.hudMessage
        )
        .documentation("HUD message shown when this step executes.")
        .add()
        .<String>append(
            new KeyedCodec<>("SoundEvent", SOUND_EVENT_CODEC),
            (feedback, value) -> feedback.soundEvent = value,
            feedback -> feedback.soundEvent
        )
        .documentation("Sound event played when this step executes.")
        .add()
        .<String>append(
            new KeyedCodec<>("ParticleSystem", PARTICLE_SYSTEM_CODEC),
            (feedback, value) -> feedback.particleSystem = value,
            feedback -> feedback.particleSystem
        )
        .documentation("Particle system spawned when this step executes.")
        .add()
        .<Vector3d>append(
            new KeyedCodec<>("ParticleOffset", VECTOR3D_CODEC),
            (feedback, value) -> feedback.particleOffset = value,
            feedback -> feedback.particleOffset
        )
        .documentation("Offset applied to spawned particle effects.")
        .add()
        .build();

    public static final BuilderCodec<CommandEntry> COMMAND_ENTRY_CODEC = BuilderCodec.builder(
            CommandEntry.class, CommandEntry::new
        )
        .<String>append(
            new KeyedCodec<>("Id", Codec.STRING),
            (entry, value) -> entry.id = value,
            entry -> entry.id
        )
        .documentation("Unique identifier for this entry.")
        .add()
        .<String>append(
            new KeyedCodec<>("DisplayName", Codec.STRING),
            (entry, value) -> entry.displayName = value,
            entry -> entry.displayName
        )
        .documentation("Display name shown to players.")
        .add()
        .<String>append(
            new KeyedCodec<>("Icon", Codec.STRING),
            (entry, value) -> entry.icon = value,
            entry -> entry.icon
        )
        .documentation("Icon asset path shown for this entry.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Default", Codec.BOOLEAN),
            (entry, value) -> entry.defaultCommand = value != null && value,
            entry -> entry.defaultCommand
        )
        .documentation("Default setting when no override is set.")
        .add()
        .<CommandFeedback>append(
            new KeyedCodec<>("Feedback", COMMAND_FEEDBACK_CODEC),
            (entry, value) -> entry.feedback = value,
            entry -> entry.feedback
        )
        .documentation("User feedback settings for this command interaction.")
        .add()
        .<ModeMapping>append(
            new KeyedCodec<>("ModeMapping", MODE_MAPPING_CODEC),
            (entry, value) -> entry.modeMapping = value,
            entry -> entry.modeMapping
        )
        .documentation("Maps command modes to behavior modes.")
        .add()
        .<CommandStep[]>append(
            new KeyedCodec<>("Steps", COMMAND_STEP_ARRAY_CODEC),
            (entry, value) -> entry.steps = value == null ? EMPTY_STEPS : value,
            entry -> entry.steps
        )
        .documentation("Ordered list of command step definitions.")
        .add()
        .build();

    public static final ArrayCodec<CommandEntry> COMMAND_ENTRY_ARRAY_CODEC =
            new ArrayCodec<>(COMMAND_ENTRY_CODEC, CommandEntry[]::new);

    public static final AssetBuilderCodec<String, TwCommandItemConfig> CODEC =
        AssetBuilderCodec.builder(
                TwCommandItemConfig.class,
                TwCommandItemConfig::new,
                Codec.STRING,
                (asset, id) -> asset.id = id,
                asset -> asset.id,
                (asset, data) -> asset.data = data,
                asset -> asset.data
        )
        .documentation("Command item configuration for Alec's Tamework!")
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (asset, value) -> asset.enabled = value == null || value,
            asset -> asset.enabled
        )
        .documentation("Turns this section on or off.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("ItemIds", Codec.STRING_ARRAY),
            (asset, value) -> asset.itemIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            asset -> asset.itemIds
        )
        .documentation("Command item IDs this config applies to. Inheritance: omitted value inherits from parent; "
                + "explicit array replaces parent value (no merge).")
        .add()
        .<Double>append(
            new KeyedCodec<>("Radius", Codec.DOUBLE),
            (asset, value) -> asset.radius = value == null ? -1.0 : value,
            asset -> asset.radius
        )
        .documentation("Search radius in blocks used by this system.")
        .add()
        .<String>append(
            new KeyedCodec<>("MembershipMode", Codec.STRING),
            (asset, value) -> asset.membershipMode = MembershipMode.fromString(value),
            asset -> asset.membershipMode.name()
        )
        .documentation("Controls how command membership is interpreted when selecting targets.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("LinkEnabled", Codec.BOOLEAN),
            (asset, value) -> asset.linkEnabled = value == null || value,
            asset -> asset.linkEnabled
        )
        .documentation("If true, this command can create and use persistent links.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("LinkUseTogglesMembership", Codec.BOOLEAN),
            (asset, value) -> asset.linkUseTogglesMembership = value == null || value,
            asset -> asset.linkUseTogglesMembership
        )
        .documentation("If true, using link mode toggles membership for selected targets.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (asset, value) -> asset.requireTamed = value == null || value,
            asset -> asset.requireTamed
        )
        .documentation("Requires the target NPC to be tamed.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (asset, value) -> asset.requireOwner = value,
            asset -> asset.requireOwner
        )
        .documentation("Optional owner requirement override. If omitted, global OwnershipRequirements.LinkingRequiresOwner applies.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxTargets", Codec.INTEGER),
            (asset, value) -> asset.maxTargets = value == null ? 25 : Math.max(1, value),
            asset -> asset.maxTargets
        )
        .documentation("Maximum number of targets this command can affect at once.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxActive", Codec.INTEGER),
            (asset, value) -> asset.maxActive = value == null ? 0 : Math.max(0, value),
            asset -> asset.maxActive
        )
        .documentation("Maximum number of active linked NPCs allowed for this command item.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownSeconds", Codec.INTEGER),
            (asset, value) -> asset.cooldownSeconds = value == null ? 2 : Math.max(0, value),
            asset -> asset.cooldownSeconds
        )
        .documentation("Cooldown duration in seconds before this can be used again.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireLineOfSight", Codec.BOOLEAN),
            (asset, value) -> asset.requireLineOfSight = value != null && value,
            asset -> asset.requireLineOfSight
        )
        .documentation("Requires line of sight to target before command can run.")
        .add()
        .<AllowedRoles>append(
            new KeyedCodec<>("AllowedRoles", ALLOWED_ROLES_CODEC),
            (asset, value) -> asset.allowedRoles = value == null ? new AllowAllRoles() : value,
            asset -> asset.allowedRoles
        )
        .documentation("Role restrictions for command targets. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<CommandEntry[]>append(
            new KeyedCodec<>("CommandList", COMMAND_ENTRY_ARRAY_CODEC),
            (asset, value) -> asset.commandList = value == null ? EMPTY_COMMAND_LIST : value,
            asset -> asset.commandList
        )
        .documentation("Available command entries. Inheritance: omitted value inherits from parent; explicit array "
                + "replaces parent value (no merge).")
        .add()
        .build();

    private static AssetStore<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private String[] itemIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private double radius = -1.0;
    private MembershipMode membershipMode = MembershipMode.LinkedOnly;
    private boolean linkEnabled = true;
    private boolean linkUseTogglesMembership = true;
    private boolean requireTamed = true;
    private Boolean requireOwner;
    private int maxTargets = 25;
    private int maxActive;
    private int cooldownSeconds = 2;
    private boolean requireLineOfSight;
    private AllowedRoles allowedRoles = new AllowAllRoles();
    private CommandEntry[] commandList = EMPTY_COMMAND_LIST;

    public static AssetStore<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwCommandItemConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCommandItemConfig> getAssetMap() {
        AssetStore<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwCommandItemConfig> assetMap =
                (DefaultAssetMap<String, TwCommandItemConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwCommandItemConfig> assetMap) {
        if (!INHERITANCE_CACHE_DIRTY || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || assetMap.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    protected TwCommandItemConfig() {
    }

    public String getId() {
        return id;
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCommandItemConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCommandItemConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("ItemIds")) itemIds = parent.itemIds;
        if (!explicitTopLevelKeys.contains("Radius")) radius = parent.radius;
        if (!explicitTopLevelKeys.contains("MembershipMode")) membershipMode = parent.membershipMode;
        if (!explicitTopLevelKeys.contains("LinkEnabled")) linkEnabled = parent.linkEnabled;
        if (!explicitTopLevelKeys.contains("LinkUseTogglesMembership")) {
            linkUseTogglesMembership = parent.linkUseTogglesMembership;
        }
        if (!explicitTopLevelKeys.contains("RequireTamed")) requireTamed = parent.requireTamed;
        if (!explicitTopLevelKeys.contains("RequireOwner")) requireOwner = parent.requireOwner;
        if (!explicitTopLevelKeys.contains("MaxTargets")) maxTargets = parent.maxTargets;
        if (!explicitTopLevelKeys.contains("MaxActive")) maxActive = parent.maxActive;
        if (!explicitTopLevelKeys.contains("CooldownSeconds")) cooldownSeconds = parent.cooldownSeconds;
        if (!explicitTopLevelKeys.contains("RequireLineOfSight")) requireLineOfSight = parent.requireLineOfSight;
        if (!explicitTopLevelKeys.contains("AllowedRoles")) {
            allowedRoles = parent.allowedRoles;
        } else {
            inheritAllowedRolesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "AllowedRoles"));
        }
        if (!explicitTopLevelKeys.contains("CommandList")) commandList = parent.commandList;
    }

    private void inheritAllowedRolesSection(@Nonnull TwCommandItemConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Mode")) {
            allowedRoles = parent.allowedRoles;
            return;
        }
        if (allowedRoles == null || parent.allowedRoles == null) {
            return;
        }
        if (allowedRoles instanceof AllowlistRoles childAllowlist && parent.allowedRoles instanceof AllowlistRoles parentAllowlist) {
            if (!nestedExplicitKeys.contains("Allowlist")) {
                childAllowlist.allowlist = parentAllowlist.allowlist;
            }
        } else if (allowedRoles instanceof DenylistRoles childDenylist
                && parent.allowedRoles instanceof DenylistRoles parentDenylist) {
            if (!nestedExplicitKeys.contains("Denylist")) {
                childDenylist.denylist = parentDenylist.denylist;
            }
        }
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String[] getItemIds() {
        return itemIds;
    }

    public double getRadius() {
        return radius;
    }

    public MembershipMode getMembershipMode() {
        return membershipMode;
    }

    public boolean isLinkEnabled() {
        return linkEnabled;
    }

    public boolean isLinkUseTogglesMembership() {
        return linkUseTogglesMembership;
    }

    public boolean isRequireTamed() {
        return requireTamed;
    }

    public boolean isRequireOwner() {
        return requireOwner == null || requireOwner;
    }

    @Nullable
    public Boolean getRequireOwnerOverride() {
        return requireOwner;
    }

    public boolean resolveRequireOwner(boolean fallbackValue) {
        return requireOwner == null ? fallbackValue : requireOwner;
    }

    public int getMaxTargets() {
        return maxTargets;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean isRequireLineOfSight() {
        return requireLineOfSight;
    }

    public AllowedRoles getAllowedRoles() {
        return allowedRoles;
    }

    public CommandEntry[] getCommandList() {
        return commandList;
    }

    /**
     * Builds the built-in Return Home command used by panel actions when an item config does not
     * define an explicit return-home command entry.
     */
    public static CommandEntry createBuiltInReturnHomeCommand() {
        MoveToPositionStep step = new MoveToPositionStep();
        step.source = MoveSource.StoredHome;

        CommandEntry entry = new CommandEntry();
        entry.id = "ReturnHome";
        entry.displayName = "Return Home";
        entry.steps = new CommandStep[] { step };
        return entry;
    }

    @Nullable
    public CommandEntry findCommandById(@Nullable String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return null;
        }
        CommandEntry[] commands = commandList;
        if (commands == null || commands.length == 0) {
            return null;
        }
        for (CommandEntry entry : commands) {
            if (entry == null || entry.id == null) {
                continue;
            }
            if (commandIdEquals(entry.id, commandId)) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public CommandEntry findDefaultCommand() {
        CommandEntry[] commands = commandList;
        if (commands == null || commands.length == 0) {
            return null;
        }
        for (CommandEntry entry : commands) {
            if (entry != null && entry.defaultCommand) {
                return entry;
            }
        }
        for (CommandEntry entry : commands) {
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public CommandEntry findNextCommand(@Nullable String currentCommandId) {
        CommandEntry[] commands = commandList;
        if (commands == null || commands.length == 0) {
            return null;
        }
        int firstValidIndex = -1;
        int currentIndex = -1;
        for (int i = 0; i < commands.length; i++) {
            CommandEntry entry = commands[i];
            if (entry == null || entry.id == null || entry.id.isBlank()) {
                continue;
            }
            if (firstValidIndex < 0) {
                firstValidIndex = i;
            }
            if (commandIdEquals(entry.id, currentCommandId)) {
                currentIndex = i;
            }
        }
        if (firstValidIndex < 0) {
            return null;
        }
        if (currentIndex < 0) {
            return commands[firstValidIndex];
        }
        for (int offset = 1; offset <= commands.length; offset++) {
            int index = (currentIndex + offset) % commands.length;
            CommandEntry entry = commands[index];
            if (entry != null && entry.id != null && !entry.id.isBlank()) {
                return entry;
            }
        }
        return commands[currentIndex];
    }

    private boolean commandIdEquals(@Nullable String left, @Nullable String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.trim().toLowerCase(Locale.ROOT).equals(right.trim().toLowerCase(Locale.ROOT));
    }

    /** Base role filter model for command targets. */
    public abstract static class AllowedRoles {
        public abstract RoleFilterMode getMode();

        public String[] getAllowlist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public String[] getDenylist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
    }

    /** Allow command dispatch to all NPC roles. */
    public static final class AllowAllRoles extends AllowedRoles {
        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.AllowAll;
        }
    }

    /** Allow command dispatch only for explicitly listed NPC roles. */
    public static final class AllowlistRoles extends AllowedRoles {
        private String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;

        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.Allowlist;
        }

        @Override
        public String[] getAllowlist() {
            return allowlist;
        }
    }

    /** Deny command dispatch for explicitly listed NPC roles. */
    public static final class DenylistRoles extends AllowedRoles {
        private String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;

        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.Denylist;
        }

        @Override
        public String[] getDenylist() {
            return denylist;
        }
    }

    public static final class ModeMapping {
        private String state;
        private String subState;
        private String message;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class CommandEntry {
        private String id;
        private String displayName;
        private String icon;
        private boolean defaultCommand;
        private CommandFeedback feedback;
        private ModeMapping modeMapping;
        private CommandStep[] steps = EMPTY_STEPS;

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getIcon() {
            return icon;
        }

        public boolean isDefaultCommand() {
            return defaultCommand;
        }

        public CommandFeedback getFeedback() {
            return feedback;
        }

        public ModeMapping getModeMapping() {
            return modeMapping;
        }

        public CommandStep[] getSteps() {
            return steps;
        }
    }

    public abstract static class CommandStep {
        private FailurePolicy failurePolicy = FailurePolicy.Continue;
        private boolean optional;

        public FailurePolicy getFailurePolicy() {
            return failurePolicy;
        }

        public boolean isOptional() {
            return optional;
        }
    }

    public static final class SetStateStep extends CommandStep {
        private String state;
        private String subState;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }
    }

    public static final class SetTargetStep extends CommandStep {
        private String targetSlot = "MasterTarget";
        private TargetSource source = TargetSource.CrosshairTarget;

        public String getTargetSlot() {
            return targetSlot;
        }

        public TargetSource getSource() {
            return source;
        }
    }

    public static final class ClearTargetStep extends CommandStep {
        private String targetSlot = "MasterTarget";

        public String getTargetSlot() {
            return targetSlot;
        }
    }

    public static final class ClearCombatStep extends CommandStep {
        private String state = "Idle";
        private String subState;
        private String[] targetSlots = new String[] { "LockedTarget" };
        private boolean assignOwnerAsMasterTarget = true;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }

        public String[] getTargetSlots() {
            return targetSlots;
        }

        public boolean isAssignOwnerAsMasterTarget() {
            return assignOwnerAsMasterTarget;
        }
    }

    public static final class MoveToPositionStep extends CommandStep {
        private MoveSource source = MoveSource.RaycastHit;

        public MoveSource getSource() {
            return source;
        }
    }

    public static final class StoreHomeStep extends CommandStep {
        private StoreSource source = StoreSource.RaycastHit;

        public StoreSource getSource() {
            return source;
        }
    }

    public static final class TriggerHookStep extends CommandStep {
        private String hookId;
        private Map<String, String> payload = Collections.emptyMap();

        public String getHookId() {
            return hookId;
        }

        public Map<String, String> getPayload() {
            return payload;
        }
    }

    public static final class CommandFeedback {
        private String chatMessage;
        private String hudMessage;
        private String soundEvent;
        private String particleSystem;
        private Vector3d particleOffset;

        public String getChatMessage() {
            return chatMessage;
        }

        public String getHudMessage() {
            return hudMessage;
        }

        public String getSoundEvent() {
            return soundEvent;
        }

        public String getParticleSystem() {
            return particleSystem;
        }

        public Vector3d getParticleOffset() {
            return particleOffset;
        }
    }
}


