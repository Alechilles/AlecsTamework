package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.server.npc.asset.builder.holder.TemporalArrayHolder;
import com.hypixel.hytale.server.npc.util.Alarm;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.npc.util.expression.ValueType;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves harvest alarm durations and repairs legacy wall-clock harvest timestamps.
 */
public final class HarvestAlarmTimeBasis {
    private static final String DEFAULT_HARVEST_ALARM_NAME = "Harvest_Ready";
    private static final Duration LEGACY_WALL_CLOCK_FUTURE_SKEW = Duration.ofDays(365);

    private HarvestAlarmTimeBasis() {
    }

    public static boolean isHarvestAlarmName(@Nullable String alarmName) {
        if (alarmName == null || alarmName.isBlank()) {
            return false;
        }
        if (DEFAULT_HARVEST_ALARM_NAME.equals(alarmName)) {
            return true;
        }
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestAlarmName() : null;
        return configured != null && !configured.isBlank() && configured.equals(alarmName);
    }

    public static boolean isLegacyWallClockHarvestAlarm(@Nullable String alarmName,
                                                        @Nullable Alarm alarm,
                                                        @Nullable Instant worldNow) {
        if (!isHarvestAlarmName(alarmName) || alarm == null || !alarm.isSet() || worldNow == null) {
            return false;
        }
        Instant alarmInstant = readAlarmInstant(alarm);
        return alarmInstant != null && alarmInstant.isAfter(worldNow.plus(LEGACY_WALL_CLOCK_FUTURE_SKEW));
    }

    public static double resolveHarvestTimeoutSeconds(@Nullable StdScope scope,
                                                      @Nonnull String parameterName,
                                                      @Nonnull DoubleSupplier random) {
        if (scope == null || parameterName.isBlank()) {
            return 0.0;
        }
        try {
            ValueType type = scope.getType(parameterName);
            if (type == ValueType.NUMBER) {
                DoubleSupplier supplier = scope.getNumberSupplier(parameterName);
                double value = supplier != null ? supplier.getAsDouble() : 0.0;
                return sanitizeSeconds(value);
            }
            if (type == ValueType.STRING_ARRAY) {
                String[] raw = scope.getStringArraySupplier(parameterName).get();
                return resolveTemporalRangeSeconds(raw, random);
            }
        } catch (RuntimeException ignored) {
            return 0.0;
        }
        return 0.0;
    }

    static double resolveTemporalRangeSeconds(@Nullable String[] raw, @Nonnull DoubleSupplier random) {
        TemporalAmount[] amounts = TemporalArrayHolder.convertStringToTemporalArray(raw);
        if (amounts == null || amounts.length == 0) {
            return 0.0;
        }
        double min = temporalSeconds(amounts[0]);
        double max = amounts.length > 1 ? temporalSeconds(amounts[1]) : min;
        if (max < min) {
            max = min;
        }
        double spread = max - min;
        double value = spread > 0.0 ? min + (spread * clampRandom(random.getAsDouble())) : min;
        return sanitizeSeconds(value);
    }

    static Instant resolveCooldownUntil(@Nullable Instant now, double cooldownSeconds) {
        Instant base = now != null ? now : Instant.now();
        return base.plusMillis(Math.max(0L, Math.round(sanitizeSeconds(cooldownSeconds) * 1000.0)));
    }

    @Nullable
    public static Instant readAlarmInstant(@Nullable Alarm alarm) {
        if (alarm == null) {
            return null;
        }
        try {
            Field field = Alarm.class.getDeclaredField("alarmInstant");
            field.setAccessible(true);
            Object value = field.get(alarm);
            return value instanceof Instant ? (Instant) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static double temporalSeconds(@Nonnull TemporalAmount amount) {
        try {
            return sanitizeSeconds(Duration.between(Instant.EPOCH, Instant.EPOCH.plus(amount)).toMillis() / 1000.0);
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private static double sanitizeSeconds(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private static double clampRandom(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : ThreadLocalRandom.current().nextDouble();
    }
}
