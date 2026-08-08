package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.math.codec.Vector3dArrayCodec;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;
import org.bson.BsonNull;
import org.bson.BsonValue;
import org.joml.Vector3d;

/**
 * Focused codecs for the command-item role filters, command entries, and execution steps.
 *
 * <p>The asset root owns inheritance and top-level settings. Keeping the nested command model
 * codecs here prevents that root from also becoming the implementation home for every command
 * step subtype.</p>
 */
final class TwCommandItemCodecs {
    private static final Codec<String> PARTICLE_SYSTEM_CODEC =
            assetRefCodec("ParticleSystem");
    private static final Codec<String> SOUND_EVENT_CODEC =
            assetRefCodec("SoundEvent");
    private static final Codec<Vector3d> VECTOR3D_CODEC =
            new Vector3dArrayCodec();

    static final Codec<TwCommandItemConfig.RosterStorage>
            ROSTER_STORAGE_CODEC = new TwSilentCodec<>() {
                @Override
                public TwCommandItemConfig.RosterStorage decode(
                        @Nonnull BsonValue value,
                        ExtraInfo extraInfo
                ) {
                    return TwCommandItemConfig.RosterStorage.fromString(
                            TwCodecLenient.asStringOrNull(value)
                    );
                }

                @Override
                public BsonValue encode(
                        TwCommandItemConfig.RosterStorage value,
                        ExtraInfo extraInfo
                ) {
                    TwCommandItemConfig.RosterStorage storage = value == null
                            ? TwCommandItemConfig.RosterStorage.ItemMetadata
                            : value;
                    return Codec.STRING.encode(storage.name(), extraInfo);
                }

                @Nonnull
                @Override
                public Schema toSchema(@Nonnull SchemaContext context) {
                    StringSchema schema = new StringSchema();
                    schema.setEnum(new String[] {
                            "ItemMetadata",
                            "OwnerCommandFamily",
                            "BondedCompanions"
                    });
                    return schema;
                }
            };

    static final StringCodecMapCodec<
            TwCommandItemConfig.AllowedRoles,
            BuilderCodec<? extends TwCommandItemConfig.AllowedRoles>>
            ALLOWED_ROLES_CODEC = TwCommandRoleCodecs.CODEC;

    static final BuilderCodec<TwCommandItemConfig.ModeMapping>
            MODE_MAPPING_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.ModeMapping.class,
                    TwCommandItemConfig.ModeMapping::new
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

    private static final BuilderCodec<TwCommandItemConfig.CommandStep>
            COMMAND_STEP_BASE_CODEC = BuilderCodec.abstractBuilder(
                    TwCommandItemConfig.CommandStep.class
            )
            .<String>append(
                    new KeyedCodec<>("FailurePolicy", Codec.STRING),
                    (step, value) -> step.failurePolicy =
                            TwCommandItemConfig.FailurePolicy.fromString(value),
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

    private static final BuilderCodec<TwCommandItemConfig.SetStateStep>
            SET_STATE_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.SetStateStep.class,
                    TwCommandItemConfig.SetStateStep::new,
                    COMMAND_STEP_BASE_CODEC
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

    private static final BuilderCodec<TwCommandItemConfig.SetTargetStep>
            SET_TARGET_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.SetTargetStep.class,
                    TwCommandItemConfig.SetTargetStep::new,
                    COMMAND_STEP_BASE_CODEC
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
                    (step, value) -> step.source =
                            TwCommandItemConfig.TargetSource.fromString(value),
                    step -> step.source.name()
            )
            .documentation("Source channel used by this operation.")
            .add()
            .build();

