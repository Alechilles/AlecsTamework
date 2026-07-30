package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.Arrays;
import java.util.List;

/** Codec definitions for companion movement config assets. */
public final class TwCompanionMovementConfigCodec {
    private static final BuilderCodec<AttachmentModifierDefinition> ATTACHMENT_MODIFIER_CODEC = BuilderCodec.builder(
            AttachmentModifierDefinition.class,
            AttachmentModifierDefinition::new
    )
            .<String>append(new KeyedCodec<>("Slot", Codec.STRING),
                    (modifier, value) -> modifier.slot = value,
                    modifier -> modifier.slot)
            .documentation("Attachment slot this movement modifier evaluates.")
            .add()
            .<String[]>append(new KeyedCodec<>("Values", Codec.STRING_ARRAY),
                    (modifier, value) -> modifier.values = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    modifier -> modifier.values)
            .documentation("Attachment values that receive this modifier.")
            .add()
            .<Double>append(new KeyedCodec<>("Multiplier", Codec.DOUBLE),
                    (modifier, value) -> modifier.multiplier = value == null ? 1.0 : value,
                    modifier -> modifier.multiplier)
            .documentation("Movement multiplier applied when this attachment modifier matches. Defaults to 1.0.")
            .add()
            .build();

    private static final ArrayCodec<AttachmentModifierDefinition> ATTACHMENT_MODIFIER_ARRAY_CODEC =
            new ArrayCodec<>(ATTACHMENT_MODIFIER_CODEC, AttachmentModifierDefinition[]::new);

    public static final AssetBuilderCodec<String, TwCompanionMovementConfig> CODEC = AssetBuilderCodec.builder(
            TwCompanionMovementConfig.class,
            TwCompanionMovementConfig::new,
            Codec.STRING,
            TwCompanionMovementConfig::setId,
            TwCompanionMovementConfig::getId,
            TwCompanionMovementConfig::setData,
            TwCompanionMovementConfig::getData
    )
            .documentation("Role-scoped companion movement configuration for Alec's Tamework companions.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    TwCompanionMovementConfig::setEnabled,
                    TwCompanionMovementConfig::isEnabled)
            .documentation("Turns this companion movement config on or off. Inheritance: omitted inherits from the parent.")
            .add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    TwCompanionMovementConfig::setPriority,
                    TwCompanionMovementConfig::getPriority)
            .documentation("Higher priority matching configs win. Inheritance: omitted inherits from the parent.")
            .add()
            .<String[]>append(new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    TwCompanionMovementConfig::setRoleIds,
                    TwCompanionMovementConfig::getRoleIds)
            .documentation("NPC role IDs this config applies to. Inheritance: explicit arrays replace parent values.")
            .add()
            .<Double>append(new KeyedCodec<>("BaseMoveSpeedMultiplier", Codec.DOUBLE),
                    TwCompanionMovementConfig::setBaseMoveSpeedMultiplier,
                    TwCompanionMovementConfig::getBaseMoveSpeedMultiplier)
            .documentation("Base movement multiplier. Inheritance: omitted inherits from the parent.")
            .add()
            .<Double>append(new KeyedCodec<>("MinMoveSpeedMultiplier", Codec.DOUBLE),
                    TwCompanionMovementConfig::setMinMoveSpeedMultiplier,
                    TwCompanionMovementConfig::getMinMoveSpeedMultiplier)
            .documentation("Minimum movement multiplier. Inheritance: omitted inherits from the parent.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxMoveSpeedMultiplier", Codec.DOUBLE),
                    TwCompanionMovementConfig::setMaxMoveSpeedMultiplier,
                    TwCompanionMovementConfig::getMaxMoveSpeedMultiplier)
            .documentation("Maximum movement multiplier. Inheritance: omitted inherits from the parent.")
            .add()
            .<AttachmentModifierDefinition[]>append(new KeyedCodec<>(
                            "AttachmentModifiers", ATTACHMENT_MODIFIER_ARRAY_CODEC),
                    (config, value) -> config.setAttachmentModifiers(toModifiers(value)),
                    config -> toDefinitions(config.getAttachmentModifiers()))
            .documentation("Attachment-specific movement modifiers. Inheritance: explicit arrays replace parent values.")
            .add()
            .build();

    private TwCompanionMovementConfigCodec() {
    }

    private static TwCompanionMovementConfig.AttachmentModifier[] toModifiers(
            AttachmentModifierDefinition[] definitions) {
        if (definitions == null || definitions.length == 0) {
            return new TwCompanionMovementConfig.AttachmentModifier[0];
        }
        return Arrays.stream(definitions)
                .filter(java.util.Objects::nonNull)
                .map(definition -> new TwCompanionMovementConfig.AttachmentModifier(
                        definition.slot,
                        List.of(definition.values == null ? ArrayUtil.EMPTY_STRING_ARRAY : definition.values),
                        definition.multiplier
                ))
                .toArray(TwCompanionMovementConfig.AttachmentModifier[]::new);
    }

    private static AttachmentModifierDefinition[] toDefinitions(
            TwCompanionMovementConfig.AttachmentModifier[] modifiers) {
        if (modifiers == null || modifiers.length == 0) {
            return new AttachmentModifierDefinition[0];
        }
        return Arrays.stream(modifiers)
                .filter(java.util.Objects::nonNull)
                .map(AttachmentModifierDefinition::new)
                .toArray(AttachmentModifierDefinition[]::new);
    }

    private static final class AttachmentModifierDefinition {
        private String slot;
        private String[] values = ArrayUtil.EMPTY_STRING_ARRAY;
        private double multiplier = 1.0;

        private AttachmentModifierDefinition() {
        }

        private AttachmentModifierDefinition(TwCompanionMovementConfig.AttachmentModifier modifier) {
            slot = modifier.slot();
            values = modifier.values().toArray(String[]::new);
            multiplier = modifier.multiplier();
        }
    }
}
