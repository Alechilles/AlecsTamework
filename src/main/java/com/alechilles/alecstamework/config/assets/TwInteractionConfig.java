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
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.exception.CodecException;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonNull;
import org.bson.BsonValue;

/**
 * Asset-backed configuration for optimized interaction rules.
 * Stored under Server/Tamework/Interactions.
 */
public class TwInteractionConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwInteractionConfig>> {
    public enum ParamOperator {
        Equals,
        NotEquals,
        GreaterThan,
        GreaterThanOrEqual,
        LessThan,
        LessThanOrEqual
    }

    public enum MatchType {
        Any,
        All
    }

    private static final EnumCodec<ParamOperator> PARAM_OPERATOR_CODEC =
            new EnumCodec<>(ParamOperator.class);
    private static final EnumCodec<MatchType> MATCH_TYPE_CODEC =
            new EnumCodec<>(MatchType.class);

    private static final InteractionEntry[] EMPTY_INTERACTIONS = new InteractionEntry[0];
    private static final ModeStep[] EMPTY_MODE_CYCLE = new ModeStep[0];
    private static final FeedItem[] EMPTY_FEED_ITEMS = new FeedItem[0];
    private static final ItemsInHandRequirement[] EMPTY_ITEMS_IN_HAND_REQUIREMENTS = new ItemsInHandRequirement[0];
    private static final ItemsEquippedRequirement[] EMPTY_ITEMS_EQUIPPED_REQUIREMENTS = new ItemsEquippedRequirement[0];
    private static final InteractionContextRequirement[] EMPTY_CONTEXT_REQUIREMENTS = new InteractionContextRequirement[0];
    private static final StringRequirement[] EMPTY_STRING_REQUIREMENTS = new StringRequirement[0];
    private static final MovementStateRequirement[] EMPTY_MOVEMENT_STATE_REQUIREMENTS = new MovementStateRequirement[0];
    private static final AlarmRequirement[] EMPTY_ALARM_REQUIREMENTS = new AlarmRequirement[0];
    private static final ParamRequirement[] EMPTY_PARAM_REQUIREMENTS = new ParamRequirement[0];
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
            new KeyedCodec<>("ConsumeItem", Codec.BOOLEAN),
            (entry, value) -> entry.consumeItem = value,
            entry -> entry.consumeItem
        )
        .add()
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
            new KeyedCodec<>("ConsumeItem", Codec.BOOLEAN),
            (entry, value) -> entry.consumeItem = value,
            entry -> entry.consumeItem
        )
        .add()
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
        .build();

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
        .build();

    public static final ArrayCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC =
            new ArrayCodec<>(ITEMS_IN_HAND_REQUIREMENT_CODEC, ItemsInHandRequirement[]::new);

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
            new KeyedCodec<>("StartBreeding", Codec.BOOLEAN),
            (effects, value) -> effects.startBreeding = value,
            effects -> effects.startBreeding
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ApplyFeeding", Codec.BOOLEAN),
            (effects, value) -> effects.applyFeeding = value,
            effects -> effects.applyFeeding
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("FeedHeal", Codec.DOUBLE),
            (effects, value) -> effects.feedHeal = value,
            effects -> effects.feedHeal
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
        .<ModeStep[]>append(
            new KeyedCodec<>("ModeCycle", MODE_STEP_ARRAY_CODEC),
            (effects, value) -> effects.modeCycle = value == null ? EMPTY_MODE_CYCLE : value,
            effects -> effects.modeCycle
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
        .<HookEffect>append(
            new KeyedCodec<>("TriggerNpcHook", HOOK_EFFECT_CODEC),
            (effects, value) -> effects.triggerNpcHook = value,
            effects -> effects.triggerNpcHook
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

    private static AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> ASSET_STORE;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
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

    public static final class Cooldowns {
        private Integer interactionSeconds;

        public Integer getInteractionSeconds() {
            return interactionSeconds;
        }
    }

    public static abstract class InteractionEntry {
        private boolean enabled = true;
        private Integer cooldownSeconds;

        public boolean isEnabled() {
            return enabled;
        }

        public Integer getCooldownSeconds() {
            return cooldownSeconds;
        }
    }

    public static final class TameInteraction extends InteractionEntry {
        private Boolean consumeItem;
        private Boolean useLovedItems;
        private String[] itemsInHand = ArrayUtil.EMPTY_STRING_ARRAY;
        private String itemsParam;

        public Boolean getConsumeItem() {
            return consumeItem;
        }

        public Boolean getUseLovedItems() {
            return useLovedItems;
        }

        public String[] getItemsInHand() {
            return itemsInHand == null ? ArrayUtil.EMPTY_STRING_ARRAY : itemsInHand;
        }

        public String getItemsParam() {
            return itemsParam;
        }
    }

    public static final class FeedItem {
        private String item;
        private Double heal;

        public FeedItem() {
        }

        public FeedItem(String item, Double heal) {
            this.item = item;
            this.heal = heal;
        }

        public String getItem() {
            return item;
        }

        public Double getHeal() {
            return heal;
        }
    }

    public static final class FeedInteraction extends InteractionEntry {
        private Boolean consumeItem;
        private Boolean useLovedItems;
        private FeedItem[] itemsInHand = EMPTY_FEED_ITEMS;
        private Double heal;
        private String itemsParam;

        public Boolean getConsumeItem() {
            return consumeItem;
        }

        public Boolean getUseLovedItems() {
            return useLovedItems;
        }

        public FeedItem[] getItemsInHand() {
            return itemsInHand == null ? EMPTY_FEED_ITEMS : itemsInHand;
        }

        public Double getHeal() {
            return heal;
        }

        public String getItemsParam() {
            return itemsParam;
        }
    }

    public static final class HarvestInteraction extends InteractionEntry {
        private Boolean requireTamed;
        private Boolean requireHarvestable;
        private Boolean requireHarvestAlarmReady;
        private Boolean requireHarvestInteractionContext;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Boolean getRequireHarvestable() {
            return requireHarvestable;
        }

        public Boolean getRequireHarvestAlarmReady() {
            return requireHarvestAlarmReady;
        }

        public Boolean getRequireHarvestInteractionContext() {
            return requireHarvestInteractionContext;
        }
    }

    public static final class MountInteraction extends InteractionEntry {
        private Boolean requireTamed;
        private Boolean requireOwner;
        private Boolean requireMountable;
        private Boolean requireCrouching;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Boolean getRequireOwner() {
            return requireOwner;
        }

        public Boolean getRequireMountable() {
            return requireMountable;
        }

        public Boolean getRequireCrouching() {
            return requireCrouching;
        }
    }

    public static final class ModeCycleInteraction extends InteractionEntry {
        private Boolean requireTamed;
        private Boolean requireOwner;
        private ModeStep[] cycle = EMPTY_MODE_CYCLE;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Boolean getRequireOwner() {
            return requireOwner;
        }

        public ModeStep[] getCycle() {
            return cycle == null ? EMPTY_MODE_CYCLE : cycle;
        }
    }

    public static final class BreedInteraction extends InteractionEntry {
        private Boolean requireTamed;
        private Double minHappiness;
        private Double fertilityBonus;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Double getMinHappiness() {
            return minHappiness;
        }

        public Double getFertilityBonus() {
            return fertilityBonus;
        }
    }

    public static final class CustomInteraction extends InteractionEntry {
        private RequirementGroup requires;
        private Effects effects;

        public RequirementGroup getRequires() {
            return requires;
        }

        public Effects getEffects() {
            return effects;
        }
    }

    public static final class RequirementGroup {
        private RequirementBucket all = new RequirementBucket();
        private RequirementBucket any = new RequirementBucket();

        public RequirementBucket getAll() {
            return all == null ? new RequirementBucket() : all;
        }

        public RequirementBucket getAny() {
            return any == null ? new RequirementBucket() : any;
        }
    }

    public static final class RequirementBucket {
        private boolean lovedItems;
        private boolean isHarvestable;
        private boolean isMountable;
        private boolean isTamed;
        private boolean isNotTamed;
        private boolean playerCrouching;
        private boolean playerIsOwner;
        private boolean harvestAlarmReady;
        private boolean harvestInteractionContext;
        private ItemsInHandRequirement[] itemsInHand = EMPTY_ITEMS_IN_HAND_REQUIREMENTS;
        private ItemsEquippedRequirement[] itemsEquipped = EMPTY_ITEMS_EQUIPPED_REQUIREMENTS;
        private ParamRequirement[] parameter = EMPTY_PARAM_REQUIREMENTS;
        private AlarmRequirement[] alarmState = EMPTY_ALARM_REQUIREMENTS;
        private StringRequirement[] npcState = EMPTY_STRING_REQUIREMENTS;
        private MovementStateRequirement[] playerMovementState = EMPTY_MOVEMENT_STATE_REQUIREMENTS;
        private InteractionContextRequirement[] interactionContext = EMPTY_CONTEXT_REQUIREMENTS;

        public boolean isLovedItems() {
            return lovedItems;
        }

        public boolean isHarvestable() {
            return isHarvestable;
        }

        public boolean isMountable() {
            return isMountable;
        }

        public boolean isTamed() {
            return isTamed;
        }

        public boolean isNotTamed() {
            return isNotTamed;
        }

        public boolean isPlayerCrouching() {
            return playerCrouching;
        }

        public boolean isPlayerIsOwner() {
            return playerIsOwner;
        }

        public boolean isHarvestAlarmReady() {
            return harvestAlarmReady;
        }

        public boolean isHarvestInteractionContext() {
            return harvestInteractionContext;
        }

        public ItemsInHandRequirement[] getItemsInHand() {
            return itemsInHand == null ? EMPTY_ITEMS_IN_HAND_REQUIREMENTS : itemsInHand;
        }

        public ItemsEquippedRequirement[] getItemsEquipped() {
            return itemsEquipped == null ? EMPTY_ITEMS_EQUIPPED_REQUIREMENTS : itemsEquipped;
        }

        public ParamRequirement[] getParameter() {
            return parameter == null ? EMPTY_PARAM_REQUIREMENTS : parameter;
        }

        public AlarmRequirement[] getAlarmState() {
            return alarmState == null ? EMPTY_ALARM_REQUIREMENTS : alarmState;
        }

        public StringRequirement[] getNpcState() {
            return npcState == null ? EMPTY_STRING_REQUIREMENTS : npcState;
        }

        public MovementStateRequirement[] getPlayerMovementState() {
            return playerMovementState == null ? EMPTY_MOVEMENT_STATE_REQUIREMENTS : playerMovementState;
        }

        public InteractionContextRequirement[] getInteractionContext() {
            return interactionContext == null ? EMPTY_CONTEXT_REQUIREMENTS : interactionContext;
        }

        public boolean isEmpty() {
            return !lovedItems
                    && !isHarvestable
                    && !isMountable
                    && !isTamed
                    && !isNotTamed
                    && !playerCrouching
                    && !playerIsOwner
                    && !harvestAlarmReady
                    && !harvestInteractionContext
                    && getItemsInHand().length == 0
                    && getItemsEquipped().length == 0
                    && getParameter().length == 0
                    && getAlarmState().length == 0
                    && getNpcState().length == 0
                    && getPlayerMovementState().length == 0
                    && getInteractionContext().length == 0;
        }
    }

    public static final class ItemsInHandRequirement {
        private String param;
        private String[] items = ArrayUtil.EMPTY_STRING_ARRAY;

        public String getParam() {
            return param;
        }

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }
    }

    public static final class ItemsEquippedRequirement {
        private String[] items = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] slots = ArrayUtil.EMPTY_STRING_ARRAY;

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }

        public String[] getSlots() {
            return slots == null ? ArrayUtil.EMPTY_STRING_ARRAY : slots;
        }
    }

    public static final class ParamRequirement {
        private String name;
        private ParamOperator operator = ParamOperator.Equals;
        private MatchType match = MatchType.Any;
        private String[] values = ArrayUtil.EMPTY_STRING_ARRAY;

        public String getName() {
            return name;
        }

        public ParamOperator getOperator() {
            return operator == null ? ParamOperator.Equals : operator;
        }

        public MatchType getMatch() {
            return match == null ? MatchType.Any : match;
        }

        public String[] getValues() {
            return values == null ? ArrayUtil.EMPTY_STRING_ARRAY : values;
        }
    }

    public static final class AlarmRequirement {
        private String name;
        private String state;

        public String getName() {
            return name;
        }

        public String getState() {
            return state;
        }
    }

    public static final class StringRequirement {
        private String state;
        private String subState;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }
    }

    public static final class InteractionContextRequirement {
        private String context;
        private String param;

        public String getContext() {
            return context;
        }

        public String getParam() {
            return param;
        }
    }

    public static final class MovementStateRequirement {
        private String state;

        public String getState() {
            return state;
        }
    }

    public static final class Effects {
        private Boolean startTaming;
        private Boolean startBreeding;
        private Boolean applyFeeding;
        private Double feedHeal;
        private Boolean startHarvest;
        private Boolean mount;
        private Boolean toggleMode;
        private Boolean consumeItem;
        private String playSound;
        private String spawnParticles;
        private String dropItem;
        private HookEffect triggerNpcHook;
        private ModeStep[] modeCycle = EMPTY_MODE_CYCLE;

        public Boolean getStartTaming() {
            return startTaming;
        }

        public Boolean getStartBreeding() {
            return startBreeding;
        }

        public Boolean getApplyFeeding() {
            return applyFeeding;
        }

        public Double getFeedHeal() {
            return feedHeal;
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

        public ModeStep[] getModeCycle() {
            return modeCycle == null ? EMPTY_MODE_CYCLE : modeCycle;
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

        public HookEffect getTriggerNpcHook() {
            return triggerNpcHook;
        }
    }

    public static final class HookEffect {
        private String hookId;
        private boolean playerOnly;
        private boolean consume;

        public String getHookId() {
            return hookId;
        }

        public boolean isPlayerOnly() {
            return playerOnly;
        }

        public boolean isConsume() {
            return consume;
        }
    }

    public static final class ModeStep {
        private String state;
        private String subState;
        private String message;

        public ModeStep() {
        }

        public ModeStep(String state, String subState, String message) {
            this.state = state;
            this.subState = subState;
            this.message = message;
        }

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

}
