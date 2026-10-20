package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * Full-entity snapshots of companion bodies (spec 6.5). Runs on the body's world thread.
 * Each call builds a fresh top-level document, so a later capture never overwrites a queued
 * one. Data of unregistered third-party components may still share nested documents with the
 * live entity (engine behavior), so treat a snapshot's data as read-only. The readers below
 * expect {@link #FORMAT}; check the envelope's format before calling them.
 */
public final class CompanionSnapshots {
    public static final int FORMAT = 1;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Function<Holder<EntityStore>, BsonDocument> serializer;

    public CompanionSnapshots(@Nonnull Function<Holder<EntityStore>, BsonDocument> serializer) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Nonnull
    public static CompanionSnapshots production() {
        return new CompanionSnapshots(EntityStore.REGISTRY::serialize);
    }

    /**
     * Returns the snapshot, or null when the entity could not be copied or serialized
     * (logged as WARN with the profile id). {@code worldGameTimeMs} is the source world's game
     * time, kept so phase 3 can re-base alarms after a cross-world respawn.
     */
    @Nullable
    public SnapshotEnvelope capture(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                    @Nonnull UUID profileId, long generation,
                                    @Nonnull String worldName, long worldGameTimeMs) {
        try {
            Holder<EntityStore> copy = store.copySerializableEntity(ref);
            BsonDocument entity = serializer.apply(copy);
            BsonDocument data = new BsonDocument("Entity", entity)
                    .append("World", new BsonString(worldName))
                    .append("GameTimeMs", new BsonInt64(worldGameTimeMs));
            return new SnapshotEnvelope(profileId, FORMAT, generation, data);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Companion snapshot failed for profile %s", profileId);
            return null;
        }
    }

    @Nonnull
    public static BsonDocument entity(@Nonnull SnapshotEnvelope envelope) {
        return envelope.data().getDocument("Entity");
    }

    @Nonnull
    public static String world(@Nonnull SnapshotEnvelope envelope) {
        return envelope.data().getString("World").getValue();
    }

    public static long gameTimeMs(@Nonnull SnapshotEnvelope envelope) {
        return envelope.data().get("GameTimeMs").asNumber().longValue();
    }
}
