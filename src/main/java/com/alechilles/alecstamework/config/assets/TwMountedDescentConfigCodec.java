package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.npc.movement.NativeMountedDescentPhysics;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import java.util.HashMap;
import java.util.Map;

/** Codec definitions for native mounted-descent config assets. */
public final class TwMountedDescentConfigCodec {
    private static final BuilderCodec<SettingsDefinition> SETTINGS_CODEC = BuilderCodec.builder(
            SettingsDefinition.class,
            SettingsDefinition::new
    )
            .<Double>append(new KeyedCodec<>("MaxDownwardSpeed", Codec.DOUBLE),
                    (settings, value) -> settings.maxDownwardSpeed = value == null ? 0.0 : value,
                    settings -> settings.maxDownwardSpeed)
            .documentation("Positive maximum downward speed in blocks per second.")
            .add()
            .<Double>append(new KeyedCodec<>("FallAccelerationMultiplier", Codec.DOUBLE),
                    (settings, value) -> settings.fallAccelerationMultiplier = value == null ? 0.0 : value,
                    settings -> settings.fallAccelerationMultiplier)
            .documentation("Positive multiplier applied to base-game gravity while descending.")
            .add()
            .build();

    private static final MapCodec<SettingsDefinition, Map<String, SettingsDefinition>> PROFILES_CODEC =
            new MapCodec<>(SETTINGS_CODEC, HashMap::new);

    public static final AssetBuilderCodec<String, TwMountedDescentConfig> CODEC = AssetBuilderCodec.builder(
            TwMountedDescentConfig.class,
            TwMountedDescentConfig::new,
            Codec.STRING,
            TwMountedDescentConfig::setId,
            TwMountedDescentConfig::getId,
            TwMountedDescentConfig::setData,
            TwMountedDescentConfig::getData
    )
            .documentation("Native mounted-descent overlays keyed by source MovementConfig ID.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    TwMountedDescentConfig::setEnabled,
                    TwMountedDescentConfig::isEnabled)
            .documentation("Turns this descent asset on or off. Omitted value inherits from the parent.")
            .add()
            .<Map<String, SettingsDefinition>>append(new KeyedCodec<>("Profiles", PROFILES_CODEC),
                    (config, value) -> config.setProfiles(toSettings(value)),
                    config -> toDefinitions(config.getProfiles()))
            .documentation("Settings keyed by native MovementConfig ID. Omitted map inherits; an explicit map replaces the parent map.")
            .add()
            .build();

    private TwMountedDescentConfigCodec() {
    }

    private static Map<String, NativeMountedDescentPhysics.Settings> toSettings(
            Map<String, SettingsDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Map.of();
        }
        Map<String, NativeMountedDescentPhysics.Settings> settings = new HashMap<>();
        for (Map.Entry<String, SettingsDefinition> entry : definitions.entrySet()) {
            SettingsDefinition definition = entry.getValue();
            if (definition != null) {
                settings.put(entry.getKey(), new NativeMountedDescentPhysics.Settings(
                        definition.maxDownwardSpeed,
                        definition.fallAccelerationMultiplier
                ));
            }
        }
        return settings;
    }

    private static Map<String, SettingsDefinition> toDefinitions(
            Map<String, NativeMountedDescentPhysics.Settings> settings) {
        if (settings == null || settings.isEmpty()) {
            return Map.of();
        }
        Map<String, SettingsDefinition> definitions = new HashMap<>();
        for (Map.Entry<String, NativeMountedDescentPhysics.Settings> entry : settings.entrySet()) {
            NativeMountedDescentPhysics.Settings value = entry.getValue();
            if (value != null) {
                definitions.put(entry.getKey(), new SettingsDefinition(value));
            }
        }
        return definitions;
    }

    private static final class SettingsDefinition {
        private double maxDownwardSpeed;
        private double fallAccelerationMultiplier;

        private SettingsDefinition() {
        }

        private SettingsDefinition(NativeMountedDescentPhysics.Settings settings) {
            maxDownwardSpeed = settings.maxDownwardSpeed();
            fallAccelerationMultiplier = settings.fallAccelerationMultiplier();
        }
    }
}
