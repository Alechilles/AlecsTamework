package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Decides what an unstamped body saved by Tamework 3.x or 4.x is to the imported companion index
 * (spec 12.4 "Old bodies", plan 7 R14). The caller checks that the body has no companion stamp.
 *
 * <p>The body's record is looked for in this order, and a step counts only when the index has
 * that record:</p>
 * <ol>
 *   <li>the legacy alias of the body's NPC UUID, when the importer marked that body as the
 *       companion's own ({@link LegacyAliases.Kind#CURRENT} or {@link LegacyAliases.Kind#REJOIN});</li>
 *   <li>the profile id of the body's projection identity;</li>
 *   <li>any other legacy alias of the NPC UUID;</li>
 *   <li>the NPC UUID used as a profile id.</li>
 * </ol>
 * Step 1 comes before the spec's order (projection identity, alias, NPC UUID) because the NPC
 * UUID a record itself names is the stronger identity: a projection identity is a marker an old
 * operation left on the body and may name another profile.
 *
 * <p>The rule of this class is "when in doubt, do not remove". A body the importer marked as the
 * companion's own is removed only when the record proves another body holds the companion now: it
 * names a different NPC UUID, or its generation is above 0 (it was restored after the import).</p>
 */
public final class LegacyBodyResolution {
    /** Causes of records the importer could not place start with this (see {@code LegacyMapper}). */
    private static final String IMPORTED_CAUSE_PREFIX = "IMPORTED_";

    private LegacyBodyResolution() {
    }

    public enum Action {
        /** The record's current body: stamp it with the record's generation and register it. */
        STAMP_CURRENT,
        /** The body of a record imported LOST: the record goes back to LIVE with this body. */
        REJOIN,
        /** A leftover duplicate of a companion: remove the body. */
        REMOVE_STALE,
        /** No record claims this owned and tamed body: the normal tame path registers it. */
        ADOPT,
        /** Do nothing to the body, and do not register it. */
        LEAVE
    }

    /**
     * What is read from the unstamped body on its world thread. {@code projectionProfileId} is the
     * profile id of its projection identity, null when it has none or the id is not a UUID.
     */
    public record Body(boolean ownedAndTamed, boolean hasProjectionIdentity,
                       @Nullable UUID projectionProfileId, @Nonnull UUID npcUuid) {
        public Body {
            Objects.requireNonNull(npcUuid, "npcUuid");
        }

        /** Plan 7 R14: only such a body may be removed as a stale duplicate. */
        boolean old() {
            return ownedAndTamed || hasProjectionIdentity;
        }
    }

    /**
     * {@code profileId} is the record's id, null when no record was found. {@code generation} is
     * the stamp to write for {@link Action#STAMP_CURRENT} and {@link Action#REJOIN}. {@code reason}
     * is a short English diagnostic for logs.
     */
    public record Decision(@Nonnull Action action, @Nullable UUID profileId, long generation, @Nonnull String reason) {
    }

    /**
     * Pure decision.
     *
     * @param alias             the alias entry of the body's NPC UUID, if any
     * @param record            the record the body resolved to, if any
     * @param unreadable        true when no record was found but one of the ids the body resolves
     *                          to could not be read at startup; such a body is never adopted
     * @param anotherBodyLoaded true when another valid body is registered for the record's profile
     * @param liveTame          true when a player is taming the loaded body right now; false for a
     *                          body that arrived already owned (chunk load, startup pass). An
     *                          animal 4.x released is wild again, so taming it makes a new
     *                          companion instead of removing it as a body of its old tombstone.
     */
    @Nonnull
    public static Decision decide(@Nonnull Body body, @Nullable LegacyAliases.Entry alias,
                                  @Nullable CompanionRecord record, boolean unreadable, boolean anotherBodyLoaded,
                                  boolean liveTame) {
        if (record == null) {
            if (unreadable) {
                return leave(null, "its record could not be read at startup");
            }
            return body.ownedAndTamed()
                    ? new Decision(Action.ADOPT, null, 0L, "no record") : leave(null, "no record");
        }
        UUID profileId = record.profileId();
        boolean ownBody = alias != null && alias.kind() != LegacyAliases.Kind.STALE;
        if (ownBody && !alias.profileId().equals(profileId)) {
            return leave(record, "its alias names another profile");
        }
        LocationKind kind = record.location().kind();
        if (kind == LocationKind.LIVE && body.npcUuid().equals(record.currentNpcUuid())) {
            return anotherBodyLoaded ? leave(record, "another body is loaded for its profile")
                    : new Decision(Action.STAMP_CURRENT, profileId, record.generation(), "current body");
        }
        if (alias != null && alias.kind() == LegacyAliases.Kind.REJOIN && awaitsItsBody(record)) {
            return anotherBodyLoaded ? leave(record, "another body is loaded for its profile")
                    : new Decision(Action.REJOIN, profileId, record.generation(), "body of a companion imported as lost");
        }
        if (ownBody) {
            boolean anotherHolder = record.generation() > 0
                    || record.currentNpcUuid() != null && !record.currentNpcUuid().equals(body.npcUuid());
            if (!anotherHolder) {
                return leave(record, "the record does not prove another body holds the companion");
            }
        }
        if (kind == LocationKind.LIVE && record.currentNpcUuid() == null) {
            return leave(record, "the live record names no body to compare with");
        }
        if (!body.old()) {
            return leave(record, "not a Tamework body");
        }
        if (liveTame && kind == LocationKind.RELEASED && body.ownedAndTamed()) {
            return new Decision(Action.ADOPT, null, 0L, "tamed again after its release");
        }
        String why = ownBody ? "the companion has another holder since the import"
                : alias != null ? "stale alias" : "not the record's current body";
        return new Decision(Action.REMOVE_STALE, profileId, 0L, why + " (record is " + kind + ")");
    }

