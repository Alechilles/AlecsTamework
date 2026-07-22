package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import java.util.Collections;
import javax.annotation.Nullable;

/** Focused asset codec for the capture section of {@link TwSpawnerConfig}. */
final class TwSpawnerCaptureSettingsCodec {
    static final BuilderCodec<TwSpawnerConfig.CaptureSettings> CODEC = BuilderCodec.builder(
            TwSpawnerConfig.CaptureSettings.class, TwSpawnerConfig.CaptureSettings::new)
        .<Boolean>append(new KeyedCodec<>("ClearsOwner", Codec.BOOLEAN),
            (settings, value) -> settings.clearsOwner = value, settings -> settings.clearsOwner)
        .documentation("Clear owner data when capturing.").add()
        .<Boolean>append(new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (settings, value) -> settings.requireTamed = value, settings -> settings.requireTamed)
        .documentation("Require the target NPC to be tamed.").add()
        .<Boolean>append(new KeyedCodec<>("TamesTarget", Codec.BOOLEAN),
            (settings, value) -> settings.tamesTarget = value, settings -> settings.tamesTarget)
        .documentation("Capture an eligible wild NPC as a newly owned tamed companion.").add()
        .<Double>append(new KeyedCodec<>("MaxHealthPercent", Codec.DOUBLE),
            (settings, value) -> settings.maxHealthPercent = value, settings -> settings.maxHealthPercent)
        .documentation("Optional maximum target health percent required for capture.").add()
        .<String>append(new KeyedCodec<>("RequiredEffectId", Codec.STRING),
            (settings, value) -> settings.requiredEffectId = value, settings -> settings.requiredEffectId)
        .documentation("Optional active entity effect required on the target.").add()
        .<String>append(new KeyedCodec<>("ChannelAuraEffectId", Codec.STRING),
            (settings, value) -> settings.channelAuraEffectId = value, settings -> settings.channelAuraEffectId)
        .documentation("Optional temporary entity effect applied while a capture channel is active.").add()
        .<java.util.Map<String, String>>append(
            new KeyedCodec<>("TamedRoleOverrides", MapCodec.STRING_HASH_MAP_CODEC),
            (settings, value) -> settings.tamedRoleOverrides = value == null
                    ? Collections.emptyMap() : value,
            settings -> settings.tamedRoleOverrides)
        .documentation("Source-role to tamed-role mappings used when TamesTarget is enabled.").add()
        .<Boolean>append(new KeyedCodec<>("OwnerRestricted", Codec.BOOLEAN),
            (settings, value) -> settings.ownerRestricted = value, settings -> settings.ownerRestricted)
        .documentation("Restrict capture to the owner.").add()
        .<Boolean>append(new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireOwner = value, settings -> settings.requireOwner)
        .documentation("Require the NPC to have an owner set.").add()
        .<String>append(new KeyedCodec<>("ParticleSystem", Codec.STRING),
            (settings, value) -> settings.particleSystem = value, settings -> settings.particleSystem)
        .documentation("Particle system to play on capture.").add()
        .<String>append(new KeyedCodec<>("SoundEvent", Codec.STRING),
            (settings, value) -> settings.soundEvent = value, settings -> settings.soundEvent)
        .documentation("Sound event to play on capture.").add()
        .<Integer>append(new KeyedCodec<>("CooldownMs", Codec.INTEGER),
            (settings, value) -> settings.cooldownMs = value, settings -> settings.cooldownMs)
        .documentation("Cooldown after capture (milliseconds).").add()
        .<Double>append(new KeyedCodec<>("MaxDistance", Codec.DOUBLE),
            (settings, value) -> settings.maxDistance = value, settings -> settings.maxDistance)
        .documentation("Maximum capture distance.").add()
        .<String>append(new KeyedCodec<>("ChanceMode", Codec.STRING),
            (settings, value) -> settings.chanceMode = parseChanceMode(value),
            settings -> settings.chanceMode == CaptureChanceMode.PROBABILITY
                    ? "Probability" : "Guaranteed")
        .documentation("Guaranteed preserves deterministic legacy capture and bypasses role policies; Probability opts in.").add()
        .<Integer>append(new KeyedCodec<>("Power", Codec.INTEGER),
            (settings, value) -> settings.power = value, settings -> settings.power)
        .documentation("Generic non-negative capture power.").add()
        .<Double>append(new KeyedCodec<>("BaseChance", Codec.DOUBLE),
            (settings, value) -> settings.baseChance = value, settings -> settings.baseChance)
        .documentation("Base success probability in [0,1].").add()
        .<Double>append(new KeyedCodec<>("ChancePerPower", Codec.DOUBLE),
            (settings, value) -> settings.chancePerPower = value, settings -> settings.chancePerPower)
        .documentation("Finite non-negative chance added per power above the target minimum.").add()
        .<Double>append(new KeyedCodec<>("MinimumChance", Codec.DOUBLE),
            (settings, value) -> settings.minimumChance = value, settings -> settings.minimumChance)
        .documentation("Inclusive lower probability clamp in [0,1].").add()
        .<Double>append(new KeyedCodec<>("MaximumChance", Codec.DOUBLE),
            (settings, value) -> settings.maximumChance = value, settings -> settings.maximumChance)
        .documentation("Inclusive upper probability clamp in [0,1], not below MinimumChance.").add()
        .<Integer>append(new KeyedCodec<>("FailureCooldownMs", Codec.INTEGER),
            (settings, value) -> settings.failureCooldownMs = value, settings -> settings.failureCooldownMs)
        .documentation("Non-negative cooldown applied only after a resolved failed probability roll.").add()
        .<String>append(new KeyedCodec<>("FailureParticleSystem", Codec.STRING),
            (settings, value) -> settings.failureParticleSystem = value,
            settings -> settings.failureParticleSystem)
        .documentation("Optional failed-roll particle system.").add()
        .<String>append(new KeyedCodec<>("FailureSoundEvent", Codec.STRING),
            (settings, value) -> settings.failureSoundEvent = value,
            settings -> settings.failureSoundEvent)
        .documentation("Optional failed-roll sound event.").add()
        .build();

    private TwSpawnerCaptureSettingsCodec() {
    }

    private static CaptureChanceMode parseChanceMode(@Nullable String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("Guaranteed")) {
            return CaptureChanceMode.GUARANTEED;
        }
        if (value.equalsIgnoreCase("Probability")) return CaptureChanceMode.PROBABILITY;
        throw new IllegalArgumentException("Unknown capture ChanceMode: " + value);
    }
}
