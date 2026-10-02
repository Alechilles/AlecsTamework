package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * The decisions of the saved-chunk pass (plan 7 task 13): what a body found in a chunk on disk
 * means for the imported companion index. No engine types and no file access; the pass
 * ({@link LegacyBodyLocator}) hands in plain {@link SavedEntity} values.
 *
 * <p>4.x stored no position, and often no state, for a companion in an unloaded chunk. Such a
 * record is imported LIVE at exactly 0,0,0 in a guessed world with an empty or old snapshot
 * ({@link CompanionRecord#neverSighted()}). A saved body whose NPC UUID is that record's own alias
 * gives it the real world, position and summary, and a format 1 snapshot of the saved entity, so a
 * recall or recover restores the real animal. The saved chunk is not changed: when it really
 * loads, {@link LegacyBodyResolution} stamps the body as before.</p>
 *
 * <p>The copy of a chunk on disk can be older than the loaded chunk. So a record is only filled
 * while it is still exactly as the import left it and no body is registered for it, and that is
 * checked again under the index lock with the update. Anything a live sighting, a recall or a
 * recover did in between wins, and a second pass over the same chunk changes nothing.</p>
 */
public final class LegacyBodyLocate {
    /**
     * One entity saved in a chunk on disk.
     *
     * @param world  the name of the world whose storage holds the chunk
     * @param entity gives the serialized entity, the document a format 1 snapshot holds. It is
     *               only asked for when the body matches a record that still needs it, so the
     *               other entities of a chunk are never serialized.
     */
    public record SavedEntity(@Nonnull UUID npcUuid, @Nonnull String world, double x, double y, double z,
                              @Nonnull Supplier<BsonDocument> entity) {
        public SavedEntity {
            Objects.requireNonNull(npcUuid, "npcUuid");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(entity, "entity");
        }
    }

    /** What one saved entity was to the index. */
    public enum Outcome {
        /** Its record was filled with the body's place and state. */
        LOCATED,
        /** A leftover body of a companion (stale alias). Only counted; it is removed when its chunk loads. */
        STALE,
        /** No import knows this entity, or its record no longer needs it. */
        IGNORED
    }

    private final CompanionIndex index;
    private final LegacyAliases aliases;
    private final Predicate<UUID> hasBody;
    private final Consumer<SnapshotEnvelope> queueSnapshot;
    private final LongSupplier clock;

    /**
     * @param hasBody       true while a usable body is registered for a profile id in the
     *                      loaded-bodies registry; any thread
     * @param queueSnapshot the writer's snapshot queue; called under the index lock, so it must not block
     * @param clock         wall clock, for the snapshot time
     */
    public LegacyBodyLocate(@Nonnull CompanionIndex index, @Nonnull LegacyAliases aliases,
                            @Nonnull Predicate<UUID> hasBody, @Nonnull Consumer<SnapshotEnvelope> queueSnapshot,
                            @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.aliases = Objects.requireNonNull(aliases, "aliases");
        this.hasBody = Objects.requireNonNull(hasBody, "hasBody");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** True on a world that was imported from 3.x or 4.x; the pass never runs otherwise. */
    public boolean imported() {
        return aliases.size() > 0;
    }

    /** How many records are still never sighted. The pass goes on while this is above 0. */
    public int remaining() {
        int[] count = new int[1];
        index.forEach(record -> {
            if (record.neverSighted()) {
                count[0]++;
            }
        });
        return count[0];
    }

    /**
     * Applies one saved entity. Cheap for the usual entity no import knows: one map lookup. Any
     * thread except a world thread (the entity supplier may serialize).
     */
    @Nonnull
    public Outcome apply(@Nonnull SavedEntity saved) {
        LegacyAliases.Entry alias = aliases.byNpcUuid(saved.npcUuid()).orElse(null);
        if (alias == null) {
            return Outcome.IGNORED;
        }
        if (alias.kind() == LegacyAliases.Kind.STALE) {
            return Outcome.STALE;
        }
        UUID profileId = alias.profileId();
        CompanionRecord seen = index.get(profileId);
        if (seen == null || !awaits(seen, alias, saved.npcUuid())) {
            return Outcome.IGNORED;
        }
        // Serializing and decoding happen outside the index lock; the fence is checked again inside.
        BsonDocument entity;
        try {
            entity = saved.entity().get();
        } catch (RuntimeException | LinkageError unreadable) {
            return Outcome.IGNORED;
        }
        if (entity == null) {
            return Outcome.IGNORED;
        }
        long now = Math.max(1L, clock.getAsLong());
        LegacyState.Checkpoint body = LegacyState.checkpoint(entity, saved.world(), saved.x(), saved.y(), saved.z(), now);
        return index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record == null || !awaits(record, alias, saved.npcUuid())) {
                return Outcome.IGNORED;
            }
            // Queued before the record changes, as the importer and the item adoption do: a stop
            // right after keeps the state the record then points at.
            queueSnapshot.accept(new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT, record.generation(),
                    new BsonDocument("Entity", body.entity())
                            .append("World", new BsonString(saved.world()))
                            .append("GameTimeMs", new BsonInt64(0L))));
            String bodyRole = body.summary().roleId();
            boolean guessedHome = record.homeWorld() == null || record.homeWorld().isBlank() || record.neverSighted();
            boolean applied = index.update(profileId, record.revision(), b -> {
                b.location(CompanionLocation.live(saved.world(), saved.x(), saved.y(), saved.z()))
                        .currentNpcUuid(saved.npcUuid())
                        .summary(body.summary())
                        .lastSnapshotAtMs(now);
                // Mounting and avatar-flight parking swap a body's role to Empty_Role; keep the real one.
                if (bodyRole != null && !bodyRole.isBlank() && !"Empty_Role".equalsIgnoreCase(bodyRole.trim())) {
                    b.roleId(bodyRole.trim());
                }
                if (record.displayName() == null && body.customName() != null) {
                    b.displayName(body.customName());
                }
                return guessedHome ? b.homeWorld(saved.world()) : b;
            }).applied();
            return applied ? Outcome.LOCATED : Outcome.IGNORED;
        });
    }

    /**
     * After every world has been read: a record that is still never sighted has no body on disk.
     * It becomes LOST with {@link LegacyBodyResolution#CAUSE_BODY_NOT_FOUND}, keeping its snapshot
     * and its generation 0, so its owner can recover it and its body still rejoins it if it turns
     * up in a world that was not read. A record whose body is registered is left alone.
     *
     * @return the profile ids moved to LOST
     */
    @Nonnull
    public List<UUID> markNotFound() {
        List<UUID> candidates = new ArrayList<>();
        index.forEach(record -> {
            if (record.neverSighted()) {
                candidates.add(record.profileId());
            }
        });
        List<UUID> lost = new ArrayList<>();
        for (UUID profileId : candidates) {
            boolean moved = index.atomically(() -> {
                CompanionRecord record = index.get(profileId);
                return record != null && record.neverSighted() && !hasBody.test(profileId)
                        && index.update(profileId, record.revision(), b -> b
                        .location(CompanionLocation.lost(LegacyBodyResolution.CAUSE_BODY_NOT_FOUND))
                        .currentNpcUuid(null).summonedUntilMs(0L)).applied();
            });
            if (moved) {
                lost.add(profileId);
            }
        }
        return lost;
    }

    /**
     * True while {@code record} is still exactly as the import (or {@link #markNotFound}) left it,
     * this body is its own, and no body is registered for it.
     */
    private boolean awaits(CompanionRecord record, LegacyAliases.Entry alias, UUID npcUuid) {
        boolean own = alias.kind() == LegacyAliases.Kind.CURRENT && record.neverSighted()
                && npcUuid.equals(record.currentNpcUuid())
                || LegacyBodyResolution.rejoins(alias, record);
        return own && !hasBody.test(record.profileId());
    }
}
