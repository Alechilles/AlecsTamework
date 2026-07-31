package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable-facing configuration for one avatar-flight combat ability interaction. */
public final class AvatarFlightCombatAbilitySettings {
    public static final BuilderCodec<AvatarFlightCombatAbilitySettings> CODEC = BuilderCodec.builder(
            AvatarFlightCombatAbilitySettings.class,
            AvatarFlightCombatAbilitySettings::new
    )
            .<String>append(new KeyedCodec<>("RootInteraction", Codec.STRING),
                    (settings, value) -> settings.rootInteraction = normalized(value),
                    settings -> settings.rootInteraction)
            .documentation("Root interaction to execute for this ability. Blank or omitted values disable the ability.")
            .add()
            .<String>append(new KeyedCodec<>("Glyph", Codec.STRING),
                    (settings, value) -> settings.glyph = normalized(value),
                    settings -> settings.glyph)
            .documentation("Glyph name shown for this ability.")
            .add()
            .build();

    private String rootInteraction = "";
    private String glyph = "";

    @Nonnull
    public String getRootInteraction() { return normalized(rootInteraction); }

    @Nonnull
    public String getGlyph() { return normalized(glyph); }

    public boolean isConfigured() { return !getRootInteraction().isEmpty(); }

    @Nonnull
    private static String normalized(@Nullable String value) { return value == null ? "" : value.trim(); }
}
