package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonValue;

/**
 * Pure edits to a serialized companion entity before it is deserialized (spec 6.5). Every method
 * returns a new document and leaves its input unchanged. Component ids match the engine and
 * Tamework registrations: {@code DeathComponent} as "Death", {@code AlarmStore} as "AlarmStore",
 * {@code TameworkNeedsComponent} as "TameworkNeeds", {@code TameworkAlarmComponent} as
 * "TameworkAlarm", {@code TameworkBreedingComponent} as "TameworkBreeding" and
 * {@code TameworkLifeStageComponent} as "TameworkLifeStage".
 */
public final class SnapshotPatch {
    static final String COMPONENTS = "Components";
    static final String DEATH = "Death";
    static final String ALARMS = "AlarmStore";
    static final String NEEDS = "TameworkNeeds";
    static final String TAMEWORK_ALARMS = "TameworkAlarm";
    static final String BREEDING = "TameworkBreeding";
    static final String LIFE_STAGE = "TameworkLifeStage";
    private static final String PARAMETERS = "Parameters";
    private static final String INSTANT = "Instant";
    private static final String[] ALARM_TIMES = {"UntilMs", "StartedAtMs"};
    // ManualBreedingUntilMs is wall clock (ManualBreedingClock) and must not be shifted.
    private static final String[] BREEDING_TIMES = {"CooldownUntilMs", "CooldownStartedAtMs"};

    private SnapshotPatch() {
    }

    public static boolean isDeathSnapshot(@Nonnull BsonDocument entity) {
        return entity.isDocument(COMPONENTS) && entity.getDocument(COMPONENTS).containsKey(DEATH);
    }

    /**
     * Moves every world-game-time value by {@code deltaMs} (destination game time minus source
     * game time): the engine's alarm instants and Tamework's world-time deadlines.
     *
     * <p>Engine instants use {@code Codec.INSTANT} (an ISO-8601 string); an unset or unreadable
     * one is left as it is. Tamework alarm windows and breeding deadlines move only while they
     * run on world game time (see {@code rebaseWorldTimes}).
     */
    @Nonnull
    public static BsonDocument rebaseAlarms(@Nonnull BsonDocument entity, long deltaMs) {
        BsonDocument copy = entity.clone();
        if (deltaMs == 0L || !copy.isDocument(COMPONENTS)) {
            return copy;
        }
        BsonDocument components = copy.getDocument(COMPONENTS);
        if (components.isDocument(ALARMS) && components.getDocument(ALARMS).isDocument(PARAMETERS)) {
            for (Map.Entry<String, BsonValue> alarm : components.getDocument(ALARMS).getDocument(PARAMETERS).entrySet()) {
                if (alarm.getValue().isDocument() && alarm.getValue().asDocument().containsKey(INSTANT)) {
                    rebaseInstant(alarm.getValue().asDocument(), deltaMs);
                }
            }
        }
        rebaseWorldTimes(components, deltaMs);
        return copy;
    }

    private static void rebaseInstant(BsonDocument alarm, long deltaMs) {
        try {
            Instant at = Codec.INSTANT.decode(alarm.get(INSTANT), EmptyExtraInfo.EMPTY);
            alarm.put(INSTANT, Codec.INSTANT.encode(at.plusMillis(deltaMs), EmptyExtraInfo.EMPTY));
        } catch (BsonInvalidOperationException | IllegalArgumentException | DateTimeException
                 | ArithmeticException unreadable) {
            // Skip it: the engine decodes or rejects it the same way it would without a re-base.
        }
    }

    /**
     * Tamework alarm windows and breeding deadlines are compared against
     * {@code AnimalProgressionService.currentTimeMs}. That is the world's game time only while the
     * body has no {@code TameworkLifeStage} with {@code ProgressionInitialized}; once progression is
     * initialized, it is a clock the body carries ({@code LastProgressionWorldMs} plus owner-clock
     * time), which a world change does not move, so those deadlines stay as they are. {@code 0}
     * means unset and stays {@code 0}.
     */
    private static void rebaseWorldTimes(BsonDocument components, long deltaMs) {
        if (carriesOwnClock(components)) {
            return;
        }
        if (components.isDocument(TAMEWORK_ALARMS) && components.getDocument(TAMEWORK_ALARMS).isArray("Alarms")) {
            for (BsonValue alarm : components.getDocument(TAMEWORK_ALARMS).getArray("Alarms")) {
                if (alarm.isDocument()) {
                    shift(alarm.asDocument(), ALARM_TIMES, deltaMs);
                }
            }
        }
        if (components.isDocument(BREEDING)) {
            shift(components.getDocument(BREEDING), BREEDING_TIMES, deltaMs);
        }
    }

    private static boolean carriesOwnClock(BsonDocument components) {
        if (!components.isDocument(LIFE_STAGE)) {
            return false;
        }
        BsonDocument lifeStage = components.getDocument(LIFE_STAGE);
        return lifeStage.isBoolean("ProgressionInitialized") && lifeStage.getBoolean("ProgressionInitialized").getValue();
    }

    private static void shift(BsonDocument doc, String[] keys, long deltaMs) {
        for (String key : keys) {
            if (!doc.isNumber(key)) {
                continue;
            }
            long value = doc.getNumber(key).longValue();
            if (value != 0L) {
                long shifted = BreedingTimeService.saturatingAdd(value, deltaMs);
                // A set deadline must not land on the unset sentinel.
                doc.put(key, new BsonInt64(shifted == 0L ? -1L : shifted));
            }
        }
    }

    /**
     * Removes the death state and the needs that killed it. On add, the progression bootstrap
     * ({@code CompanionProgressionBootstrapOnLoadSystem}) recreates the needs of a tamed body with
     * the config defaults.
     */
    @Nonnull
    public static BsonDocument forRevive(@Nonnull BsonDocument entity) {
        BsonDocument copy = entity.clone();
        if (copy.isDocument(COMPONENTS)) {
            copy.getDocument(COMPONENTS).remove(DEATH);
            copy.getDocument(COMPONENTS).remove(NEEDS);
        }
        return copy;
    }
}
