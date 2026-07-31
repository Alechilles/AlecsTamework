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
            .documentation("Fallback glyph text shown when no GlyphTexturePath is configured.")
            .add()
            .<String>append(new KeyedCodec<>("GlyphTexturePath", Codec.STRING),
                    (settings, value) -> settings.glyphTexturePath = normalized(value),
                    settings -> settings.glyphTexturePath)
            .documentation("Optional UI texture path for this glyph. It can display the HUD control without fallback text. "
                    + "Blank uses a bundled glyph texture or text fallback. "
                    + "CombatAbilities is a map: an explicit map replaces the parent map; an omitted map inherits it.")
            .add()
            .build();

    private String rootInteraction = "";
    private String glyph = "";
    private String glyphTexturePath = "";

    @Nonnull
    public String getRootInteraction() { return normalized(rootInteraction); }

    @Nonnull
    public String getGlyph() { return normalized(glyph); }

    /** Returns the optional per-avatar UI texture path for this combat glyph. */
    @Nonnull
    public String getGlyphTexturePath() { return normalized(glyphTexturePath); }

    public boolean isConfigured() { return !getRootInteraction().isEmpty(); }

    @Nonnull
    private static String normalized(@Nullable String value) { return value == null ? "" : value.trim(); }
}
