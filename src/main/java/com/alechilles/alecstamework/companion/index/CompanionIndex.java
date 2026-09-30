package com.alechilles.alecstamework.companion.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * In-memory companion index (spec 6.7). Reads are lock-free map lookups and are safe
 * from any thread. Writes take one global lock, so cap checks, loaded-body checks and
 * record changes can be made atomic with {@link #atomically(Supplier)}.
 */
public final class CompanionIndex {
    /** Receives every applied change. Must be cheap and non-blocking; it runs under the index lock. */
    public interface ChangeListener {
        void onChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after);
    }

    public enum Status { APPLIED, CONFLICT, NOT_FOUND, DUPLICATE }

    public record Mutation(@Nonnull Status status, @Nullable CompanionRecord before, @Nullable CompanionRecord after) {
        public boolean applied() {
            return status == Status.APPLIED;
        }
    }

    private static final UUID UNOWNED = new UUID(0L, 0L);

    private final Map<UUID, CompanionRecord> records = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> profilesByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> profileByNpc = new ConcurrentHashMap<>();
    private final Map<String, UUID> profileByOrigin = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final LongSupplier clock;
    private final ChangeListener listener;

    public CompanionIndex(@Nonnull LongSupplier clock, @Nonnull ChangeListener listener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Replaces the whole index with loaded records. Calls no listener. */
    public void load(@Nonnull Collection<CompanionRecord> loaded) {
        synchronized (lock) {
            records.clear();
            profilesByOwner.clear();
            profileByNpc.clear();
            profileByOrigin.clear();
            for (CompanionRecord record : loaded) {
                records.put(record.profileId(), record);
                addToMaps(record);
            }
        }
    }

    @Nullable
    public CompanionRecord get(@Nonnull UUID profileId) {
        return records.get(profileId);
    }

    /** Every record filed under this owner, including RELEASED tombstones. {@code null} means unowned. */
    @Nonnull
    public List<CompanionRecord> fileRecords(@Nullable UUID owner) {
        Set<UUID> ids = profilesByOwner.get(ownerKey(owner));
        if (ids == null) {
            return List.of();
        }
        List<CompanionRecord> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            CompanionRecord record = records.get(id);
            if (record != null) {
                out.add(record);
            }
        }
        return out;
    }

    public int count(@Nonnull UUID owner, @Nonnull Predicate<CompanionRecord> filter) {
        int n = 0;
        for (CompanionRecord record : fileRecords(owner)) {
            if (filter.test(record)) {
                n++;
            }
        }
        return n;
    }

    public int ownedCount(@Nonnull UUID owner) {
        return count(owner, CompanionRecord::countsAsOwned);
    }

    public int deployedCount(@Nonnull UUID owner) {
        return count(owner, r -> r.countsAsOwned() && r.isDeployed());
    }

    @Nullable
    public CompanionRecord byNpcUuid(@Nonnull UUID npcUuid) {
        UUID id = profileByNpc.get(npcUuid);
        return id == null ? null : records.get(id);
    }

    @Nullable
    public CompanionRecord byOrigin(@Nonnull String namespace, @Nonnull String key) {
        UUID id = profileByOrigin.get(originKey(namespace, key));
        return id == null ? null : records.get(id);
    }

    public void forEach(@Nonnull Consumer<CompanionRecord> action) {
        records.values().forEach(action);
    }

    /** Runs {@code action} under the index lock. Nested inserts and updates are allowed. */
    public <T> T atomically(@Nonnull Supplier<T> action) {
        synchronized (lock) {
            return action.get();
        }
    }

    @Nonnull
    public Mutation insert(@Nonnull CompanionRecord record) {
        synchronized (lock) {
            CompanionRecord existing = records.get(record.profileId());
            if (existing != null) {
                return new Mutation(Status.DUPLICATE, existing, null);
            }
            String origin = originKey(record);
            if (origin != null && profileByOrigin.containsKey(origin)) {
                return new Mutation(Status.DUPLICATE, records.get(profileByOrigin.get(origin)), null);
            }
            CompanionRecord stamped = record.toBuilder().updatedAtMs(clock.getAsLong()).build();
            records.put(stamped.profileId(), stamped);
            addToMaps(stamped);
            listener.onChanged(null, stamped);
            return new Mutation(Status.APPLIED, null, stamped);
        }
    }

    /**
     * Applies {@code change} if the current revision equals {@code expectedRevision}.
     * The revision goes up by one; the profile id cannot change; the generation cannot go down.
     */
    @Nonnull
    public Mutation update(@Nonnull UUID profileId, long expectedRevision,
                           @Nonnull UnaryOperator<CompanionRecord.Builder> change) {
        synchronized (lock) {
            CompanionRecord before = records.get(profileId);
            if (before == null) {
                return new Mutation(Status.NOT_FOUND, null, null);
            }
            if (before.revision() != expectedRevision) {
                return new Mutation(Status.CONFLICT, before, null);
            }
            CompanionRecord changed = change.apply(before.toBuilder()).build();
            if (!changed.profileId().equals(profileId)) {
                throw new IllegalArgumentException("profileId cannot change");
            }
            if (changed.generation() < before.generation()) {
                throw new IllegalArgumentException("generation cannot go backwards");
            }
            String origin = originKey(changed);
            UUID originHolder = origin == null ? null : profileByOrigin.get(origin);
            if (originHolder != null && !originHolder.equals(profileId)) {
                return new Mutation(Status.DUPLICATE, before, null);
            }
            CompanionRecord after = changed.toBuilder()
                    .revision(before.revision() + 1)
                    .updatedAtMs(clock.getAsLong())
                    .build();
            removeFromMaps(before);
            records.put(profileId, after);
            addToMaps(after);
            listener.onChanged(before, after);
            return new Mutation(Status.APPLIED, before, after);
        }
    }

    private void addToMaps(CompanionRecord record) {
        profilesByOwner.computeIfAbsent(ownerKey(record.ownerUuid()), k -> ConcurrentHashMap.newKeySet())
                .add(record.profileId());
        if (record.currentNpcUuid() != null) {
            profileByNpc.put(record.currentNpcUuid(), record.profileId());
        }
        String origin = originKey(record);
        if (origin != null) {
            profileByOrigin.put(origin, record.profileId());
        }
    }

    private void removeFromMaps(CompanionRecord record) {
        UUID owner = ownerKey(record.ownerUuid());
        Set<UUID> ids = profilesByOwner.get(owner);
        if (ids != null) {
            ids.remove(record.profileId());
            if (ids.isEmpty()) {
                profilesByOwner.remove(owner);
            }
        }
        if (record.currentNpcUuid() != null) {
            profileByNpc.remove(record.currentNpcUuid(), record.profileId());
        }
        String origin = originKey(record);
        if (origin != null) {
            profileByOrigin.remove(origin, record.profileId());
        }
    }

    private static UUID ownerKey(@Nullable UUID owner) {
        return owner == null ? UNOWNED : owner;
    }

    @Nullable
    private static String originKey(CompanionRecord record) {
        return record.originNamespace() == null ? null : originKey(record.originNamespace(), record.originKey());
    }

    private static String originKey(String namespace, String key) {
        return namespace + '\u0000' + key;
    }
}
