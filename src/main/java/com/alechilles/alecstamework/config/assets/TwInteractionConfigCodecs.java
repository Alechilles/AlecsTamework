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

    private static final Codec<String[]> ITEM_ASSET_ARRAY_CODEC = new Codec<>() {
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
            throw new CodecException("Expected string array", bsonValue, extraInfo, null);
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
            return arraySchema;
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
            return arraySchema;
        }
    };

    private static final Codec<Vector3d> VECTOR3D_CODEC = new Vector3dArrayCodec();
    private static final Codec<Color> COLOR_CODEC = new ColorCodec();

    public static final BuilderCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_CODEC = BuilderCodec.builder(
            ItemsInHandRequirement.class,
            ItemsInHandRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (requirement, value) -> requirement.itemsParam = value,
            requirement -> requirement.itemsParam
        )
        .documentation("Role param to import items from (string, string[], or JSON array string).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Items", ITEM_ASSET_ARRAY_CODEC),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .documentation("Items that must be held in hand.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (requirement, value) -> requirement.quantity = value,
            requirement -> requirement.quantity
        )
        .documentation("Minimum stack size required.")
        .add()
        .build();

    public static final ArrayCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ITEMS_IN_HAND_REQUIREMENT_CODEC, ItemsInHandRequirement[]::new);

    public static final BuilderCodec<ItemsInInventoryRequirement> ITEMS_IN_INVENTORY_REQUIREMENT_CODEC = BuilderCodec.builder(
            ItemsInInventoryRequirement.class,
            ItemsInInventoryRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (requirement, value) -> requirement.itemsParam = value,
            requirement -> requirement.itemsParam
        )
        .documentation("Role param to import items from (string, string[], or JSON array string).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Items", ITEM_ASSET_ARRAY_CODEC),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .documentation("Items that must exist in inventory.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (requirement, value) -> requirement.quantity = value,
            requirement -> requirement.quantity
        )
        .documentation("Minimum total quantity required.")
        .add()
        .build();

    public static final ArrayCodec<ItemsInInventoryRequirement> ITEMS_IN_INVENTORY_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ITEMS_IN_INVENTORY_REQUIREMENT_CODEC, ItemsInInventoryRequirement[]::new);

    public static final BuilderCodec<ItemsEquippedRequirement> ITEMS_EQUIPPED_REQUIREMENT_CODEC = BuilderCodec.builder(
            ItemsEquippedRequirement.class,
            ItemsEquippedRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (requirement, value) -> requirement.itemsParam = value,
            requirement -> requirement.itemsParam
        )
        .documentation("Role param to import items from (string, string[], or JSON array string).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Items", ITEM_ASSET_ARRAY_CODEC),
            (requirement, value) -> requirement.items = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.items
        )
        .documentation("Optional items that must be equipped.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Slots", EQUIPPED_SLOT_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.slots = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.slots
        )
        .documentation("Slots to check for equipped items.")
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
        .documentation("Role parameter name to evaluate.")
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
        .documentation("Comparison operator for the parameter.")
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
        .documentation("Whether any or all values must match.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Value", STRING_ARRAY_OR_SINGLE_CODEC),
            (requirement, value) -> requirement.values = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            requirement -> requirement.values
        )
        .documentation("Value or values to compare against.")
        .add()
        .build();

    public static final ArrayCodec<ParamRequirement> PARAM_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(PARAM_REQUIREMENT_CODEC, ParamRequirement[]::new);

    public static final BuilderCodec<AlarmRequirement> ALARM_REQUIREMENT_CODEC = BuilderCodec.builder(
            AlarmRequirement.class,
            AlarmRequirement::new
    )
        .<String>append(
            new KeyedCodec<>("AlarmParam", Codec.STRING),
            (requirement, value) -> requirement.alarmParam = value,
            requirement -> requirement.alarmParam
        )
        .documentation("Role param that provides the alarm name.")
        .add()
        .<String>append(
            new KeyedCodec<>("Name", Codec.STRING),
            (requirement, value) -> requirement.name = value,
            requirement -> requirement.name
        )
        .documentation("Alarm name to evaluate.")
        .add()
        .<String>append(
            new KeyedCodec<>("State", Codec.STRING),
            (requirement, value) -> requirement.state = value,
            requirement -> requirement.state
        )
        .documentation("Alarm state: unset, active, or passed.")
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
        .documentation("Primary NPC state name.")
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (requirement, value) -> requirement.subState = value,
            requirement -> requirement.subState
        )
        .documentation("Optional substate name.")
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
        .documentation("Required player movement state.")
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
        .documentation("Interaction context name to check.")
        .add()
        .<String>append(
            new KeyedCodec<>("ContextParam", Codec.STRING),
            (requirement, value) -> requirement.contextParam = value,
            requirement -> requirement.contextParam
        )
        .documentation("Role param that provides the interaction context.")
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
        .documentation("Require loved items in the player's hand.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsHarvestable", Codec.BOOLEAN),
            (bucket, value) -> bucket.isHarvestable = value != null && value,
            bucket -> bucket.isHarvestable
        )
        .documentation("Require the NPC's IsHarvestable parameter to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsMountable", Codec.BOOLEAN),
            (bucket, value) -> bucket.isMountable = value != null && value,
            bucket -> bucket.isMountable
        )
        .documentation("Require the NPC's IsMountable parameter to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsTamed", Codec.BOOLEAN),
            (bucket, value) -> bucket.isTamed = value != null && value,
            bucket -> bucket.isTamed
        )
        .documentation("Require the NPC to be tamed.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("IsNotTamed", Codec.BOOLEAN),
            (bucket, value) -> bucket.isNotTamed = value != null && value,
            bucket -> bucket.isNotTamed
        )
        .documentation("Require the NPC to be untamed.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerCrouching", Codec.BOOLEAN),
            (bucket, value) -> bucket.playerCrouching = value != null && value,
            bucket -> bucket.playerCrouching
        )
        .documentation("Require the player to be crouching.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerIsOwner", Codec.BOOLEAN),
            (bucket, value) -> bucket.playerIsOwner = value != null && value,
            bucket -> bucket.playerIsOwner
        )
        .documentation("Require the player to be the NPC's owner.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("HarvestAlarmReady", Codec.BOOLEAN),
            (bucket, value) -> bucket.harvestAlarmReady = value != null && value,
            bucket -> bucket.harvestAlarmReady
        )
        .documentation("Require the Harvest_Ready alarm to be ready.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("HarvestInteractionContext", Codec.BOOLEAN),
            (bucket, value) -> bucket.harvestInteractionContext = value != null && value,
            bucket -> bucket.harvestInteractionContext
        )
        .documentation("Require the HarvestInteractionContext to be valid.")
        .add()
        .<ItemsInHandRequirement[]>append(
            new KeyedCodec<>("ItemsInHand", ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.itemsInHand = value == null ? EMPTY_ITEMS_IN_HAND_REQUIREMENTS : value,
            bucket -> bucket.itemsInHand
        )
        .documentation("Custom checks for items held in hand.")
        .add()
        .<ItemsInInventoryRequirement[]>append(
            new KeyedCodec<>("ItemsInInventory", ITEMS_IN_INVENTORY_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.itemsInInventory = value == null ? EMPTY_ITEMS_IN_INVENTORY_REQUIREMENTS : value,
            bucket -> bucket.itemsInInventory
        )
        .documentation("Custom checks for items in inventory.")
        .add()
        .<ItemsEquippedRequirement[]>append(
            new KeyedCodec<>("ItemsEquipped", ITEMS_EQUIPPED_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.itemsEquipped = value == null ? EMPTY_ITEMS_EQUIPPED_REQUIREMENTS : value,
            bucket -> bucket.itemsEquipped
        )
        .documentation("Custom checks for equipped items.")
        .add()
        .<ParamRequirement[]>append(
            new KeyedCodec<>("Parameter", PARAM_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.parameter = value == null ? EMPTY_PARAM_REQUIREMENTS : value,
            bucket -> bucket.parameter
        )
        .documentation("Custom checks against role parameters.")
        .add()
        .<AlarmRequirement[]>append(
            new KeyedCodec<>("AlarmState", ALARM_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.alarmState = value == null ? EMPTY_ALARM_REQUIREMENTS : value,
            bucket -> bucket.alarmState
        )
        .documentation("Custom checks against alarm state.")
        .add()
        .<StringRequirement[]>append(
            new KeyedCodec<>("NpcState", STRING_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.npcState = value == null ? EMPTY_STRING_REQUIREMENTS : value,
            bucket -> bucket.npcState
        )
        .documentation("Custom checks against NPC state and substate.")
        .add()
        .<MovementStateRequirement[]>append(
            new KeyedCodec<>("PlayerMovementState", MOVEMENT_STATE_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.playerMovementState = value == null ? EMPTY_MOVEMENT_STATE_REQUIREMENTS : value,
            bucket -> bucket.playerMovementState
        )
        .documentation("Custom checks against player movement state.")
        .add()
        .<InteractionContextRequirement[]>append(
            new KeyedCodec<>("InteractionContext", INTERACTION_CONTEXT_REQUIREMENT_ARRAY_CODEC),
            (bucket, value) -> bucket.interactionContext = value == null ? EMPTY_CONTEXT_REQUIREMENTS : value,
            bucket -> bucket.interactionContext
        )
        .documentation("Custom checks against interaction context.")
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
        .documentation("All requirements must pass.")
        .add()
        .<RequirementBucket>append(
            new KeyedCodec<>("Any", REQUIREMENT_BUCKET_CODEC),
            (group, value) -> group.any = value == null ? new RequirementBucket() : value,
            group -> group.any
        )
        .documentation("At least one requirement must pass.")
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
        .documentation("Hook identifier to emit on the NPC.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerOnly", Codec.BOOLEAN),
            (effect, value) -> effect.playerOnly = value != null && value,
            effect -> effect.playerOnly
        )
        .documentation("Only allow triggering when a player is present.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Consume", Codec.BOOLEAN),
            (effect, value) -> effect.consume = value != null && value,
            effect -> effect.consume
        )
        .documentation("Sets the consume flag on the hook payload.")
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
        .documentation("Message shown as floating combat text.")
        .add()
        .build();

    public static final BuilderCodec<UiMessageEffect> UI_MESSAGE_EFFECT_CODEC = BuilderCodec.builder(
            UiMessageEffect.class,
            UiMessageEffect::new
    )
        .<String>append(
            new KeyedCodec<>("Message", Codec.STRING),
            (effect, value) -> effect.message = value,
            effect -> effect.message
        )
        .documentation("Message shown in the on-screen UI overlay.")
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
        .documentation("Particle system asset to spawn.")
        .add()
        .<Vector3d>append(
            new KeyedCodec<>("Offset", VECTOR3D_CODEC),
            (effect, value) -> effect.offset = value,
            effect -> effect.offset
        )
        .documentation("Offset from the NPC position.")
        .add()
        .<Color>append(
            new KeyedCodec<>("Color", COLOR_CODEC),
            (effect, value) -> effect.color = value,
            effect -> effect.color
        )
        .documentation("Optional color tint for the particle system.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerOnly", Codec.BOOLEAN),
            (effect, value) -> effect.playerOnly = value != null && value,
            effect -> effect.playerOnly
        )
        .documentation("Only show particles to the interacting player.")
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
        .documentation("Sound event asset to play.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Volume", Codec.DOUBLE),
            (effect, value) -> effect.volume = value,
            effect -> effect.volume
        )
        .documentation("Volume multiplier.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Pitch", Codec.DOUBLE),
            (effect, value) -> effect.pitch = value,
            effect -> effect.pitch
        )
        .documentation("Pitch multiplier.")
        .add()
        .<Vector3d>append(
            new KeyedCodec<>("Offset", VECTOR3D_CODEC),
            (effect, value) -> effect.offset = value,
            effect -> effect.offset
        )
        .documentation("Offset from the NPC position.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PlayerOnly", Codec.BOOLEAN),
            (effect, value) -> effect.playerOnly = value != null && value,
            effect -> effect.playerOnly
        )
        .documentation("Only play the sound for the interacting player.")
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
        .documentation("Single item to drop (optional).")
        .add()
        .<String>append(
            new KeyedCodec<>("DropList", ITEM_DROP_LIST_CODEC),
            (effect, value) -> effect.dropList = value,
            effect -> effect.dropList
        )
        .documentation("Item drop list asset to roll from (optional).")
        .add()
        .<Integer>append(
            new KeyedCodec<>("QuantityMin", Codec.INTEGER),
            (effect, value) -> effect.quantityMin = value,
            effect -> effect.quantityMin
        )
        .documentation("Minimum quantity when dropping a single item.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("QuantityMax", Codec.INTEGER),
            (effect, value) -> effect.quantityMax = value,
            effect -> effect.quantityMax
        )
        .documentation("Maximum quantity when dropping a single item.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThrowSpeed", Codec.DOUBLE),
            (effect, value) -> effect.throwSpeed = value,
            effect -> effect.throwSpeed
        )
        .documentation("Initial throw speed for dropped items.")
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
        .documentation("True to set tamed, false to clear.")
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
        .documentation("Where to take owner data from.")
        .add()
        .<String>append(
            new KeyedCodec<>("Uuid", Codec.STRING),
            (effect, value) -> effect.uuid = value,
            effect -> effect.uuid
        )
        .documentation("Owner UUID when Source is Custom.")
        .add()
        .<String>append(
            new KeyedCodec<>("Name", Codec.STRING),
            (effect, value) -> effect.name = value,
            effect -> effect.name
        )
        .documentation("Owner name when Source is Custom.")
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
        .documentation("Stat asset ID to modify.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Amount", Codec.DOUBLE),
            (entry, value) -> entry.amount = value,
            entry -> entry.amount
        )
        .documentation("Additive change to apply.")
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
        .documentation("Stat changes to apply.")
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
        .documentation("Target state to set.")
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (effect, value) -> effect.subState = value,
            effect -> effect.subState
        )
        .documentation("Target substate to set.")
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
        .documentation("Number of items to remove from hand.")
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
        .documentation("Item asset ID.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("Quantity", Codec.INTEGER),
            (entry, value) -> entry.quantity = value,
            entry -> entry.quantity
        )
        .documentation("Quantity to add or remove.")
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
        .documentation("Items to remove from inventory.")
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
        .documentation("Items to add to inventory.")
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
        .documentation("Set or clear tamed status.")
        .add()
        .<SetOwnerEffect>append(
            new KeyedCodec<>("SetOwner", SET_OWNER_EFFECT_CODEC),
            (effects, value) -> effects.setOwner = value,
            effects -> effects.setOwner
        )
        .documentation("Assign or clear owner info.")
        .add()
        .<ModifyStatsEffect>append(
            new KeyedCodec<>("ModifyStats", MODIFY_STATS_EFFECT_CODEC),
            (effects, value) -> effects.modifyStats = value,
            effects -> effects.modifyStats
        )
        .documentation("Apply stat deltas.")
        .add()
        .<SetStateEffect>append(
            new KeyedCodec<>("SetState", SET_STATE_EFFECT_CODEC),
            (effects, value) -> effects.setState = value,
            effects -> effects.setState
        )
        .documentation("Set NPC state/substate.")
        .add()
        .<RemoveItemsHandEffect>append(
            new KeyedCodec<>("RemoveItemsHand", REMOVE_ITEMS_HAND_EFFECT_CODEC),
            (effects, value) -> effects.removeItemsHand = value,
            effects -> effects.removeItemsHand
        )
        .documentation("Remove items from the player's hand.")
        .add()
        .<RemoveItemsInventoryEffect>append(
            new KeyedCodec<>("RemoveItemsInventory", REMOVE_ITEMS_INVENTORY_EFFECT_CODEC),
            (effects, value) -> effects.removeItemsInventory = value,
            effects -> effects.removeItemsInventory
        )
        .documentation("Remove items from the player's inventory.")
        .add()
        .<AddItemInventoryEffect>append(
            new KeyedCodec<>("AddItemInventory", ADD_ITEM_INVENTORY_EFFECT_CODEC),
            (effects, value) -> effects.addItemInventory = value,
            effects -> effects.addItemInventory
        )
        .documentation("Add items to the player's inventory.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Mount", Codec.BOOLEAN),
            (effects, value) -> effects.mount = value,
            effects -> effects.mount
        )
        .documentation("Attempt to mount the NPC.")
        .add()
        .<PlaySoundEffect>append(
            new KeyedCodec<>("PlaySound", PLAY_SOUND_EFFECT_CODEC),
            (effects, value) -> effects.playSound = value,
            effects -> effects.playSound
        )
        .documentation("Play a sound effect.")
        .add()
        .<SpawnParticlesEffect>append(
            new KeyedCodec<>("SpawnParticles", SPAWN_PARTICLES_EFFECT_CODEC),
            (effects, value) -> effects.spawnParticles = value,
            effects -> effects.spawnParticles
        )
        .documentation("Spawn particle effects.")
        .add()
        .<DropItemEffect>append(
            new KeyedCodec<>("DropItem", DROP_ITEM_EFFECT_CODEC),
            (effects, value) -> effects.dropItem = value,
            effects -> effects.dropItem
        )
        .documentation("Drop items from the NPC.")
        .add()
        .<HookEffect>append(
            new KeyedCodec<>("TriggerNpcHook", HOOK_EFFECT_CODEC),
            (effects, value) -> effects.triggerNpcHook = value,
            effects -> effects.triggerNpcHook
        )
        .documentation("Emit a Tamework NPC hook payload.")
        .add()
        .<FloatingTextEffect>append(
            new KeyedCodec<>("ShowFloatingText", FLOATING_TEXT_EFFECT_CODEC),
            (effects, value) -> effects.showFloatingText = value,
            effects -> effects.showFloatingText
        )
        .documentation("Show floating combat text.")
        .add()
        .<UiMessageEffect>append(
            new KeyedCodec<>("ShowUiMessage", UI_MESSAGE_EFFECT_CODEC),
            (effects, value) -> effects.showUiMessage = value,
            effects -> effects.showUiMessage
        )
        .documentation("Show a temporary UI message to the interacting player.")
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
        .documentation("Mode state name to set.")
        .add()
        .<String>append(
            new KeyedCodec<>("SubState", Codec.STRING),
            (step, value) -> step.subState = value,
            step -> step.subState
        )
        .documentation("Mode substate name to set.")
        .add()
        .<String>append(
            new KeyedCodec<>("Message", Codec.STRING),
            (step, value) -> step.message = value,
            step -> step.message
        )
        .documentation("Optional UI message for this mode.")
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
        .documentation("Whether this interaction entry is enabled. Default: true.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownSeconds", Codec.INTEGER),
            (entry, value) -> entry.cooldownSeconds = value,
            entry -> entry.cooldownSeconds
        )
        .documentation("Cooldown before this entry can trigger again. Default: none.")
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
        .documentation("Allow loved items as valid tame items. Default: true.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("ItemsInHand", ITEM_ASSET_ARRAY_OR_SINGLE_CODEC),
            (entry, value) -> entry.itemsInHand = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            entry -> entry.itemsInHand
        )
        .documentation("Items that can tame the NPC. Default: empty.")
        .add()
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (entry, value) -> entry.itemsParam = value,
            entry -> entry.itemsParam
        )
        .documentation("Role param that provides items list (string, string[], or JSON array string). Default: none.")
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .documentation("Additional requirements to tame. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Additional effects to apply after taming. Default: none.")
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
        .documentation("Item asset ID for this feed entry.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Heal", Codec.DOUBLE),
            (entry, value) -> entry.heal = value,
            entry -> entry.heal
        )
        .documentation("Heal override for this item.")
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
        .documentation("Allow loved items as valid feed items. Default: true.")
        .add()
        .<FeedItem[]>append(
            new KeyedCodec<>("ItemsInHand", FEED_ITEM_ARRAY_OR_SINGLE_CODEC),
            (entry, value) -> entry.itemsInHand = value == null ? EMPTY_FEED_ITEMS : value,
            entry -> entry.itemsInHand
        )
        .documentation("Feed items with per-item heal overrides. Default: empty.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Heal", Codec.DOUBLE),
            (entry, value) -> entry.heal = value,
            entry -> entry.heal
        )
        .documentation("Default heal amount if no per-item override. Default: 0.")
        .add()
        .<String>append(
            new KeyedCodec<>("ItemsParam", Codec.STRING),
            (entry, value) -> entry.itemsParam = value,
            entry -> entry.itemsParam
        )
        .documentation("Role param that provides items list (string, string[], or JSON array string of items/objects). Default: none.")
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .documentation("Additional requirements to feed. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Additional effects to apply after feeding. Default: none.")
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
        .documentation("Require the NPC to be tamed. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireHarvestable", Codec.BOOLEAN),
            (entry, value) -> entry.requireHarvestable = value,
            entry -> entry.requireHarvestable
        )
        .documentation("Require the IsHarvestable role param. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireHarvestAlarmReady", Codec.BOOLEAN),
            (entry, value) -> entry.requireHarvestAlarmReady = value,
            entry -> entry.requireHarvestAlarmReady
        )
        .documentation("Require the Harvest_Ready alarm to be ready. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireHarvestInteractionContext", Codec.BOOLEAN),
            (entry, value) -> entry.requireHarvestInteractionContext = value,
            entry -> entry.requireHarvestInteractionContext
        )
        .documentation("Require the HarvestInteractionContext to match. Default: true.")
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .documentation("Additional requirements to harvest. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Additional effects to apply after harvesting. Default: none.")
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
        .documentation("Require the NPC to be tamed. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (entry, value) -> entry.requireOwner = value,
            entry -> entry.requireOwner
        )
        .documentation("Require the player to be the owner. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireMountable", Codec.BOOLEAN),
            (entry, value) -> entry.requireMountable = value,
            entry -> entry.requireMountable
        )
        .documentation("Require the IsMountable role param. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireCrouching", Codec.BOOLEAN),
            (entry, value) -> entry.requireCrouching = value,
            entry -> entry.requireCrouching
        )
        .documentation("Require the player to be crouching. Default: true.")
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .documentation("Additional requirements to mount. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Additional effects to apply after mounting. Default: none.")
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
        .documentation("Require the NPC to be tamed. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (entry, value) -> entry.requireOwner = value,
            entry -> entry.requireOwner
        )
        .documentation("Require the player to be the owner. Default: true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ShowFloatingText", Codec.BOOLEAN),
            (entry, value) -> entry.showFloatingText = value,
            entry -> entry.showFloatingText
        )
        .documentation("Show the mode cycle message as floating text. Default: false.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ShowUiMessage", Codec.BOOLEAN),
            (entry, value) -> entry.showUiMessage = value,
            entry -> entry.showUiMessage
        )
        .documentation("Show the mode cycle message as a UI message. Default: false.")
        .add()
        .<ModeStep[]>append(
            new KeyedCodec<>("Cycle", MODE_STEP_ARRAY_CODEC),
            (entry, value) -> entry.cycle = value == null ? EMPTY_MODE_CYCLE : value,
            entry -> entry.cycle
        )
        .documentation("Ordered mode steps to cycle through. Default: empty.")
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .documentation("Additional requirements to change mode. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Additional effects to apply after cycling. Default: none.")
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
        .documentation("Require the NPC to be tamed. Default: true.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MinHappiness", Codec.DOUBLE),
            (entry, value) -> entry.minHappiness = value,
            entry -> entry.minHappiness
        )
        .documentation("Minimum happiness to allow breeding. Default: none.")
        .add()
        .<Double>append(
            new KeyedCodec<>("FertilityBonus", Codec.DOUBLE),
            (entry, value) -> entry.fertilityBonus = value,
            entry -> entry.fertilityBonus
        )
        .documentation("Additive fertility bonus. Default: none.")
        .add()
        .<RequirementGroup>append(
            new KeyedCodec<>("Requires", REQUIREMENT_GROUP_CODEC),
            (entry, value) -> entry.requires = value,
            entry -> entry.requires
        )
        .documentation("Additional requirements to breed. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Additional effects to apply after breeding. Default: none.")
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
        .documentation("Custom requirements for this interaction. Default: none.")
        .add()
        .<Effects>append(
            new KeyedCodec<>("Effects", EFFECTS_CODEC),
            (entry, value) -> entry.effects = value,
            entry -> entry.effects
        )
        .documentation("Effects to apply when matched. Default: none.")
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
        .documentation("Default cooldown applied to interactions.")
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
        .documentation("Enable or disable this interaction config.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
            (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            asset -> asset.roleIds
        )
        .documentation("NPC role IDs this config applies to.")
        .add()
        .<InteractionEntry[]>append(
            new KeyedCodec<>("Interactions", INTERACTION_ARRAY_CODEC),
            (asset, value) -> asset.interactions = value == null ? EMPTY_INTERACTIONS : value,
            asset -> asset.interactions
        )
        .documentation("Ordered list of interactions (first match wins).")
        .add()
        .<Cooldowns>append(
            new KeyedCodec<>("Cooldowns", COOLDOWNS_CODEC),
            (asset, value) -> asset.cooldowns = value == null ? new Cooldowns() : value,
            asset -> asset.cooldowns
        )
        .documentation("Default cooldown settings.")
        .add()
        .build();
}
