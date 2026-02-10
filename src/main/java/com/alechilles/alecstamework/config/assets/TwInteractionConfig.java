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
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Asset-backed configuration for optimized interaction rules.
 * Stored under Server/Tamework/Interactions.
 */
public class TwInteractionConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwInteractionConfig>> {
    public enum InteractionType {
        Taming,
        Feeding,
        Breeding,
        Mounting,
        Harvesting,
        ModeToggle
    }

    public enum RequirementType {
        LovedItems,
        Items,
        IsHarvestable,
        IsMountable,
        HarvestAlarmReady,
        HarvestInteractionContext,
        CustomParamEquals,
        AlarmState,
        NpcState,
        PlayerMovementState,
        PlayerIsOwner,
        NpcIsTamed,
        NpcNotTamed,
        NpcIsAdult,
        CooldownReady,
        HappinessAtLeast
    }

    public enum RequirementSource {
        RoleParam,
        Inline
    }

    private static final EnumCodec<InteractionType> INTERACTION_TYPE_CODEC =
            new EnumCodec<>(InteractionType.class);
    private static final EnumCodec<RequirementType> REQUIREMENT_TYPE_CODEC =
            new EnumCodec<>(RequirementType.class);
    private static final EnumCodec<RequirementSource> REQUIREMENT_SOURCE_CODEC =
            new EnumCodec<>(RequirementSource.class);

    private static final Requirement[] EMPTY_REQUIREMENTS = new Requirement[0];
    private static final InteractionEntry[] EMPTY_INTERACTIONS = new InteractionEntry[0];

    public static final BuilderCodec<Requirement> REQUIREMENT_CODEC = BuilderCodec.builder(
            Requirement.class,
            Requirement::new
    )
        .<RequirementType>append(
            new KeyedCodec<>("Type", REQUIREMENT_TYPE_CODEC),
            (requirement, value) -> requirement.type = value,
            requirement -> requirement.type
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Items", Codec.STRING_ARRAY),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .add()
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (requirement, value) -> requirement.state = value,
            requirement -> requirement.state
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Context", Codec.STRING),
            (requirement, value) -> requirement.context = value,
            requirement -> requirement.context
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Param", Codec.STRING),
            (requirement, value) -> requirement.param = value,
            requirement -> requirement.param
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Name", Codec.STRING),
            (requirement, value) -> requirement.name = value,
            requirement -> requirement.name
        )
        .add()
        .<RequirementSource>append(
            new KeyedCodec<>("Source", REQUIREMENT_SOURCE_CODEC),
            (requirement, value) -> {
                if (value != null) {
                    requirement.source = value;
                }
            },
            requirement -> requirement.source
        )
        .add()
        .build();

    public static final ArrayCodec<Requirement> REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(REQUIREMENT_CODEC, Requirement[]::new);

    public static final BuilderCodec<RequirementGroup> REQUIREMENT_GROUP_CODEC = BuilderCodec.builder(
            RequirementGroup.class,
            RequirementGroup::new
    )
        .<Requirement[]>append(
            new KeyedCodec<>("All", REQUIREMENT_ARRAY_CODEC),
            (group, value) -> group.all = value == null ? EMPTY_REQUIREMENTS : value,
            group -> group.all
        )
        .add()
        .<Requirement[]>append(
            new KeyedCodec<>("Any", REQUIREMENT_ARRAY_CODEC),
            (group, value) -> group.any = value == null ? EMPTY_REQUIREMENTS : value,
            group -> group.any
        )
        .add()
        .build();

    public static final BuilderCodec<Effects> EFFECTS_CODEC = BuilderCodec.builder(
            Effects.class,
            Effects::new
    )
        .<Boolean>append(
            new KeyedCodec<>("StartTaming", Codec.BOOLEAN),
            (effects, value) -> effects.startTaming = value,
            effects -> effects.startTaming
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ApplyFeeding", Codec.BOOLEAN),
            (effects, value) -> effects.applyFeeding = value,
            effects -> effects.applyFeeding
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("StartHarvest", Codec.BOOLEAN),
            (effects, value) -> effects.startHarvest = value,
            effects -> effects.startHarvest
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Mount", Codec.BOOLEAN),
            (effects, value) -> effects.mount = value,
            effects -> effects.mount
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ToggleMode", Codec.BOOLEAN),
            (effects, value) -> effects.toggleMode = value,
            effects -> effects.toggleMode
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ConsumeItem", Codec.BOOLEAN),
            (effects, value) -> effects.consumeItem = value,
            effects -> effects.consumeItem
        )
        .add()
        .<String>append(
            new KeyedCodec<>("PlaySound", Codec.STRING),
            (effects, value) -> effects.playSound = value,
            effects -> effects.playSound
        )
        .add()
        .<String>append(
            new KeyedCodec<>("SpawnParticles", Codec.STRING),
            (effects, value) -> effects.spawnParticles = value,
            effects -> effects.spawnParticles
        )
        .add()
        .<String>append(
            new KeyedCodec<>("DropItem", Codec.STRING),
            (effects, value) -> effects.dropItem = value,
            effects -> effects.dropItem
        )
        .add()
        .build();

