package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import java.util.Map;

/** Codec definitions for dynamic companion icon config assets. */
public final class TwDynamicIconConfigCodec {
    private static final BuilderCodec<TwDynamicIconConfig.IconOverride> ICON_OVERRIDE_CODEC = BuilderCodec.builder(
            TwDynamicIconConfig.IconOverride.class,
            TwDynamicIconConfig.IconOverride::new
    )
            .<String>append(new KeyedCodec<>("Icon", Codec.STRING),
                    TwDynamicIconConfig.IconOverride::setIcon,
                    TwDynamicIconConfig.IconOverride::getIcon)
            .documentation("PNG icon asset path selected when this override matches.")
            .add()
            .<Map<String, String>>append(new KeyedCodec<>("Attachments", MapCodec.STRING_HASH_MAP_CODEC),
                    TwDynamicIconConfig.IconOverride::setAttachments,
                    TwDynamicIconConfig.IconOverride::getAttachments)
            .documentation("Required attachment key/value pairs. Keys and values are case-sensitive.")
            .add()
            .build();

    private static final ArrayCodec<TwDynamicIconConfig.IconOverride> ICON_OVERRIDE_ARRAY_CODEC =
            new ArrayCodec<>(ICON_OVERRIDE_CODEC, TwDynamicIconConfig.IconOverride[]::new);

    public static final AssetBuilderCodec<String, TwDynamicIconConfig> CODEC = AssetBuilderCodec.builder(
            TwDynamicIconConfig.class,
            TwDynamicIconConfig::new,
            Codec.STRING,
            TwDynamicIconConfig::setId,
            TwDynamicIconConfig::getId,
            TwDynamicIconConfig::setData,
            TwDynamicIconConfig::getData
    )
            .documentation("Role-scoped dynamic companion icon configuration.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    TwDynamicIconConfig::setEnabled,
                    TwDynamicIconConfig::isEnabled)
            .documentation("Turns this dynamic icon config on or off. Inheritance: omitted inherits from the parent.")
            .add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    TwDynamicIconConfig::setPriority,
                    TwDynamicIconConfig::getPriority)
            .documentation("Higher priority matching configs win. Inheritance: omitted inherits from the parent.")
            .add()
            .<String[]>append(new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    TwDynamicIconConfig::setRoleIds,
                    TwDynamicIconConfig::getRoleIds)
            .documentation("NPC role IDs this config applies to. Inheritance: omitted inherits from the parent; explicit arrays replace parent values.")
            .add()
            .<String>append(new KeyedCodec<>("IconDefault", Codec.STRING),
                    TwDynamicIconConfig::setIconDefault,
                    TwDynamicIconConfig::getIconDefault)
            .documentation("Fallback PNG icon path when no attachment override matches. Inheritance: omitted inherits from the parent.")
            .add()
            .<TwDynamicIconConfig.IconOverride[]>append(new KeyedCodec<>("IconOverrides", ICON_OVERRIDE_ARRAY_CODEC),
                    TwDynamicIconConfig::setIconOverrides,
                    TwDynamicIconConfig::getIconOverrides)
            .documentation("Ordered attachment-specific icon overrides. Inheritance: omitted inherits from the parent; explicit arrays replace parent values.")
            .add()
            .build();

    private TwDynamicIconConfigCodec() {
    }
}
