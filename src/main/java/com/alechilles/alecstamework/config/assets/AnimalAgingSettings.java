package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Optional adult life-cycle tuning for husbandry animals.
 *
 * <p>The durations are real minutes of eligible progression. They deliberately
 * do not use the world's day-length scale.</p>
 */
public final class AnimalAgingSettings {
    private static final int DEFAULT_ADULT_TO_PRIME_MINUTES = 60;
    private static final int DEFAULT_PRIME_MINUTES = 720;
    private static final int DEFAULT_SENIOR_MINUTES = 720;
    private static final double DEFAULT_NON_PRIME_YIELD_MULTIPLIER = 0.5;

    public static final BuilderCodec<AnimalAgingSettings> CODEC = BuilderCodec.builder(
            AnimalAgingSettings.class,
            AnimalAgingSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value != null && value,
            settings -> settings.enabled
        )
        .documentation("Enables adult aging for this breeding profile. Defaults to false so existing profiles retain adult-only behavior. Inheritance: missing nested key inherits parent value.")
        .add()
        .<String>append(
            new KeyedCodec<>("Mode", Codec.STRING),
            (settings, value) -> settings.mode = LifecycleMode.fromConfigValue(value),
            settings -> settings.getMode().toConfigValue()
        )
        .documentation("Adult aging mode: Off, FreezeAtPrime, or Full. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("AdultToPrimeMinutes", Codec.INTEGER),
            (settings, value) -> settings.adultToPrimeMinutes = positiveOrDefault(
                    value,
                    DEFAULT_ADULT_TO_PRIME_MINUTES
            ),
            settings -> settings.adultToPrimeMinutes
        )
        .documentation("Eligible real minutes from adult to prime. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("PrimeMinutes", Codec.INTEGER),
            (settings, value) -> settings.primeMinutes = positiveOrDefault(value, DEFAULT_PRIME_MINUTES),
            settings -> settings.primeMinutes
        )
        .documentation("Eligible real minutes spent in prime before senior age. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("SeniorMinutes", Codec.INTEGER),
            (settings, value) -> settings.seniorMinutes = positiveOrDefault(value, DEFAULT_SENIOR_MINUTES),
            settings -> settings.seniorMinutes
        )
        .documentation("Eligible real minutes in senior age before optional old-age death. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("OldAgeDeathEnabled", Codec.BOOLEAN),
            (settings, value) -> settings.oldAgeDeathEnabled = value != null && value,
            settings -> settings.oldAgeDeathEnabled
        )
        .documentation("Whether a Full lifecycle ends in old-age death after SeniorMinutes. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("NonPrimeYieldMultiplier", Codec.DOUBLE),
            (settings, value) -> settings.nonPrimeYieldMultiplier = clamp01OrDefault(
                    value,
                    DEFAULT_NON_PRIME_YIELD_MULTIPLIER
            ),
            settings -> settings.nonPrimeYieldMultiplier
        )
        .documentation("Slaughter yield multiplier outside prime age. Inheritance: missing nested key inherits parent value.")
        .add()
        .build();

    /** Partial role-level patch applied to the resolved root aging settings. */
    public static final BuilderCodec<Override> OVERRIDE_CODEC = BuilderCodec.builder(
            Override.class,
            Override::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value,
            settings -> settings.enabled
        )
        .documentation("Whether adult aging is enabled for this role.")
        .add()
        .<String>append(
            new KeyedCodec<>("Mode", Codec.STRING),
            (settings, value) -> settings.mode = value == null ? null : LifecycleMode.fromConfigValue(value),
            settings -> settings.mode == null ? null : settings.mode.toConfigValue()
        )
        .documentation("Adult aging mode for this role: Off, FreezeAtPrime, or Full.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("AdultToPrimeMinutes", Codec.INTEGER),
            (settings, value) -> settings.adultToPrimeMinutes = value,
            settings -> settings.adultToPrimeMinutes
        )
        .documentation("Eligible real minutes from adult to prime for this role.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("PrimeMinutes", Codec.INTEGER),
            (settings, value) -> settings.primeMinutes = value,
            settings -> settings.primeMinutes
        )
        .documentation("Eligible real minutes spent in prime for this role.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("SeniorMinutes", Codec.INTEGER),
            (settings, value) -> settings.seniorMinutes = value,
            settings -> settings.seniorMinutes
        )
        .documentation("Eligible real minutes spent in senior age for this role.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("OldAgeDeathEnabled", Codec.BOOLEAN),
            (settings, value) -> settings.oldAgeDeathEnabled = value,
            settings -> settings.oldAgeDeathEnabled
        )
        .documentation("Whether this role can die of old age in a Full lifecycle.")
        .add()
        .<Double>append(
            new KeyedCodec<>("NonPrimeYieldMultiplier", Codec.DOUBLE),
            (settings, value) -> settings.nonPrimeYieldMultiplier = value,
            settings -> settings.nonPrimeYieldMultiplier
        )
        .documentation("Slaughter yield multiplier outside prime age for this role.")
        .add()
        .build();

    private boolean enabled;
    private LifecycleMode mode = LifecycleMode.FREEZE_AT_PRIME;
    private int adultToPrimeMinutes = DEFAULT_ADULT_TO_PRIME_MINUTES;
    private int primeMinutes = DEFAULT_PRIME_MINUTES;
    private int seniorMinutes = DEFAULT_SENIOR_MINUTES;
    private boolean oldAgeDeathEnabled;
    private double nonPrimeYieldMultiplier = DEFAULT_NON_PRIME_YIELD_MULTIPLIER;

