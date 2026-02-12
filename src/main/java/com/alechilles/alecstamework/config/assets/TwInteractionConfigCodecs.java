package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.exception.CodecException;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.math.codec.Vector3dArrayCodec;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.codec.protocol.ColorCodec;
import java.util.ArrayList;
import javax.annotation.Nonnull;
import org.bson.BsonNull;
import org.bson.BsonValue;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.*;

public final class TwInteractionConfigCodecs {
    private TwInteractionConfigCodecs() {
    }

    private static final EnumCodec<ParamOperator> PARAM_OPERATOR_CODEC =
            new EnumCodec<>(ParamOperator.class);
    private static final EnumCodec<MatchType> MATCH_TYPE_CODEC =
            new EnumCodec<>(MatchType.class);
    private static final EnumCodec<OwnerSource> OWNER_SOURCE_CODEC =
            new EnumCodec<>(OwnerSource.class);

    private static final InteractionEntry[] EMPTY_INTERACTIONS = new InteractionEntry[0];
    private static final ModeStep[] EMPTY_MODE_CYCLE = new ModeStep[0];
    private static final FeedItem[] EMPTY_FEED_ITEMS = new FeedItem[0];
    private static final ItemsInHandRequirement[] EMPTY_ITEMS_IN_HAND_REQUIREMENTS = new ItemsInHandRequirement[0];
    private static final ItemsInInventoryRequirement[] EMPTY_ITEMS_IN_INVENTORY_REQUIREMENTS = new ItemsInInventoryRequirement[0];
    private static final ItemsEquippedRequirement[] EMPTY_ITEMS_EQUIPPED_REQUIREMENTS = new ItemsEquippedRequirement[0];
    private static final InteractionContextRequirement[] EMPTY_CONTEXT_REQUIREMENTS = new InteractionContextRequirement[0];
    private static final StringRequirement[] EMPTY_STRING_REQUIREMENTS = new StringRequirement[0];
    private static final MovementStateRequirement[] EMPTY_MOVEMENT_STATE_REQUIREMENTS = new MovementStateRequirement[0];
    private static final AlarmRequirement[] EMPTY_ALARM_REQUIREMENTS = new AlarmRequirement[0];
    private static final ParamRequirement[] EMPTY_PARAM_REQUIREMENTS = new ParamRequirement[0];
    private static final StatDelta[] EMPTY_STAT_DELTAS = new StatDelta[0];
    private static final ItemQuantity[] EMPTY_ITEM_QUANTITIES = new ItemQuantity[0];
    private static final String[] PLAYER_MOVEMENT_STATE_ENUM = new String[] {
            "Crouching",
            "Walking",
            "Running",
            "Sprinting",
            "Idle",
            "Mounting",
            "Sleeping"
    };
    private static final String[] EQUIPPED_SLOT_ENUM = new String[] {
            "Head",
            "Chest",
            "Hands",
            "Legs",
            "Armor",
            "Equipped",
            "Utility",
            "Accessory",
            "Accessories"
    };

