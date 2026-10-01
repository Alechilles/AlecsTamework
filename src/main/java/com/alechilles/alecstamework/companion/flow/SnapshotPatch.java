package com.alechilles.alecstamework.companion.flow;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Pure edits to a serialized companion entity before it is deserialized (spec 6.5). Every method
 * returns a new document and leaves its input unchanged. Component ids match the engine and
 * Tamework registrations: {@code DeathComponent} as "Death", {@code AlarmStore} as "AlarmStore",
 * {@code TameworkNeedsComponent} as "TameworkNeeds".
 */
public final class SnapshotPatch {
    static final String COMPONENTS = "Components";
    static final String DEATH = "Death";
    static final String ALARMS = "AlarmStore";
    static final String NEEDS = "TameworkNeeds";
    private static final String PARAMETERS = "Parameters";
    private static final String INSTANT = "Instant";

    private SnapshotPatch() {
    }

    public static boolean isDeathSnapshot(@Nonnull BsonDocument entity) {
        return entity.isDocument(COMPONENTS) && entity.getDocument(COMPONENTS).containsKey(DEATH);
    }

    /**
     * Moves every alarm instant by {@code deltaMs} (destination game time minus source game time).
     * Instants use the engine's {@code Codec.INSTANT} (an ISO-8601 string); an unset alarm has no
     * instant and an unreadable one is left as it is.
     */
    @Nonnull
    public static BsonDocument rebaseAlarms(@Nonnull BsonDocument entity, long deltaMs) {
        BsonDocument copy = entity.clone();
        if (deltaMs == 0L || !copy.isDocument(COMPONENTS) || !copy.getDocument(COMPONENTS).isDocument(ALARMS)) {
            return copy;
        }
        BsonDocument store = copy.getDocument(COMPONENTS).getDocument(ALARMS);
        if (!store.isDocument(PARAMETERS)) {
            return copy;
        }
        for (Map.Entry<String, BsonValue> alarm : store.getDocument(PARAMETERS).entrySet()) {
            if (alarm.getValue().isDocument() && alarm.getValue().asDocument().isString(INSTANT)) {
                BsonDocument doc = alarm.getValue().asDocument();
                try {
                    Instant at = Codec.INSTANT.decode(doc.get(INSTANT), EmptyExtraInfo.EMPTY);
                    doc.put(INSTANT, Codec.INSTANT.encode(at.plusMillis(deltaMs), EmptyExtraInfo.EMPTY));
                } catch (DateTimeException | ArithmeticException unreadable) {
                    // Leave it: the engine decodes or rejects it the same way it would without a re-base.
                }
            }
        }
        return copy;
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