    public static final BuilderCodec<InteractionEntry> INTERACTION_CODEC = BuilderCodec.builder(
            InteractionEntry.class,
            InteractionEntry::new
    )
        .<InteractionType>append(
            new KeyedCodec<>("Type", INTERACTION_TYPE_CODEC),
            (entry, value) -> entry.type = value,
            entry -> entry.type
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (entry, value) -> {
                if (value != null) {
                    entry.enabled = value;
                }
            },
            entry -> entry.enabled
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("UseDefaults", Codec.BOOLEAN),
            (entry, value) -> entry.useDefaults = value,
            entry -> entry.useDefaults
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownSeconds", Codec.INTEGER),
            (entry, value) -> entry.cooldownSeconds = value,
            entry -> entry.cooldownSeconds
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final ArrayCodec<InteractionEntry> INTERACTION_ARRAY_CODEC =
            new ArrayCodec<>(INTERACTION_CODEC, InteractionEntry[]::new);

    public static final BuilderCodec<Cooldowns> COOLDOWNS_CODEC = BuilderCodec.builder(
            Cooldowns.class,
            Cooldowns::new
    )
        .<Integer>append(
            new KeyedCodec<>("InteractionSeconds", Codec.INTEGER),
            (cooldowns, value) -> cooldowns.interactionSeconds = value,
            cooldowns -> cooldowns.interactionSeconds
        )
        .add()
        .build();

    public static final BuilderCodec<Defaults> DEFAULTS_CODEC = BuilderCodec.builder(
            Defaults.class,
            Defaults::new
    ).build();

    public static final AssetBuilderCodec<String, TwInteractionConfig> CODEC =
        AssetBuilderCodec.builder(
                TwInteractionConfig.class,
                TwInteractionConfig::new,
                Codec.STRING,
                (asset, id) -> asset.id = id,
                asset -> asset.id,
                (asset, data) -> asset.data = data,
                asset -> asset.data
        )
        .documentation("Interaction configuration for Alec's Tamework!")
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (asset, value) -> asset.enabled = value == null || value,
            asset -> asset.enabled
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
            (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            asset -> asset.roleIds
        )
        .add()
        .<Defaults>append(
            new KeyedCodec<>("Defaults", DEFAULTS_CODEC),
            (asset, value) -> asset.defaults = value == null ? new Defaults() : value,
            asset -> asset.defaults
        )
        .add()
        .<InteractionEntry[]>append(
            new KeyedCodec<>("Interactions", INTERACTION_ARRAY_CODEC),
            (asset, value) -> asset.interactions = value == null ? EMPTY_INTERACTIONS : value,
            asset -> asset.interactions
        )
        .add()
        .<Cooldowns>append(
            new KeyedCodec<>("Cooldowns", COOLDOWNS_CODEC),
            (asset, value) -> asset.cooldowns = value == null ? new Cooldowns() : value,
            asset -> asset.cooldowns
        )
        .add()
        .build();

    private static AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> ASSET_STORE;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private Defaults defaults = new Defaults();
    private InteractionEntry[] interactions = EMPTY_INTERACTIONS;
    private Cooldowns cooldowns = new Cooldowns();

    public static AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwInteractionConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwInteractionConfig> getAssetMap() {
        AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, TwInteractionConfig>) store.getAssetMap();
    }

    protected TwInteractionConfig() {
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String[] getRoleIds() {
        return roleIds;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public InteractionEntry[] getInteractions() {
        return interactions == null ? EMPTY_INTERACTIONS : interactions;
    }

    public Cooldowns getCooldowns() {
        return cooldowns;
    }

    public boolean matchesRole(String roleId) {
        if (roleId == null || roleIds == null || roleIds.length == 0) {
            return false;
        }
        return Arrays.stream(roleIds).anyMatch(roleId::equalsIgnoreCase);
    }

    public static final class Defaults {
    }

    public static final class Cooldowns {
        private Integer interactionSeconds;

        public Integer getInteractionSeconds() {
            return interactionSeconds;
        }
    }

    public static final class InteractionEntry {
        private InteractionType type;
        private boolean enabled = true;
        private Boolean useDefaults;
        private RequirementGroup requires;
        private Integer cooldownSeconds;
        private Effects effects;

        public InteractionType getType() {
            return type;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean useDefaults() {
            return useDefaults == null || useDefaults;
        }

        public RequirementGroup getRequires() {
            return requires;
        }

        public Integer getCooldownSeconds() {
            return cooldownSeconds;
        }

        public Effects getEffects() {
            return effects;
        }
    }

    public static final class RequirementGroup {
        private Requirement[] all = EMPTY_REQUIREMENTS;
        private Requirement[] any = EMPTY_REQUIREMENTS;

        public Requirement[] getAll() {
            return all == null ? EMPTY_REQUIREMENTS : all;
        }

        public Requirement[] getAny() {
            return any == null ? EMPTY_REQUIREMENTS : any;
        }
    }

    public static final class Requirement {
        private RequirementType type;
        private String[] items = ArrayUtil.EMPTY_STRING_ARRAY;
        private String state;
        private String context;
        private String param;
        private String name;
        private RequirementSource source = RequirementSource.RoleParam;

        public RequirementType getType() {
            return type;
        }

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }

        public String getState() {
            return state;
        }

        public String getContext() {
            return context;
        }

        public String getParam() {
            return param;
        }

        public String getName() {
            return name;
        }

        public RequirementSource getSource() {
            return source == null ? RequirementSource.RoleParam : source;
        }
    }

    public static final class Effects {
        private Boolean startTaming;
        private Boolean applyFeeding;
        private Boolean startHarvest;
        private Boolean mount;
        private Boolean toggleMode;
        private Boolean consumeItem;
        private String playSound;
        private String spawnParticles;
        private String dropItem;

        public Boolean getStartTaming() {
            return startTaming;
        }

        public Boolean getApplyFeeding() {
            return applyFeeding;
        }

        public Boolean getStartHarvest() {
            return startHarvest;
        }

        public Boolean getMount() {
            return mount;
        }

        public Boolean getToggleMode() {
            return toggleMode;
        }

        public Boolean getConsumeItem() {
            return consumeItem;
        }

        public String getPlaySound() {
            return playSound;
        }

        public String getSpawnParticles() {
            return spawnParticles;
        }

        public String getDropItem() {
            return dropItem;
        }
    }
}
