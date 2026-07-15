package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.common.util.ArrayUtil;

/**
 * Owns the attachment-inheritance codec surface extracted from the broader breeding config.
 */
final class BreedingAttachmentInheritanceCodec {
    private BreedingAttachmentInheritanceCodec() {
    }

    static BuilderCodec<TwBreedingConfig.AttachmentInheritanceSettings> settingsCodec() {
        return BuilderCodec.builder(
                TwBreedingConfig.AttachmentInheritanceSettings.class,
                TwBreedingConfig.AttachmentInheritanceSettings::new
        )
            .<Double>append(
                new KeyedCodec<>("ParentWeight", Codec.DOUBLE),
                (settings, value) -> settings.parentWeight = value,
                settings -> settings.parentWeight
            )
            .documentation("Relative weight for inheriting attachment traits from parents.")
            .add()
            .<Double>append(
                new KeyedCodec<>("RandomWeight", Codec.DOUBLE),
                (settings, value) -> settings.randomWeight = value,
                settings -> settings.randomWeight
            )
            .documentation("Relative weight for selecting random attachment traits.")
            .add()
            .<Double>append(
                new KeyedCodec<>("MutationChance", Codec.DOUBLE),
                (settings, value) -> settings.mutationChance = value,
                settings -> settings.mutationChance
            )
            .documentation("Chance for mutation when generating inherited data.")
            .add()
            .<String[]>append(
                new KeyedCodec<>("ExcludedSets", Codec.STRING_ARRAY),
                (settings, value) -> settings.excludedSets = value == null
                        ? ArrayUtil.EMPTY_STRING_ARRAY
                        : value,
                settings -> settings.excludedSets
            )
            .documentation("Exact model attachment-set IDs excluded from offspring inheritance. Inheritance: "
                    + "omitted value inherits from parent; explicit array replaces parent value, and [] clears it.")
            .add()
            .build();
    }

    static BuilderCodec<TwBreedingConfig.AttachmentInheritanceSettingsOverride> overrideCodec() {
        return BuilderCodec.builder(
                TwBreedingConfig.AttachmentInheritanceSettingsOverride.class,
                TwBreedingConfig.AttachmentInheritanceSettingsOverride::new
        )
            .<Double>append(
                new KeyedCodec<>("ParentWeight", Codec.DOUBLE),
                (settings, value) -> settings.parentWeight = value,
                settings -> settings.parentWeight
            )
            .documentation("Relative weight for inheriting attachment traits from parents.")
            .add()
            .<Double>append(
                new KeyedCodec<>("RandomWeight", Codec.DOUBLE),
                (settings, value) -> settings.randomWeight = value,
                settings -> settings.randomWeight
            )
            .documentation("Relative weight for selecting random attachment traits.")
            .add()
            .<Double>append(
                new KeyedCodec<>("MutationChance", Codec.DOUBLE),
                (settings, value) -> settings.mutationChance = value,
                settings -> settings.mutationChance
            )
            .documentation("Chance for mutation when generating inherited data.")
            .add()
            .<String[]>append(
                new KeyedCodec<>("ExcludedSets", Codec.STRING_ARRAY),
                (settings, value) -> settings.excludedSets = value,
                settings -> settings.excludedSets
            )
            .documentation("Exact model attachment-set IDs excluded for this role. An explicit array replaces the "
                    + "resolved base list, and [] clears it.")
            .add()
            .build();
    }
}