    public void inheritMissingFrom(@Nonnull AnimalAgingSettings parent, @Nullable Set<String> explicitKeys) {
        if (explicitKeys == null) {
            return;
        }
        if (!explicitKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitKeys.contains("Mode")) mode = parent.mode;
        if (!explicitKeys.contains("AdultToPrimeMinutes")) adultToPrimeMinutes = parent.adultToPrimeMinutes;
        if (!explicitKeys.contains("PrimeMinutes")) primeMinutes = parent.primeMinutes;
        if (!explicitKeys.contains("SeniorMinutes")) seniorMinutes = parent.seniorMinutes;
        if (!explicitKeys.contains("OldAgeDeathEnabled")) oldAgeDeathEnabled = parent.oldAgeDeathEnabled;
        if (!explicitKeys.contains("NonPrimeYieldMultiplier")) {
            nonPrimeYieldMultiplier = parent.nonPrimeYieldMultiplier;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LifecycleMode getMode() {
        return mode == null ? LifecycleMode.FREEZE_AT_PRIME : mode;
    }

    public long getAdultToPrimeMs() {
        return minutesToMs(getAdultToPrimeMinutes());
    }

    public int getAdultToPrimeMinutes() {
        return positiveOrDefault(adultToPrimeMinutes, DEFAULT_ADULT_TO_PRIME_MINUTES);
    }

    public long getPrimeMs() {
        return minutesToMs(getPrimeMinutes());
    }

    public int getPrimeMinutes() {
        return positiveOrDefault(primeMinutes, DEFAULT_PRIME_MINUTES);
    }

    public long getSeniorMs() {
        return minutesToMs(getSeniorMinutes());
    }

    public int getSeniorMinutes() {
        return positiveOrDefault(seniorMinutes, DEFAULT_SENIOR_MINUTES);
    }

    public boolean isOldAgeDeathEnabled() {
        return oldAgeDeathEnabled;
    }

    public double getNonPrimeYieldMultiplier() {
        return clamp01OrDefault(nonPrimeYieldMultiplier, DEFAULT_NON_PRIME_YIELD_MULTIPLIER);
    }

    /** Returns a copy whose behavior is governed by the global runtime policy. */
    @Nonnull
    public AnimalAgingSettings withRuntimePolicy(@Nonnull LifecycleMode mode, boolean oldAgeDeathEnabled) {
        AnimalAgingSettings copy = copy();
        copy.mode = mode == null ? LifecycleMode.FREEZE_AT_PRIME : mode;
        copy.oldAgeDeathEnabled = oldAgeDeathEnabled;
        return copy;
    }

    @Nonnull
    AnimalAgingSettings copy() {
        AnimalAgingSettings copy = new AnimalAgingSettings();
        copy.enabled = enabled;
        copy.mode = mode;
        copy.adultToPrimeMinutes = adultToPrimeMinutes;
        copy.primeMinutes = primeMinutes;
        copy.seniorMinutes = seniorMinutes;
        copy.oldAgeDeathEnabled = oldAgeDeathEnabled;
        copy.nonPrimeYieldMultiplier = nonPrimeYieldMultiplier;
        return copy;
    }

    private static int positiveOrDefault(@Nullable Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static double clamp01OrDefault(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(0.0, Math.min(1.0, resolved));
    }

    private static long minutesToMs(int minutes) {
        long safeMinutes = Math.max(1L, minutes);
        return safeMinutes > Long.MAX_VALUE / 60_000L ? Long.MAX_VALUE : safeMinutes * 60_000L;
    }

    public enum LifecycleMode {
        OFF("Off"),
        FREEZE_AT_PRIME("FreezeAtPrime"),
        FULL("Full");

        private final String configValue;

        LifecycleMode(String configValue) {
            this.configValue = configValue;
        }

        public static LifecycleMode fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return FREEZE_AT_PRIME;
            }
            String normalized = value.trim();
            for (LifecycleMode candidate : values()) {
                if (candidate.configValue.equalsIgnoreCase(normalized)
                        || candidate.name().equalsIgnoreCase(normalized)) {
                    return candidate;
                }
            }
            return FREEZE_AT_PRIME;
        }

        public String toConfigValue() {
            return configValue;
        }
    }

    /** Nullable values preserve which role override keys were actually authored. */
    public static final class Override {
        private Boolean enabled;
        private LifecycleMode mode;
        private Integer adultToPrimeMinutes;
        private Integer primeMinutes;
        private Integer seniorMinutes;
        private Boolean oldAgeDeathEnabled;
        private Double nonPrimeYieldMultiplier;

        void applyTo(@Nonnull AnimalAgingSettings target) {
            if (enabled != null) target.enabled = enabled;
            if (mode != null) target.mode = mode;
            if (adultToPrimeMinutes != null) target.adultToPrimeMinutes = adultToPrimeMinutes;
            if (primeMinutes != null) target.primeMinutes = primeMinutes;
            if (seniorMinutes != null) target.seniorMinutes = seniorMinutes;
            if (oldAgeDeathEnabled != null) target.oldAgeDeathEnabled = oldAgeDeathEnabled;
            if (nonPrimeYieldMultiplier != null) target.nonPrimeYieldMultiplier = nonPrimeYieldMultiplier;
        }
    }
}