    /**
     * True for a record imported LIVE whose body 5.0 has not seen yet: still at generation 0 and at
     * exactly 0,0,0, where the importer puts a body it has no position for. The first sighting of
     * the body writes its real position. Until then the record's world may be a guess and its
     * snapshot is not the body's state (it is empty or an older one), so nothing routine may
     * replace the body from it.
     */
    public static boolean neverSighted(@Nonnull CompanionRecord record) {
        return record.neverSighted();
    }

    /** True for a record still exactly as it was imported LOST with no known place. */
    private static boolean awaitsItsBody(CompanionRecord record) {
        String cause = record.location().cause();
        return record.location().kind() == LocationKind.LOST && cause != null && cause.startsWith(IMPORTED_CAUSE_PREFIX)
                && record.generation() == 0 && record.currentNpcUuid() == null;
    }

    private static Decision leave(@Nullable CompanionRecord record, String reason) {
        return new Decision(Action.LEAVE, record == null ? null : record.profileId(), 0L, reason);
    }

    /** Finds the body's record in the order the class comment gives. */
    @Nullable
    static CompanionRecord resolve(@Nonnull Body body, @Nullable LegacyAliases.Entry alias,
                                   @Nonnull Function<UUID, CompanionRecord> records) {
        CompanionRecord record = null;
        if (alias != null && alias.kind() != LegacyAliases.Kind.STALE) {
            record = records.apply(alias.profileId());
        }
        if (record == null && body.projectionProfileId() != null) {
            record = records.apply(body.projectionProfileId());
        }
        if (record == null && alias != null) {
            record = records.apply(alias.profileId());
        }
        return record != null ? record : records.apply(body.npcUuid());
    }

    /**
     * Resolves, decides and applies the index side of the decision in one step under the index
     * lock, so no other change to the record (for example an owner's Recover) can land between
     * the decision and the update. For {@link Action#STAMP_CURRENT} and {@link Action#REJOIN} the
     * record is updated with {@code matched} and {@code ref} becomes the loaded body; if that
     * update does not apply, or {@code matched} refuses the match by returning null, the answer is
     * {@link Action#LEAVE} and nothing changed. The caller writes the stamp and removes a stale body.
     *
     * @param valid   true while a registered body reference is still usable
     * @param matched given the record as it is under the lock, the change that makes it LIVE at
     *                this body (place, NPC UUID and whatever else the body is the authority for),
     *                or null to refuse the match (a live tame over the owner's limit). It must not
     *                change the generation. Runs under the index lock: no I/O.
     */
    @Nonnull
    public static <R> Decision admit(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                                     @Nonnull LegacyAliases aliases, @Nonnull Predicate<UUID> unreadable,
                                     @Nonnull Predicate<R> valid, @Nonnull Body body, @Nonnull R ref, boolean liveTame,
                                     @Nonnull Function<CompanionRecord, UnaryOperator<CompanionRecord.Builder>> matched) {
        LegacyAliases.Entry alias = aliases.byNpcUuid(body.npcUuid()).orElse(null);
        return index.atomically(() -> {
            CompanionRecord record = resolve(body, alias, index::get);
            boolean unread = record == null && (unreadable.test(body.npcUuid())
                    || alias != null && unreadable.test(alias.profileId())
                    || body.projectionProfileId() != null && unreadable.test(body.projectionProfileId()));
            R other = record == null ? null : loaded.get(record.profileId());
            boolean anotherLoaded = other != null && !other.equals(ref) && valid.test(other);
            Decision decision = decide(body, alias, record, unread, anotherLoaded, liveTame);
            if (decision.action() == Action.STAMP_CURRENT || decision.action() == Action.REJOIN) {
                UnaryOperator<CompanionRecord.Builder> change = matched.apply(record);
                if (change == null) {
                    return leave(record, "the match was refused");
                }
                if (!index.update(record.profileId(), record.revision(), change).applied()) {
                    return leave(record, "its record could not be updated");
                }
                loaded.put(record.profileId(), ref);
            }
            return decision;
        });
    }
}