    private static final BuilderCodec<TwCommandItemConfig.ClearTargetStep>
            CLEAR_TARGET_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.ClearTargetStep.class,
                    TwCommandItemConfig.ClearTargetStep::new,
                    COMMAND_STEP_BASE_CODEC
            )
            .<String>append(
                    new KeyedCodec<>("TargetSlot", Codec.STRING),
                    (step, value) -> step.targetSlot = value,
                    step -> step.targetSlot
            )
            .documentation("Target slot index used by this step.")
            .add()
            .build();

    private static final BuilderCodec<TwCommandItemConfig.ClearCombatStep>
            CLEAR_COMBAT_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.ClearCombatStep.class,
                    TwCommandItemConfig.ClearCombatStep::new,
                    COMMAND_STEP_BASE_CODEC
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
                    (step, value) -> step.targetSlots = value == null
                            ? ArrayUtil.EMPTY_STRING_ARRAY
                            : value,
                    step -> step.targetSlots
            )
            .documentation("Target slot indexes used by this step.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>(
                            "AssignOwnerAsMasterTarget",
                            Codec.BOOLEAN
                    ),
                    (step, value) ->
                            step.assignOwnerAsMasterTarget =
                                    value == null || value,
                    step -> step.assignOwnerAsMasterTarget
            )
            .documentation("Assigns the owner as master target when executing this step.")
            .add()
            .build();

    private static final BuilderCodec<TwCommandItemConfig.MoveToPositionStep>
            MOVE_TO_POSITION_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.MoveToPositionStep.class,
                    TwCommandItemConfig.MoveToPositionStep::new,
                    COMMAND_STEP_BASE_CODEC
            )
            .<String>append(
                    new KeyedCodec<>("Source", Codec.STRING),
                    (step, value) -> step.source =
                            TwCommandItemConfig.MoveSource.fromString(value),
                    step -> step.source.name()
            )
            .documentation("Source channel used by this operation.")
            .add()
            .build();

    private static final BuilderCodec<TwCommandItemConfig.StoreHomeStep>
            STORE_HOME_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.StoreHomeStep.class,
                    TwCommandItemConfig.StoreHomeStep::new,
                    COMMAND_STEP_BASE_CODEC
            )
            .<String>append(
                    new KeyedCodec<>("Source", Codec.STRING),
                    (step, value) -> step.source =
                            TwCommandItemConfig.StoreSource.fromString(value),
                    step -> step.source.name()
            )
            .documentation("Source channel used by this operation.")
            .add()
            .build();

    private static final BuilderCodec<TwCommandItemConfig.TriggerHookStep>
            TRIGGER_HOOK_STEP_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.TriggerHookStep.class,
                    TwCommandItemConfig.TriggerHookStep::new,
                    COMMAND_STEP_BASE_CODEC
            )
            .<String>append(
                    new KeyedCodec<>("HookId", Codec.STRING),
                    (step, value) -> step.hookId = value,
                    step -> step.hookId
            )
            .documentation("Hook identifier invoked by this step.")
            .add()
            .<Map<String, String>>append(
                    new KeyedCodec<>(
                            "Payload",
                            MapCodec.STRING_HASH_MAP_CODEC
                    ),
                    (step, value) -> step.payload = value == null
                            ? Collections.emptyMap()
                            : value,
                    step -> step.payload
            )
            .documentation("Custom payload value passed into this operation.")
            .add()
            .build();

    static final StringCodecMapCodec<
            TwCommandItemConfig.CommandStep,
            BuilderCodec<? extends TwCommandItemConfig.CommandStep>>
            COMMAND_STEP_CODEC = new StringCodecMapCodec<>("Type") {
            };

    static final ArrayCodec<TwCommandItemConfig.CommandStep>
            COMMAND_STEP_ARRAY_CODEC = new ArrayCodec<>(
                    COMMAND_STEP_CODEC,
                    TwCommandItemConfig.CommandStep[]::new
            );

    static final BuilderCodec<TwCommandItemConfig.CommandFeedback>
            COMMAND_FEEDBACK_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.CommandFeedback.class,
                    TwCommandItemConfig.CommandFeedback::new
            )
            .<String>append(
                    new KeyedCodec<>("ChatMessage", Codec.STRING),
                    (feedback, value) -> feedback.chatMessage = value,
                    feedback -> feedback.chatMessage
            )
            .documentation("Chat message sent when this step executes. May be raw text or a server.lang key.")
            .add()
            .<String>append(
                    new KeyedCodec<>("HudMessage", Codec.STRING),
                    (feedback, value) -> feedback.hudMessage = value,
                    feedback -> feedback.hudMessage
            )
            .documentation("HUD message shown when this step executes. May be raw text or a server.lang key.")
            .add()
            .<String>append(
                    new KeyedCodec<>("SoundEvent", SOUND_EVENT_CODEC),
                    (feedback, value) -> feedback.soundEvent = value,
                    feedback -> feedback.soundEvent
            )
            .documentation("Sound event played when this step executes.")
            .add()
            .<String>append(
                    new KeyedCodec<>(
                            "ParticleSystem",
                            PARTICLE_SYSTEM_CODEC
                    ),
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

    static final BuilderCodec<TwCommandItemConfig.CommandEntry>
            COMMAND_ENTRY_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.CommandEntry.class,
                    TwCommandItemConfig.CommandEntry::new
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
            .documentation("Display name shown to players. May be raw text or a server.lang key.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Icon", Codec.STRING),
                    (entry, value) -> entry.icon = value,
                    entry -> entry.icon
            )
            .documentation("Icon asset path shown for this entry.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("ShowInRadial", Codec.BOOLEAN),
                    (entry, value) -> entry.showInRadial = value == null || value,
                    entry -> entry.showInRadial
            )
            .documentation("Whether this command consumes one of the eight radial-menu slots. "
                    + "Omission defaults true; false keeps it available for hotswaps only.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Default", Codec.BOOLEAN),
                    (entry, value) ->
                            entry.defaultCommand = value != null && value,
                    entry -> entry.defaultCommand
            )
            .documentation("Default setting when no override is set.")
            .add()
            .<TwCommandItemConfig.CommandFeedback>append(
                    new KeyedCodec<>(
                            "Feedback",
                            COMMAND_FEEDBACK_CODEC
                    ),
                    (entry, value) -> entry.feedback = value,
                    entry -> entry.feedback
            )
            .documentation("User feedback settings for this command interaction.")
            .add()
            .<TwCommandItemConfig.ModeMapping>append(
                    new KeyedCodec<>("ModeMapping", MODE_MAPPING_CODEC),
                    (entry, value) -> entry.modeMapping = value,
                    entry -> entry.modeMapping
            )
            .documentation("Maps command modes to behavior modes.")
            .add()
            .<TwCommandItemConfig.CommandStep[]>append(
                    new KeyedCodec<>("Steps", COMMAND_STEP_ARRAY_CODEC),
                    (entry, value) -> entry.steps = value == null
                            ? TwCommandItemConfig.EMPTY_STEPS
                            : value,
                    entry -> entry.steps
            )
            .documentation("Ordered list of command step definitions.")
            .add()
            .build();

    static final ArrayCodec<TwCommandItemConfig.CommandEntry>
            COMMAND_ENTRY_ARRAY_CODEC = new ArrayCodec<>(
                    COMMAND_ENTRY_CODEC,
                    TwCommandItemConfig.CommandEntry[]::new
            );

    static {
        COMMAND_STEP_CODEC.register(
                "SetState",
                TwCommandItemConfig.SetStateStep.class,
                SET_STATE_STEP_CODEC
        );
        COMMAND_STEP_CODEC.register(
                "SetTarget",
                TwCommandItemConfig.SetTargetStep.class,
                SET_TARGET_STEP_CODEC
        );
        COMMAND_STEP_CODEC.register(
                "ClearTarget",
                TwCommandItemConfig.ClearTargetStep.class,
                CLEAR_TARGET_STEP_CODEC
        );
        COMMAND_STEP_CODEC.register(
                "ClearCombat",
                TwCommandItemConfig.ClearCombatStep.class,
                CLEAR_COMBAT_STEP_CODEC
        );
        COMMAND_STEP_CODEC.register(
                "MoveToPosition",
                TwCommandItemConfig.MoveToPositionStep.class,
                MOVE_TO_POSITION_STEP_CODEC
        );
        COMMAND_STEP_CODEC.register(
                "StoreHome",
                TwCommandItemConfig.StoreHomeStep.class,
                STORE_HOME_STEP_CODEC
        );
        COMMAND_STEP_CODEC.register(
                "TriggerHook",
                TwCommandItemConfig.TriggerHookStep.class,
                TRIGGER_HOOK_STEP_CODEC
        );
    }

    private TwCommandItemCodecs() {
    }

    private static Codec<String> assetRefCodec(String assetType) {
        return new TwSilentCodec<>() {
            @Override
            public String decode(
                    @Nonnull BsonValue bsonValue,
                    ExtraInfo extraInfo
            ) {
                return TwCodecLenient.asStringOrNull(bsonValue);
            }

            @Override
            public BsonValue encode(String value, ExtraInfo extraInfo) {
                return value == null
                        ? new BsonNull()
                        : Codec.STRING.encode(value, extraInfo);
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
}
