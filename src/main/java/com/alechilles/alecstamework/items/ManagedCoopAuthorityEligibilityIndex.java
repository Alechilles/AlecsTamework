package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/**
 * Current runtime proof that an exact physical authority still resolves to an enabled coop config.
 *
 * <p>Each reliable chunk scan replaces only its world's immutable evidence. Failed or unavailable
 * scans invalidate that world, so stale persistence alone can never authorize destructive entity
 * cleanup after a coop is disabled, removed from config, or cannot be resolved.</p>
 */
public final class ManagedCoopAuthorityEligibilityIndex implements AutoCloseable {
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(0L, Map.of()));
    private final Map<String, Long> lifecycleEpochByWorld = new HashMap<>();
    private long globalEpoch;
    private boolean closed;

    /** Replaces one world's evidence with exact values copied from resolved managed contexts. */
    public synchronized void replaceWorld(@Nonnull String worldName,
                                          @Nonnull List<AuthorityEvidence> evidence) {
        replaceWorldLocked(worldName, evidence);
    }

    /** Captures config/close and exact-world lifecycle epochs before a source scan begins. */
    synchronized PublicationToken publicationToken(@Nonnull String worldName) {
        String normalizedWorld = normalizeRequired(worldName, "worldName");
        return new PublicationToken(
                normalizedWorld,
                globalEpoch,
                lifecycleEpochByWorld.getOrDefault(normalizedWorld, 0L));
    }

    /** Publishes only if no config or same-world lifecycle invalidation raced the source scan. */
    synchronized boolean replaceWorldIfCurrent(
            @Nonnull String worldName,
            @Nonnull List<AuthorityEvidence> evidence,
            @Nonnull PublicationToken token) {
        String normalizedWorld = normalizeRequired(worldName, "worldName");
        Objects.requireNonNull(token, "token");
        if (!normalizedWorld.equals(token.worldName())
                || globalEpoch != token.globalEpoch()
                || lifecycleEpochByWorld.getOrDefault(normalizedWorld, 0L)
                != token.worldLifecycleEpoch()) {
            return false;
        }
        if (closed) {
            return false;
        }
        replaceWorldLocked(worldName, evidence);
        return true;
    }

    private void replaceWorldLocked(String worldName, List<AuthorityEvidence> evidence) {
        if (closed) {
            throw new IllegalStateException("authority eligibility index is closed");
        }
        String normalizedWorld = normalizeRequired(worldName, "worldName");
        Objects.requireNonNull(evidence, "evidence");
        LinkedHashMap<ManagedCoopAuthorityKey, String> next =
                new LinkedHashMap<>(current.get().coopIds());
        next.keySet().removeIf(key -> key.worldName().equals(normalizedWorld));
        for (AuthorityEvidence candidate : evidence) {
            if (candidate == null
                    || !candidate.authorityKey().worldName().equals(normalizedWorld)) {
                throw new IllegalArgumentException(
                        "authority evidence must belong to the replaced world");
            }
            String previous = next.putIfAbsent(candidate.authorityKey(), candidate.coopId());
            if (previous != null && !previous.equals(candidate.coopId())) {
                throw new IllegalArgumentException(
                        "authority evidence contains conflicting coop IDs");
            }
        }
        current.set(new Snapshot(nextRevision(), next));
    }

    /** Invalidates one world's current proof without affecting independently scanned worlds. */
    public synchronized void invalidateWorld(@Nonnull String worldName) {
        String normalizedWorld = normalizeRequired(worldName, "worldName");
        lifecycleEpochByWorld.put(
                normalizedWorld,
                Math.addExact(lifecycleEpochByWorld.getOrDefault(normalizedWorld, 0L), 1L));
        LinkedHashMap<ManagedCoopAuthorityKey, String> next =
                new LinkedHashMap<>(current.get().coopIds());
        next.keySet().removeIf(key -> key.worldName().equals(normalizedWorld));
        current.set(new Snapshot(nextRevision(), next));
    }

    /** Invalidates every world during config replacement or composition shutdown. */
    public synchronized void invalidateAll() {
        globalEpoch = Math.addExact(globalEpoch, 1L);
        lifecycleEpochByWorld.clear();
        current.set(new Snapshot(nextRevision(), Map.of()));
    }

    /** Permanently revokes publication when the owning runtime composition shuts down. */
    @Override
    public synchronized void close() {
        closed = true;
        globalEpoch = Math.addExact(globalEpoch, 1L);
        lifecycleEpochByWorld.clear();
        current.set(new Snapshot(nextRevision(), Map.of()));
    }

    /** Returns the current immutable multi-world snapshot. */
    @Nonnull
    public Snapshot snapshot() {
        return current.get();
    }

    private long nextRevision() {
        return Math.addExact(current.get().revision(), 1L);
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** Exact immutable authority/config evidence copied from a resolved runtime context. */
    public record AuthorityEvidence(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                    @Nonnull String coopId) {
        public AuthorityEvidence {
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = normalizeRequired(coopId, "coopId");
        }

        @Nonnull
        public static AuthorityEvidence copyOf(@Nonnull ManagedCoopContext context) {
            Objects.requireNonNull(context, "context");
            return new AuthorityEvidence(context.authorityKey(), context.coopId());
        }
    }

    /** Immutable source-scan guard unaffected by unrelated-world publications. */
    record PublicationToken(@Nonnull String worldName,
                            long globalEpoch,
                            long worldLifecycleEpoch) {
        PublicationToken {
            worldName = normalizeRequired(worldName, "worldName");
        }
    }

    /** Immutable lookup used by entity policy decisions. */
    public record Snapshot(long revision,
                           @Nonnull Map<ManagedCoopAuthorityKey, String> coopIds) {
        public Snapshot {
            coopIds = Map.copyOf(coopIds);
        }

        public boolean contains(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                @Nonnull String coopId) {
            Objects.requireNonNull(authorityKey, "authorityKey");
            return normalizeRequired(coopId, "coopId").equals(coopIds.get(authorityKey));
        }
    }
}