    private static final Codec<String[]> STRING_ARRAY_OR_SINGLE_CODEC = new Codec<>() {
        @Override
        public String[] decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            if (Codec.isNullBsonValue(bsonValue)) {
                return ArrayUtil.EMPTY_STRING_ARRAY;
            }
            if (bsonValue.isArray()) {
                return Codec.STRING_ARRAY.decode(bsonValue, extraInfo);
            }
            if (bsonValue.isString()) {
                return new String[] { bsonValue.asString().getValue() };
            }
            throw new CodecException("Expected string or string array", bsonValue, extraInfo, null);
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
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItem(Codec.STRING.toSchema(context));
            return Schema.anyOf(Codec.STRING.toSchema(context), arraySchema);
        }
    };

    private static final Codec<String> PLAYER_MOVEMENT_STATE_CODEC = new Codec<>() {
        @Override
        public String decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            return Codec.STRING.decode(bsonValue, extraInfo);
        }

        @Override
        public BsonValue encode(String value, ExtraInfo extraInfo) {
            return Codec.STRING.encode(value, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            StringSchema schema = new StringSchema();
            schema.setEnum(PLAYER_MOVEMENT_STATE_ENUM);
            return schema;
        }
    };

    private static final Codec<String> ITEM_ASSET_CODEC = new Codec<>() {
        @Override
        public String decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            if (Codec.isNullBsonValue(bsonValue)) {
                return null;
            }
            if (bsonValue.isString()) {
                return bsonValue.asString().getValue();
            }
            throw new CodecException("Expected string", bsonValue, extraInfo, null);
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
            schema.setHytaleAssetRef("Item");
            return schema;
        }
    };

    private static final Codec<String[]> ITEM_ASSET_ARRAY_OR_SINGLE_CODEC = new Codec<>() {
        @Override
        public String[] decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            if (Codec.isNullBsonValue(bsonValue)) {
                return ArrayUtil.EMPTY_STRING_ARRAY;
            }
            if (bsonValue.isArray()) {
                return Codec.STRING_ARRAY.decode(bsonValue, extraInfo);
            }
            if (bsonValue.isString()) {
                return new String[] { bsonValue.asString().getValue() };
            }
            throw new CodecException("Expected string or string array", bsonValue, extraInfo, null);
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
            StringSchema itemSchema = new StringSchema();
            itemSchema.setHytaleAssetRef("Item");
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItem(itemSchema);
            return Schema.anyOf(itemSchema, arraySchema);
        }
    };

    private static Codec<String> assetRefCodec(String assetType) {
        return new Codec<>() {
            @Override
            public String decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
                if (Codec.isNullBsonValue(bsonValue)) {
                    return null;
                }
                if (bsonValue.isString()) {
                    return bsonValue.asString().getValue();
                }
                throw new CodecException("Expected string", bsonValue, extraInfo, null);
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
    private static final Codec<String> ITEM_DROP_LIST_CODEC = assetRefCodec("ItemDropList");

    private static final Codec<String[]> EQUIPPED_SLOT_ARRAY_OR_SINGLE_CODEC = new Codec<>() {
        @Override
        public String[] decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            if (Codec.isNullBsonValue(bsonValue)) {
                return ArrayUtil.EMPTY_STRING_ARRAY;
            }
            if (bsonValue.isArray()) {
                return Codec.STRING_ARRAY.decode(bsonValue, extraInfo);
            }
            if (bsonValue.isString()) {
                return new String[] { bsonValue.asString().getValue() };
            }
            throw new CodecException("Expected string or string array", bsonValue, extraInfo, null);
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
            StringSchema slotSchema = new StringSchema();
            slotSchema.setEnum(EQUIPPED_SLOT_ENUM);
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItem(slotSchema);
            return Schema.anyOf(slotSchema, arraySchema);
        }
    };

    private static final Codec<Vector3d> VECTOR3D_CODEC = new Vector3dArrayCodec();
    private static final Codec<Color> COLOR_CODEC = new ColorCodec();

    public static final BuilderCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_CODEC = BuilderCodec.builder(
            ItemsInHandRequirement.class,
            ItemsInHandRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("Param", Codec.STRING),
            (requirement, value) -> requirement.param = value,
            requirement -> requirement.param
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Items", ITEM_ASSET_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (requirement, value) -> requirement.quantity = value,
            requirement -> requirement.quantity
        )
        .add()
        .build();

    public static final ArrayCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ITEMS_IN_HAND_REQUIREMENT_CODEC, ItemsInHandRequirement[]::new);

    public static final BuilderCodec<ItemsInInventoryRequirement> ITEMS_IN_INVENTORY_REQUIREMENT_CODEC = BuilderCodec.builder(
            ItemsInInventoryRequirement.class,
            ItemsInInventoryRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("Param", Codec.STRING),
            (requirement, value) -> requirement.param = value,
            requirement -> requirement.param
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Items", ITEM_ASSET_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (requirement, value) -> requirement.quantity = value,
            requirement -> requirement.quantity
        )
        .add()
        .build();

    public static final ArrayCodec<ItemsInInventoryRequirement> ITEMS_IN_INVENTORY_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ITEMS_IN_INVENTORY_REQUIREMENT_CODEC, ItemsInInventoryRequirement[]::new);

    public static final BuilderCodec<ItemsEquippedRequirement> ITEMS_EQUIPPED_REQUIREMENT_CODEC = BuilderCodec.builder(
            ItemsEquippedRequirement.class,
            ItemsEquippedRequirement::new
    )
        .<String[]>append(
            new KeyedCodec<>("Items", ITEM_ASSET_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Slots", EQUIPPED_SLOT_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.slots = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.slots
        )
        .add()
        .build();

    public static final ArrayCodec<ItemsEquippedRequirement> ITEMS_EQUIPPED_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ITEMS_EQUIPPED_REQUIREMENT_CODEC, ItemsEquippedRequirement[]::new);

    public static final BuilderCodec<ParamRequirement> PARAM_REQUIREMENT_CODEC = BuilderCodec.builder(
            ParamRequirement.class,
            ParamRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("Name", Codec.STRING),
            (requirement, value) -> requirement.name = value,
            requirement -> requirement.name
        )
        .add()
        .<ParamOperator>append(
            new KeyedCodec<>("Operator", PARAM_OPERATOR_CODEC),
            (requirement, value) -> {
                if (value != null) {
                    requirement.operator = value;
                }
            },
            requirement -> requirement.operator
        )
        .add()
        .<MatchType>append(
            new KeyedCodec<>("Match", MATCH_TYPE_CODEC),
            (requirement, value) -> {
                if (value != null) {
                    requirement.match = value;
                }
            },
            requirement -> requirement.match
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Value", STRING_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.values = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.values
        )
        .add()
        .build();

    public static final ArrayCodec<ParamRequirement> PARAM_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(PARAM_REQUIREMENT_CODEC, ParamRequirement[]::new);

    public static final BuilderCodec<AlarmRequirement> ALARM_REQUIREMENT_CODEC = BuilderCodec.builder(
            AlarmRequirement.class,
            AlarmRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("Name", Codec.STRING),
            (requirement, value) -> requirement.name = value,
            requirement -> requirement.name
        )
        .add()
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (requirement, value) -> requirement.state = value,
            requirement -> requirement.state
        )
        .add()
        .build();

    public static final ArrayCodec<AlarmRequirement> ALARM_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ALARM_REQUIREMENT_CODEC, AlarmRequirement[]::new);

    public static final BuilderCodec<StringRequirement> STRING_REQUIREMENT_CODEC = BuilderCodec.builder(
            StringRequirement.class,
            StringRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (requirement, value) -> requirement.state = value,
            requirement -> requirement.state
        )
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (requirement, value) -> requirement.subState = value,
            requirement -> requirement.subState
        )
        .add()
        .build();

    public static final ArrayCodec<StringRequirement> STRING_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(STRING_REQUIREMENT_CODEC, StringRequirement[]::new);

    public static final BuilderCodec<MovementStateRequirement> MOVEMENT_STATE_REQUIREMENT_CODEC = BuilderCodec.builder(
            MovementStateRequirement.class,
            MovementStateRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("State", PLAYER_MOVEMENT_STATE_CODEC),
            (requirement, value) -> requirement.state = value,
            requirement -> requirement.state
        )
        .add()
        .build();

    public static final ArrayCodec<MovementStateRequirement> MOVEMENT_STATE_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(MOVEMENT_STATE_REQUIREMENT_CODEC, MovementStateRequirement[]::new);

    public static final BuilderCodec<InteractionContextRequirement> INTERACTION_CONTEXT_REQUIREMENT_CODEC = BuilderCodec.builder(
            InteractionContextRequirement.class,
            InteractionContextRequirement::new
    )
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
        .build();

    public static final ArrayCodec<InteractionContextRequirement> INTERACTION_CONTEXT_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(INTERACTION_CONTEXT_REQUIREMENT_CODEC, InteractionContextRequirement[]::new);

    public static final BuilderCodec<RequirementBucket> REQUIREMENT_BUCKET_CODEC = BuilderCodec.builder(
            RequirementBucket.class,
            RequirementBucket::new
    )
        .<Boolean>append(
            new KeyedCodec<>("LovedItems", Codec.BOOLEAN),
            (bucket, value) -> bucket.lovedItems = value != null && value,
            bucket -> bucket.lovedItems
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsHarvestable", Codec.BOOLEAN),
            (bucket, value) -> bucket.isHarvestable = value != null && value,
            bucket -> bucket.isHarvestable
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsMountable", Codec.BOOLEAN),
            (bucket, value) -> bucket.isMountable = value != null && value,
            bucket -> bucket.isMountable
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsTamed", Codec.BOOLEAN),
            (bucket, value) -> bucket.isTamed = value != null && value,
            bucket -> bucket.isTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsNotTamed", Codec.BOOLEAN),
            (bucket, value) -> bucket.isNotTamed = value != null && value,
            bucket -> bucket.isNotTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerCrouching", Codec.BOOLEAN),
            (bucket, value) -> bucket.playerCrouching = value != null && value,
            bucket -> bucket.playerCrouching
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerIsOwner", Codec.BOOLEAN),
            (bucket, value) -> bucket.playerIsOwner = value != null && value,
            bucket -> bucket.playerIsOwner
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("HarvestAlarmReady", Codec.BOOLEAN),
            (bucket, value) -> bucket.harvestAlarmReady = value != null && value,
            bucket -> bucket.harvestAlarmReady
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("HarvestInteractionContext", Codec.BOOLEAN),
            (bucket, value) -> bucket.harvestInteractionContext = value != null && value,
            bucket -> bucket.harvestInteractionContext
        )
        .add()
        .<ItemsInHandRequirement[]>append(
            new KeyedCodec<>("ItemsInHand", ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.itemsInHand = value == null ? EMPTY_ITEMS_IN_HAND_REQUIREMENTS : value,
            bucket -> bucket.itemsInHand
        )
        .add()
        .<ItemsInInventoryRequirement[]>append(
            new KeyedCodec<>("ItemsInInventory", ITEMS_IN_INVENTORY_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.itemsInInventory = value == null ? EMPTY_ITEMS_IN_INVENTORY_REQUIREMENTS : value,
            bucket -> bucket.itemsInInventory
        )
        .add()
        .<ItemsEquippedRequirement[]>append(
            new KeyedCodec<>("ItemsEquipped", ITEMS_EQUIPPED_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.itemsEquipped = value == null ? EMPTY_ITEMS_EQUIPPED_REQUIREMENTS : value,
            bucket -> bucket.itemsEquipped
        )
        .add()
        .<ParamRequirement[]>append(
            new KeyedCodec<>("Parameter", PARAM_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.parameter = value == null ? EMPTY_PARAM_REQUIREMENTS : value,
            bucket -> bucket.parameter
        )
        .add()
        .<AlarmRequirement[]>append(
            new KeyedCodec<>("AlarmState", ALARM_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.alarmState = value == null ? EMPTY_ALARM_REQUIREMENTS : value,
            bucket -> bucket.alarmState
        )
        .add()
        .<StringRequirement[]>append(
            new KeyedCodec<>("NpcState", STRING_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.npcState = value == null ? EMPTY_STRING_REQUIREMENTS : value,
            bucket -> bucket.npcState
        )
        .add()
        .<MovementStateRequirement[]>append(
            new KeyedCodec<>("PlayerMovementState", MOVEMENT_STATE_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.playerMovementState = value == null ? EMPTY_MOVEMENT_STATE_REQUIREMENTS : value,
            bucket -> bucket.playerMovementState
        )
        .add()
        .<InteractionContextRequirement[]>append(
            new KeyedCodec<>("InteractionContext", INTERACTION_CONTEXT_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.interactionContext = value == null ? EMPTY_CONTEXT_REQUIREMENTS : value,
            bucket -> bucket.interactionContext
        )
        .add()
        .build();

    public static final BuilderCodec<RequirementGroup> REQUIREMENT_GROUP_CODEC = BuilderCodec.builder(
            RequirementGroup.class,
            RequirementGroup::new
    )
        .<RequirementBucket>append(
            new KeyedCodec<>("All", REQUIREMENT_BUCKET_CODEC),
            (group, value) -> group.all = value == null ? new RequirementBucket() : value,
            group -> group.all
        )
        .add()
        .<RequirementBucket>append(
            new KeyedCodec<>("Any", REQUIREMENT_BUCKET_CODEC),
            (group, value) -> group.any = value == null ? new RequirementBucket() : value,
            group -> group.any
        )
        .add()
        .build();

    public static final BuilderCodec<HookEffect> HOOK_EFFECT_CODEC = BuilderCodec.builder(
            HookEffect.class,
            HookEffect::new
    )
        .<String>append(
            new KeyedCodec<>("HookId", Codec.STRING),
            (effect, value) -> effect.hookId = value,
            effect -> effect.hookId
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerOnly", Codec.BOOLEAN),
            (effect, value) -> effect.playerOnly = value != null && value,
            effect -> effect.playerOnly
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Consume", Codec.BOOLEAN),
            (effect, value) -> effect.consume = value != null && value,
            effect -> effect.consume
        )
        .add()
        .build();

    public static final BuilderCodec<FloatingTextEffect> FLOATING_TEXT_EFFECT_CODEC = BuilderCodec.builder(
            FloatingTextEffect.class,
            FloatingTextEffect::new
    )
        .<String>append(
            new KeyedCodec<>("Message", Codec.STRING),
            (effect, value) -> effect.message = value,
            effect -> effect.message
        )
        .add()
        .build();

    public static final BuilderCodec<SpawnParticlesEffect> SPAWN_PARTICLES_EFFECT_CODEC = BuilderCodec.builder(
            SpawnParticlesEffect.class,
            SpawnParticlesEffect::new
    )
        .<String>append(
            new KeyedCodec<>("ParticleSystem", PARTICLE_SYSTEM_CODEC),
            (effect, value) -> effect.particleSystem = value,
            effect -> effect.particleSystem
        )
        .add()
        .<Vector3d>append(
            new KeyedCodec<>("Offset", VECTOR3D_CODEC),
            (effect, value) -> effect.offset = value,
            effect -> effect.offset
        )
        .add()
        .<Color>append(
            new KeyedCodec<>("Color", COLOR_CODEC),
            (effect, value) -> effect.color = value,
            effect -> effect.color
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerOnly", Codec.BOOLEAN),
            (effect, value) -> effect.playerOnly = value != null && value,
            effect -> effect.playerOnly
        )
        .add()
        .build();

    public static final BuilderCodec<PlaySoundEffect> PLAY_SOUND_EFFECT_CODEC = BuilderCodec.builder(
            PlaySoundEffect.class,
            PlaySoundEffect::new
    )
        .<String>append(
            new KeyedCodec<>("SoundEvent", SOUND_EVENT_CODEC),
            (effect, value) -> effect.soundEvent = value,
            effect -> effect.soundEvent
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Volume", Codec.DOUBLE),
            (effect, value) -> effect.volume = value,
            effect -> effect.volume
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Pitch", Codec.DOUBLE),
            (effect, value) -> effect.pitch = value,
            effect -> effect.pitch
        )
        .add()
        .<Vector3d>append(
            new KeyedCodec<>("Offset", VECTOR3D_CODEC),
            (effect, value) -> effect.offset = value,
            effect -> effect.offset
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerOnly", Codec.BOOLEAN),
            (effect, value) -> effect.playerOnly = value != null && value,
            effect -> effect.playerOnly
        )
        .add()
        .build();

    public static final BuilderCodec<DropItemEffect> DROP_ITEM_EFFECT_CODEC = BuilderCodec.builder(
            DropItemEffect.class,
            DropItemEffect::new
    )
        .<String>append(
            new KeyedCodec<>("Item", ITEM_ASSET_CODEC),
            (effect, value) -> effect.item = value,
            effect -> effect.item
        )
        .add()
        .<String>append(
            new KeyedCodec<>("DropList", ITEM_DROP_LIST_CODEC),
            (effect, value) -> effect.dropList = value,
            effect -> effect.dropList
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("QuantityMin", Codec.INTEGER),
            (effect, value) -> effect.quantityMin = value,
            effect -> effect.quantityMin
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("QuantityMax", Codec.INTEGER),
            (effect, value) -> effect.quantityMax = value,
            effect -> effect.quantityMax
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ThrowSpeed", Codec.DOUBLE),
            (effect, value) -> effect.throwSpeed = value,
            effect -> effect.throwSpeed
        )
        .add()
        .build();

    public static final BuilderCodec<SetTamedEffect> SET_TAMED_EFFECT_CODEC = BuilderCodec.builder(
            SetTamedEffect.class,
            SetTamedEffect::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Value", Codec.BOOLEAN),
            (effect, value) -> effect.value = value,
            effect -> effect.value
        )
        .add()
        .build();

    public static final BuilderCodec<SetOwnerEffect> SET_OWNER_EFFECT_CODEC = BuilderCodec.builder(
            SetOwnerEffect.class,
            SetOwnerEffect::new
    )
        .<OwnerSource>append(
            new KeyedCodec<>("Source", OWNER_SOURCE_CODEC),
            (effect, value) -> {
                if (value != null) {
                    effect.source = value;
                }
            },
            effect -> effect.source
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Uuid", Codec.STRING),
            (effect, value) -> effect.uuid = value,
            effect -> effect.uuid
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Name", Codec.STRING),
            (effect, value) -> effect.name = value,
            effect -> effect.name
        )
        .add()
        .build();

    public static final BuilderCodec<StatDelta> STAT_DELTA_CODEC = BuilderCodec.builder(
            StatDelta.class,
            StatDelta::new
    )
        .<String>append(
            new KeyedCodec<>("StatId", Codec.STRING),
            (entry, value) -> entry.statId = value,
            entry -> entry.statId
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Amount", Codec.DOUBLE),
            (entry, value) -> entry.amount = value,
            entry -> entry.amount
        )
        .add()
        .build();

    public static final ArrayCodec<StatDelta> STAT_DELTA_ARRAY_CODEC =
            new ArrayCodec<>(STAT_DELTA_CODEC, StatDelta[]::new);

    public static final Codec<ModifyStatsEffect> MODIFY_STATS_EFFECT_CODEC = BuilderCodec.builder(
            ModifyStatsEffect.class,
            ModifyStatsEffect::new
    )
        .<StatDelta[]>append(
            new KeyedCodec<>("Stats", STAT_DELTA_ARRAY_CODEC),
            (effect, value) -> effect.stats = value == null ? EMPTY_STAT_DELTAS : value,
            effect -> effect.stats
        )
        .add()
        .build();

    public static final BuilderCodec<SetStateEffect> SET_STATE_EFFECT_CODEC = BuilderCodec.builder(
            SetStateEffect.class,
            SetStateEffect::new
    )
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (effect, value) -> effect.state = value,
            effect -> effect.state
        )
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (effect, value) -> effect.subState = value,
            effect -> effect.subState
        )
        .add()
        .build();

    public static final BuilderCodec<RemoveItemsHandEffect> REMOVE_ITEMS_HAND_EFFECT_CODEC = BuilderCodec.builder(
            RemoveItemsHandEffect.class,
            RemoveItemsHandEffect::new
    )
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (effect, value) -> effect.quantity = value,
            effect -> effect.quantity
        )
        .add()
        .build();

    public static final BuilderCodec<ItemQuantity> ITEM_QUANTITY_CODEC = BuilderCodec.builder(
            ItemQuantity.class,
            ItemQuantity::new
    )
        .<String>append(
            new KeyedCodec<>("Item", ITEM_ASSET_CODEC),
            (entry, value) -> entry.item = value,
            entry -> entry.item
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (entry, value) -> entry.quantity = value,
            entry -> entry.quantity
        )
        .add()
        .build();

    public static final ArrayCodec<ItemQuantity> ITEM_QUANTITY_ARRAY_CODEC =
            new ArrayCodec<>(ITEM_QUANTITY_CODEC, ItemQuantity[]::new);

    public static final BuilderCodec<RemoveItemsInventoryEffect> REMOVE_ITEMS_INVENTORY_EFFECT_CODEC = BuilderCodec.builder(
            RemoveItemsInventoryEffect.class,
            RemoveItemsInventoryEffect::new
    )
        .<ItemQuantity[]>append(
            new KeyedCodec<>("Items", ITEM_QUANTITY_ARRAY_CODEC),
            (effect, value) -> effect.items = value == null ? EMPTY_ITEM_QUANTITIES : value,
            effect -> effect.items
        )
        .add()
        .build();

    public static final BuilderCodec<AddItemInventoryEffect> ADD_ITEM_INVENTORY_EFFECT_CODEC = BuilderCodec.builder(
            AddItemInventoryEffect.class,
            AddItemInventoryEffect::new
    )
        .<ItemQuantity[]>append(
            new KeyedCodec<>("Items", ITEM_QUANTITY_ARRAY_CODEC),
            (effect, value) -> effect.items = value == null ? EMPTY_ITEM_QUANTITIES : value,
            effect -> effect.items
        )
        .add()
        .build();

    public static final BuilderCodec<Effects> EFFECTS_CODEC = BuilderCodec.builder(
            Effects.class,
            Effects::new
    )
        .<SetTamedEffect>append(
            new KeyedCodec<>("SetTamed", SET_TAMED_EFFECT_CODEC),
            (effects, value) -> effects.setTamed = value,
            effects -> effects.setTamed
        )
        .add()
        .<SetOwnerEffect>append(
            new KeyedCodec<>("SetOwner", SET_OWNER_EFFECT_CODEC),
            (effects, value) -> effects.setOwner = value,
            effects -> effects.setOwner
        )
        .add()
        .<ModifyStatsEffect>append(
            new KeyedCodec<>("ModifyStats", MODIFY_STATS_EFFECT_CODEC),
            (effects, value) -> effects.modifyStats = value,
            effects -> effects.modifyStats
        )
        .add()
        .<SetStateEffect>append(
            new KeyedCodec<>("SetState", SET_STATE_EFFECT_CODEC),
            (effects, value) -> effects.setState = value,
            effects -> effects.setState
        )
        .add()
        .<RemoveItemsHandEffect>append(
            new KeyedCodec<>("RemoveItemsHand", REMOVE_ITEMS_HAND_EFFECT_CODEC),
            (effects, value) -> effects.removeItemsHand = value,
            effects -> effects.removeItemsHand
        )
        .add()
        .<RemoveItemsInventoryEffect>append(
            new KeyedCodec<>("RemoveItemsInventory", REMOVE_ITEMS_INVENTORY_EFFECT_CODEC),
            (effects, value) -> effects.removeItemsInventory = value,
            effects -> effects.removeItemsInventory
        )
        .add()
        .<AddItemInventoryEffect>append(
            new KeyedCodec<>("AddItemInventory", ADD_ITEM_INVENTORY_EFFECT_CODEC),
            (effects, value) -> effects.addItemInventory = value,
            effects -> effects.addItemInventory
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Mount", Codec.BOOLEAN),
            (effects, value) -> effects.mount = value,
            effects -> effects.mount
        )
        .add()
        .<PlaySoundEffect>append(
            new KeyedCodec<>("PlaySound", PLAY_SOUND_EFFECT_CODEC),
            (effects, value) -> effects.playSound = value,
            effects -> effects.playSound
        )
        .add()
        .<SpawnParticlesEffect>append(
            new KeyedCodec<>("SpawnParticles", SPAWN_PARTICLES_EFFECT_CODEC),
            (effects, value) -> effects.spawnParticles = value,
            effects -> effects.spawnParticles
        )
        .add()
        .<DropItemEffect>append(
            new KeyedCodec<>("DropItem", DROP_ITEM_EFFECT_CODEC),
            (effects, value) -> effects.dropItem = value,
            effects -> effects.dropItem
        )
        .add()
        .<HookEffect>append(
            new KeyedCodec<>("TriggerNpcHook", HOOK_EFFECT_CODEC),
            (effects, value) -> effects.triggerNpcHook = value,
            effects -> effects.triggerNpcHook
        )
        .add()
        .<FloatingTextEffect>append(
            new KeyedCodec<>("ShowFloatingText", FLOATING_TEXT_EFFECT_CODEC),
            (effects, value) -> effects.showFloatingText = value,
            effects -> effects.showFloatingText
        )
        .add()
        .build();

    public static final BuilderCodec<ModeStep> MODE_STEP_CODEC = BuilderCodec.builder(
            ModeStep.class,
            ModeStep::new
    )
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (step, value) -> step.state = value,
            step -> step.state
        )
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (step, value) -> step.subState = value,
            step -> step.subState
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Message", Codec.STRING),
            (step, value) -> step.message = value,
            step -> step.message
        )
        .add()
        .build();

    public static final ArrayCodec<ModeStep> MODE_STEP_ARRAY_CODEC =
            new ArrayCodec<>(MODE_STEP_CODEC, ModeStep[]::new);

    public static final BuilderCodec<InteractionEntry> INTERACTION_BASE_CODEC = BuilderCodec.abstractBuilder(
            InteractionEntry.class
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (entry, value) -> entry.enabled = value == null || value,
            entry -> entry.enabled
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownSeconds", Codec.INTEGER),
            (entry, value) -> entry.cooldownSeconds = value,
            entry -> entry.cooldownSeconds
        )
        .add()
        .build();

    public static final BuilderCodec<TameInteraction> TAME_INTERACTION_CODEC = BuilderCodec.builder(
            TameInteraction.class,
            TameInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<Boolean>append(
            new KeyedCodec<>("UseLovedItems", Codec.BOOLEAN),
            (entry, value) -> entry.useLovedItems = value,
            entry -> entry.useLovedItems
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("ItemsInHand", ITEM_ASSET_ARRAY_OR_SINGLE_CODEC),
            (entry, value) -> entry.itemsInHand = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            entry -> entry.itemsInHand
        )
        .add()
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (entry, value) -> entry.itemsParam = value,
            entry -> entry.itemsParam
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final BuilderCodec<FeedItem> FEED_ITEM_CODEC = BuilderCodec.builder(
            FeedItem.class,
            FeedItem::new
    )
        .<String>append(
            new KeyedCodec<>("Item", ITEM_ASSET_CODEC),
            (entry, value) -> entry.item = value,
            entry -> entry.item
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Heal", Codec.DOUBLE),
            (entry, value) -> entry.heal = value,
            entry -> entry.heal
        )
        .add()
        .build();

    public static final ArrayCodec<FeedItem> FEED_ITEM_ARRAY_CODEC =
            new ArrayCodec<>(FEED_ITEM_CODEC, FeedItem[]::new);

    private static final Codec<FeedItem[]> FEED_ITEM_ARRAY_OR_SINGLE_CODEC = new Codec<>() {
        @Override
        public FeedItem[] decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            if (Codec.isNullBsonValue(bsonValue)) {
                return EMPTY_FEED_ITEMS;
            }
            if (bsonValue.isDocument()) {
                return new FeedItem[] { FEED_ITEM_CODEC.decode(bsonValue, extraInfo) };
            }
            if (bsonValue.isString()) {
                return new FeedItem[] { new FeedItem(bsonValue.asString().getValue(), null) };
            }
            if (bsonValue.isArray()) {
                ArrayList<FeedItem> items = new ArrayList<>();
                for (BsonValue value : bsonValue.asArray()) {
                    if (value == null || Codec.isNullBsonValue(value)) {
                        continue;
                    }
                    if (value.isString()) {
                        items.add(new FeedItem(value.asString().getValue(), null));
                        continue;
                    }
                    if (value.isDocument()) {
                        items.add(FEED_ITEM_CODEC.decode(value, extraInfo));
                        continue;
                    }
                    throw new CodecException("Expected feed item object or string", value, extraInfo, null);
                }
                return items.toArray(EMPTY_FEED_ITEMS);
            }
            throw new CodecException("Expected feed item object, string, or array", bsonValue, extraInfo, null);
        }

        @Override
        public BsonValue encode(FeedItem[] value, ExtraInfo extraInfo) {
            if (value == null) {
                return new BsonNull();
            }
            return FEED_ITEM_ARRAY_CODEC.encode(value, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItem(FEED_ITEM_CODEC.toSchema(context));
            return arraySchema;
        }
    };

    public static final BuilderCodec<FeedInteraction> FEED_INTERACTION_CODEC = BuilderCodec.builder(
            FeedInteraction.class,
            FeedInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<Boolean>append(
            new KeyedCodec<>("UseLovedItems", Codec.BOOLEAN),
            (entry, value) -> entry.useLovedItems = value,
            entry -> entry.useLovedItems
        )
        .add()
        .<FeedItem[]>append(
            new KeyedCodec<>("ItemsInHand", FEED_ITEM_ARRAY_OR_SINGLE_CODEC),
            (entry, value) -> entry.itemsInHand = value == null ? EMPTY_FEED_ITEMS : value,
            entry -> entry.itemsInHand
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Heal", Codec.DOUBLE),
            (entry, value) -> entry.heal = value,
            entry -> entry.heal
        )
        .add()
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (entry, value) -> entry.itemsParam = value,
            entry -> entry.itemsParam
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final BuilderCodec<HarvestInteraction> HARVEST_INTERACTION_CODEC = BuilderCodec.builder(
            HarvestInteraction.class,
            HarvestInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (entry, value) -> entry.requireTamed = value,
            entry -> entry.requireTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireHarvestable", Codec.BOOLEAN),
            (entry, value) -> entry.requireHarvestable = value,
            entry -> entry.requireHarvestable
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireHarvestAlarmReady", Codec.BOOLEAN),
            (entry, value) -> entry.requireHarvestAlarmReady = value,
            entry -> entry.requireHarvestAlarmReady
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireHarvestInteractionContext", Codec.BOOLEAN),
            (entry, value) -> entry.requireHarvestInteractionContext = value,
            entry -> entry.requireHarvestInteractionContext
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final BuilderCodec<MountInteraction> MOUNT_INTERACTION_CODEC = BuilderCodec.builder(
            MountInteraction.class,
            MountInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (entry, value) -> entry.requireTamed = value,
            entry -> entry.requireTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (entry, value) -> entry.requireOwner = value,
            entry -> entry.requireOwner
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireMountable", Codec.BOOLEAN),
            (entry, value) -> entry.requireMountable = value,
            entry -> entry.requireMountable
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireCrouching", Codec.BOOLEAN),
            (entry, value) -> entry.requireCrouching = value,
            entry -> entry.requireCrouching
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final BuilderCodec<ModeCycleInteraction> MODE_CYCLE_INTERACTION_CODEC = BuilderCodec.builder(
            ModeCycleInteraction.class,
            ModeCycleInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (entry, value) -> entry.requireTamed = value,
            entry -> entry.requireTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (entry, value) -> entry.requireOwner = value,
            entry -> entry.requireOwner
        )
        .add()
        .<ModeStep[]>append(
            new KeyedCodec<>("Cycle", MODE_STEP_ARRAY_CODEC),
            (entry, value) -> entry.cycle = value == null ? EMPTY_MODE_CYCLE : value,
            entry -> entry.cycle
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final BuilderCodec<BreedInteraction> BREED_INTERACTION_CODEC = BuilderCodec.builder(
            BreedInteraction.class,
            BreedInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (entry, value) -> entry.requireTamed = value,
            entry -> entry.requireTamed
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MinHappiness", Codec.DOUBLE),
            (entry, value) -> entry.minHappiness = value,
            entry -> entry.minHappiness
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("FertilityBonus", Codec.DOUBLE),
            (entry, value) -> entry.fertilityBonus = value,
            entry -> entry.fertilityBonus
        )
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final BuilderCodec<CustomInteraction> CUSTOM_INTERACTION_CODEC = BuilderCodec.builder(
            CustomInteraction.class,
            CustomInteraction::new,
            INTERACTION_BASE_CODEC
    )
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .add()
        .build();

    public static final StringCodecMapCodec<InteractionEntry, BuilderCodec<? extends InteractionEntry>> INTERACTION_CODEC =
            new StringCodecMapCodec<>("Type") { };

    static {
        INTERACTION_CODEC.register("Tame", TameInteraction.class, TAME_INTERACTION_CODEC);
        INTERACTION_CODEC.register("Feed", FeedInteraction.class, FEED_INTERACTION_CODEC);
        INTERACTION_CODEC.register("Breed", BreedInteraction.class, BREED_INTERACTION_CODEC);
        INTERACTION_CODEC.register("Harvest", HarvestInteraction.class, HARVEST_INTERACTION_CODEC);
        INTERACTION_CODEC.register("Mount", MountInteraction.class, MOUNT_INTERACTION_CODEC);
        INTERACTION_CODEC.register("ModeCycle", ModeCycleInteraction.class, MODE_CYCLE_INTERACTION_CODEC);
        INTERACTION_CODEC.register("Custom", CustomInteraction.class, CUSTOM_INTERACTION_CODEC);
    }

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
}
