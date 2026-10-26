package com.alechilles.alecstamework.companion.network;

import com.alechilles.alecstamework.api.PortableCompanionEntry;
import com.alechilles.alecstamework.api.PortableCompanionStore;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Server network support (spec 13): keeps this server's companions in step with a network's
 * {@link PortableCompanionStore}. Without a store it does nothing.
 *
 * <ul>
 * <li><b>Push.</b> A change to an owned record is pushed once the writer has written its owner
 * file, so a commit whose write failed never leaves the server. Every entry names this server as
 * its writer, and a store lets a writer replace its own row even with a lower generation, so a
 * commit reverted after its write (a failed spawn) is corrected too. Portable records go with their
 * snapshot; world-bound records go without one, and only when something that counts toward limits
 * changed (not every position update). When a store is installed every owned record is pushed once,
 * so the store learns what this server holds.</li>
 * <li><b>Pull.</b> When an owner joins, their entries are read and each newer one is applied: a
 * portable entry replaces the local copy and its snapshot; an entry held by another server turns a
 * stale local portable copy into a released tombstone with cause {@link #CAUSE_ELSEWHERE}, which never
 * counts and still fences any stale body. World-bound local records are never overwritten.
 * {@link #refresh} reads one profile the same way before it is restored.</li>
 * <li><b>Counts.</b> The owner's companions held in other servers' worlds are kept in memory as
 * stand-in records ({@link #ownerRecords}), so owner limits count the whole network.</li>
 * </ul>
 *
 * <p>Every store call and every snapshot read runs on the single {@code tamework-portable-store}
 * thread. The index listener and the writer listener only record ids and records. Failures keep the
 * work queued and retry with backoff from 1 s to 30 s. Durability is best effort, like the rest of
 * the store.</p>
 */
public final class PortableSync implements AutoCloseable {
    /** The cause of a tombstone left for a companion that another server holds now. */
    public static final String CAUSE_ELSEWHERE = "NETWORK_ELSEWHERE";
    static final long PUSH_INTERVAL_MS = 500L;
    static final int PUSH_BATCH = 100;
    static final long MAX_BACKOFF_MS = 30_000L;
    static final long REFRESH_TIMEOUT_MS = 3_000L;
    private static final long[] LOAD_RETRY_MS = {5_000L, 15_000L, 30_000L};
    private static final long WARN_INTERVAL_MS = 60_000L;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Reads a profile's latest snapshot, blocking; null when there is none. Called on the store thread only. */
    @FunctionalInterface
    public interface SnapshotReader {
        @Nullable
        SnapshotEnvelope read(@Nonnull UUID profileId) throws Exception;
    }

    private final CompanionIndex index;
    private final SnapshotReader readSnapshot;
    private final Consumer<SnapshotEnvelope> queueSnapshot;
    private final Consumer<UUID> deleteSnapshot;
    private final ScheduledExecutorService executor;
    private final LongSupplier clock;

    private final Object lock = new Object();
    @Nullable private volatile PortableCompanionStore store;
    /** Changed since last pushed: profile id to the newest changed revision. Guarded by {@link #lock}. */
    private final Map<UUID, Long> candidates = new HashMap<>();
    /** Written and waiting to be pushed: profile id to the record to push. Guarded by {@link #lock}. */
    private final Map<UUID, CompanionRecord> outbox = new LinkedHashMap<>();
    /** Owner to the stand-ins for their companions held in other servers' worlds. */
    private final Map<UUID, Map<UUID, CompanionRecord>> elsewhere = new ConcurrentHashMap<>();
    /** Owners whose entries were read since they joined: this server's copies of them are current. */
    private final Set<UUID> loadedOwners = ConcurrentHashMap.newKeySet();
    private long backoffMs;
    private long nextPushAtMs;
    private long nextWarnAtMs = Long.MIN_VALUE;
    private volatile boolean closed;

    /**
     * @param readSnapshot   the profile's latest snapshot (the writer's pending one first, then the file)
     * @param queueSnapshot  hands a snapshot to the writer (before the record change that uses it)
     * @param deleteSnapshot queues the delete of a profile's snapshot
     * @param executor       a dedicated single-thread executor, owned by this object
     * @param clock          wall clock
     */
    public PortableSync(@Nonnull CompanionIndex index, @Nonnull SnapshotReader readSnapshot,
                        @Nonnull Consumer<SnapshotEnvelope> queueSnapshot, @Nonnull Consumer<UUID> deleteSnapshot,
                        @Nonnull ScheduledExecutorService executor, @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.readSnapshot = Objects.requireNonNull(readSnapshot, "readSnapshot");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts the push loop. */
    public void start() {
        executor.scheduleWithFixedDelay(this::pushSafely, PUSH_INTERVAL_MS, PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Whether a store is installed. */
    public boolean active() {
        return store != null && !closed;
    }

    /**
     * Installs the store and queues every owned record this server holds for a first push.
     *
     * @throws IllegalStateException when a store is installed already or this object is closed
     */
    @Nonnull
    public AutoCloseable register(@Nonnull PortableCompanionStore newStore) {
        Objects.requireNonNull(newStore, "store");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("portable companion sync is closed");
            }
            if (store != null) {
                throw new IllegalStateException("a portable companion store is already registered");
            }
            store = newStore;
            index.forEach(record -> {
                if (record.ownerUuid() != null && !isElsewhere(record)) {
                    outbox.put(record.profileId(), record);
                }
            });
        }
        LOGGER.at(Level.INFO).log("Companions follow their owners through the network store as server %s",
                newStore.holderId());
        return new Registration(newStore);
    }

    /** Removes the store it installed; idempotent. Public so a mod can close it by reflection. */
    public final class Registration implements AutoCloseable {
        private final PortableCompanionStore installed;

        private Registration(PortableCompanionStore installed) {
            this.installed = installed;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (store == installed) {
                    store = null;
                }
            }
        }
    }

    // --- push -------------------------------------------------------------------------------

    /**
     * Index after-unlock listener: notes a change that the network must hear about. A copy just taken
     * from the store comes back here too; pushing it again is harmless (the store keeps the row as it is).
     */
    public void onRecordChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (store == null || !mustPush(before, after)) {
            return;
        }
        synchronized (lock) {
            candidates.merge(after.profileId(), after.revision(), Math::max);
        }
    }

    /** Writer listener: moves each written candidate to the outbox, as written. */
    public void onWritten(@Nonnull List<CompanionRecord> records) {
        if (store == null) {
            return;
        }
        synchronized (lock) {
            for (CompanionRecord record : records) {
                Long changed = candidates.get(record.profileId());
                if (changed == null) {
                    continue;
                }
                if (record.revision() >= changed) {
                    candidates.remove(record.profileId());
                }
                outbox.put(record.profileId(), record);
            }
        }
    }

    /**
     * Whether a change matters to other servers: anything on an owned portable record, a record that
     * leaves or enters the portable set, and on a world-bound record only what limits count.
     */
    static boolean mustPush(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (isElsewhere(after)) {
            return false;
        }
        if (before == null) {
            return after.ownerUuid() != null;
        }
        if (after.ownerUuid() == null && before.ownerUuid() == null) {
            return false;
        }
        if (after.scope() == RecordScope.PORTABLE || before.scope() == RecordScope.PORTABLE) {
            return true;
        }
        return !countedFieldsEqual(before, after);
    }

    private static boolean countedFieldsEqual(CompanionRecord a, CompanionRecord b) {
        return a.location().kind() == b.location().kind() && a.generation() == b.generation()
                && Objects.equals(a.ownerUuid(), b.ownerUuid()) && a.roleId().equals(b.roleId())
                && Objects.equals(a.location().world(), b.location().world())
                && Objects.equals(a.homeWorld(), b.homeWorld()) && a.domainClaims().equals(b.domainClaims());
    }

    private void pushSafely() {
        try {
            push(PUSH_BATCH);
        } catch (RuntimeException | LinkageError failure) {
            warn("Companions could not be sent to the network store", failure);
        }
    }

    /**
     * Sends up to {@code max} queued records; returns how many left the outbox (sent, or dropped as
     * superseded). Runs on the executor thread.
     */
    int push(int max) {
        PortableCompanionStore target = store;
        if (target == null || clock.getAsLong() < nextPushAtMs) {
            return 0;
        }
        Map<UUID, CompanionRecord> batch = new LinkedHashMap<>();
        synchronized (lock) {
            for (Iterator<Map.Entry<UUID, CompanionRecord>> it = outbox.entrySet().iterator();
                 it.hasNext() && batch.size() < max; ) {
                Map.Entry<UUID, CompanionRecord> next = it.next();
                batch.put(next.getKey(), next.getValue());
                it.remove();
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        List<PortableCompanionEntry> entries = new ArrayList<>(batch.size());
        Map<UUID, CompanionRecord> retry = new LinkedHashMap<>();
        for (CompanionRecord record : batch.values()) {
            if (record.scope() != RecordScope.PORTABLE) {
                entries.add(PortableEntries.entry(record, null, target.holderId()));
                continue;
            }
            SnapshotEnvelope snapshot;
            try {
                snapshot = readSnapshot.read(record.profileId());
            } catch (Exception unreadable) {
                // Never send a portable copy without its snapshot: the other server would lose it.
                retry.put(record.profileId(), record);
                continue;
            }
            if (snapshot != null && snapshot.generation() > record.generation()) {
                // The companion moved on; its newer record is queued once it is written.
                continue;
            }
            entries.add(PortableEntries.entry(record, snapshot, target.holderId()));
        }
        try {
            if (!entries.isEmpty()) {
                target.save(entries);
            }
            backoffMs = 0L;
            nextPushAtMs = 0L;
        } catch (Exception failure) {
            retry.putAll(batch);
            backoffMs = backoffMs == 0L ? 1_000L : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            nextPushAtMs = clock.getAsLong() + backoffMs;
            warn("The network store did not take " + entries.size() + " companions; retrying", failure);
        }
        if (!retry.isEmpty()) {
            synchronized (lock) {
                // A newer copy queued meanwhile wins.
                retry.forEach(outbox::putIfAbsent);
            }
        }
        return batch.size() - retry.size();
    }

    private int outboxSize() {
        synchronized (lock) {
            return outbox.size();
        }
    }

    // --- pull -------------------------------------------------------------------------------

    /** Reads an owner's entries in the background (call when they join). */
    public void ownerJoined(@Nonnull UUID owner) {
        Objects.requireNonNull(owner, "owner");
        loadedOwners.remove(owner);
        if (active()) {
            submitLoad(owner, 0);
        }
    }

    /**
     * The owner left this server: their copies here may fall behind until they join again, so a
     * restore of them needs a working store from now on (see {@link #refresh}).
     */
    public void ownerLeft(@Nonnull UUID owner) {
        loadedOwners.remove(Objects.requireNonNull(owner, "owner"));
    }

    private void submitLoad(UUID owner, int attempt) {
        Runnable task = () -> {
            try {
                loadOwner(owner);
            } catch (Exception failure) {
                if (attempt < LOAD_RETRY_MS.length && active()) {
                    try {
                        executor.schedule(() -> submitLoad(owner, attempt + 1), LOAD_RETRY_MS[attempt],
                                TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException shutDown) {
                        // Stopping; the next join reads again.
                    }
                }
                warn("The network store could not be read for owner " + owner, failure);
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException shutDown) {
            // Stopping.
        }
    }

    /** Reads and applies an owner's entries. Runs on the executor thread. */
    void loadOwner(@Nonnull UUID owner) throws Exception {
        PortableCompanionStore source = store;
        if (source == null) {
            return;
        }
        List<CompanionRecord> local = index.fileRecords(owner);
        List<UUID> ids = new ArrayList<>();
        for (CompanionRecord record : local) {
            if (record.scope() == RecordScope.PORTABLE || isElsewhere(record)) {
                ids.add(record.profileId());
            }
        }
        List<PortableCompanionEntry> entries = source.load(owner, ids);
        apply(entries, source.holderId());
        Map<UUID, CompanionRecord> held = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (PortableCompanionEntry entry : entries) {
            seen.add(entry.profileId());
            if (!entry.portable() && owner.equals(entry.ownerUuid()) && !source.holderId().equals(entry.holder())
                    && PortableEntries.countsAsOwned(entry)) {
                CompanionRecord standIn = standIn(entry);
                if (standIn != null) {
                    held.put(entry.profileId(), standIn);
                }
            }
        }
        if (held.isEmpty()) {
            elsewhere.remove(owner);
        } else {
            elsewhere.put(owner, Map.copyOf(held));
        }
        // What the store has never seen goes up.
        for (CompanionRecord record : index.fileRecords(owner)) {
            if (record.scope() == RecordScope.PORTABLE && !seen.contains(record.profileId())) {
                queue(record);
            }
        }
        loadedOwners.add(owner);
    }

    /**
     * Reads one profile from the store and applies it, so a restore starts from the network's latest
     * copy. Completes with true when the index is safe to restore from: the store answered (within
     * {@link #REFRESH_TIMEOUT_MS}), no store is installed, or the store did not answer but this server
     * read the owner's entries since they joined (only the server they are on changes their portable
     * companions). False otherwise: the restore must wait, or two servers could each restore a copy.
     */
    @Nonnull
    public CompletableFuture<Boolean> refresh(@Nonnull UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        if (!active()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    PortableCompanionStore source = store;
                    if (source != null) {
                        apply(source.load(null, List.of(profileId)), source.holderId());
                    }
                    done.complete(true);
                } catch (Exception failure) {
                    warn("The network store could not be read for companion " + profileId, failure);
                    done.complete(currentHere(profileId));
                }
            });
        } catch (RejectedExecutionException shutDown) {
            done.complete(currentHere(profileId));
        }
        return done.completeOnTimeout(null, REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenApply(fresh -> fresh != null ? fresh : currentHere(profileId));
    }

    /** True when this server's copy of the profile is current without asking the store. */
    private boolean currentHere(UUID profileId) {
        CompanionRecord record = index.get(profileId);
        return record != null && record.ownerUuid() != null && loadedOwners.contains(record.ownerUuid());
    }

    /**
     * Whether a capture item may hold a companion this server has not caught up with: no record, or
     * the item is newer than the record. Only meaningful while a store is installed.
     */
    public boolean mayBeBehind(@Nullable CompanionRecord record, long itemGeneration) {
        return active() && (record == null || itemGeneration > record.generation());
    }

    private void apply(List<PortableCompanionEntry> entries, String holderId) {
        for (PortableCompanionEntry entry : entries) {
            try {
                applyOne(entry, holderId);
            } catch (RuntimeException failure) {
                warn("A network copy of companion " + entry.profileId() + " could not be applied", failure);
            }
        }
    }

    private void applyOne(PortableCompanionEntry entry, String holderId) {
        CompanionRecord local = index.get(entry.profileId());
        if (entry.portable()) {
            if (takesNetworkCopy(local, entry)) {
                adopt(entry);
            } else if (local.scope() == RecordScope.PORTABLE && PortableEntries.supersedes(local, entry)) {
                queue(local);
            }
            return;
        }
        if (local == null || isElsewhere(local)) {
            return;
        }
        if (!holderId.equals(entry.holder())) {
            if (local.scope() == RecordScope.PORTABLE) {
                if (PortableEntries.supersedes(entry, local)) {
                    markElsewhere(local);
                } else {
                    queue(local);
                }
            }
        } else if (PortableEntries.supersedes(local, entry)) {
            queue(local);
        }
    }

    /** Whether a portable network copy replaces the local record: none here, or an older one that follows. */
    private static boolean takesNetworkCopy(@Nullable CompanionRecord local, PortableCompanionEntry entry) {
        return local == null || (local.scope() == RecordScope.PORTABLE || isElsewhere(local))
                && PortableEntries.supersedes(entry, local);
    }

    private void adopt(PortableCompanionEntry entry) {
        CompanionRecord incoming = PortableEntries.record(entry);
        SnapshotEnvelope snapshot = PortableEntries.snapshot(entry);
        // One locked step: a local change that raced ahead keeps both its record and its snapshot.
        index.atomically(() -> {
            if (!takesNetworkCopy(index.get(incoming.profileId()), entry)) {
                return null;
            }
            // The snapshot is queued before the record that uses it (the writer's contract).
            if (snapshot != null) {
                queueSnapshot.accept(snapshot);
            } else {
                deleteSnapshot.accept(incoming.profileId());
            }
            return index.adopt(incoming);
        });
    }

    /**
     * Leaves a tombstone at the local generation: a holder of it here (an item, a body) is stale, as it
     * should be, and a reverted network copy at that generation (see {@link PortableCompanionEntry#replaces})
     * can still come back.
     */
    private void markElsewhere(CompanionRecord local) {
        CompanionIndex.Mutation result = index.update(local.profileId(), local.revision(), b -> b
                .location(CompanionLocation.released(CAUSE_ELSEWHERE))
                .currentNpcUuid(null)
                .summonedUntilMs(0L));
        if (result.applied()) {
            deleteSnapshot.accept(local.profileId());
        }
    }

    private void queue(CompanionRecord record) {
        synchronized (lock) {
            outbox.put(record.profileId(), record);
        }
    }

    // --- counts -----------------------------------------------------------------------------

    /**
     * Every record filed under {@code owner} here, plus stand-ins for their companions held in other
     * servers' worlds, for owner limits. A stand-in is left out while this server has its own record of
     * that profile. A stand-in's world is {@code <holder>/<world>}, so per-world limits keep counting
     * per world. Lock-free.
     */
    @Nonnull
    public List<CompanionRecord> ownerRecords(@Nullable UUID owner) {
        List<CompanionRecord> local = index.fileRecords(owner);
        Map<UUID, CompanionRecord> remote = owner == null ? null : elsewhere.get(owner);
        if (remote == null || remote.isEmpty()) {
            return local;
        }
        List<CompanionRecord> all = new ArrayList<>(local.size() + remote.size());
        all.addAll(local);
        for (CompanionRecord standIn : remote.values()) {
            CompanionRecord mine = index.get(standIn.profileId());
            if (mine == null || isElsewhere(mine)) {
                all.add(standIn);
            }
        }
        return all;
    }

    @Nullable
    private static CompanionRecord standIn(PortableCompanionEntry entry) {
        CompanionRecord record;
        try {
            record = PortableEntries.record(entry);
        } catch (RuntimeException unreadable) {
            return null;
        }
        CompanionLocation at = record.location();
        String holder = entry.holder() == null ? "?" : entry.holder();
        CompanionLocation moved = at.world() == null ? at : new CompanionLocation(at.kind(),
                holder + "/" + at.world(), at.x(), at.y(), at.z(), at.slot(), at.reason(), at.cause());
        return record.toBuilder()
                .location(moved)
                .homeWorld(record.homeWorld() == null ? null : holder + "/" + record.homeWorld())
                .build();
    }

    /** True for the tombstone this class leaves for a companion another server holds. */
    public static boolean isElsewhere(@Nullable CompanionRecord record) {
        return record != null && record.location().kind() == LocationKind.RELEASED
                && CAUSE_ELSEWHERE.equals(record.location().cause());
    }

    // --- lifecycle --------------------------------------------------------------------------

    /**
     * Sends what is queued within {@code timeoutMs}, then stops. Call it after the writer's final
     * flush: snapshots are read from their files on this object's own thread. Best effort: what is
     * left is pushed by the next start's first push. Never call it on a world thread.
     */
    public void shutdown(long timeoutMs) {
        if (closed) {
            return;
        }
        try {
            Future<?> last = executor.submit(() -> {
                nextPushAtMs = 0L;
                int sent;
                do {
                    sent = push(PUSH_BATCH);
                    // Until the outbox is empty, a save fails, or nothing more can be sent.
                } while (sent > 0 && nextPushAtMs == 0L && outboxSize() > 0);
            });
            last.get(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unfinished) {
            LOGGER.at(Level.WARNING).log("Some companions were not sent to the network store before shutdown;"
                    + " the next start sends them");
        } finally {
            close();
        }
        if (outboxSize() > 0) {
            LOGGER.at(Level.WARNING).log("%d companions were not sent to the network store before shutdown;"
                    + " the next start sends them", outboxSize());
        }
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }

    private void warn(String message, Throwable cause) {
        long now = clock.getAsLong();
        if (now < nextWarnAtMs) {
            return;
        }
        nextWarnAtMs = now + WARN_INTERVAL_MS;
        LOGGER.at(Level.WARNING).withCause(cause).log("%s", message);
    }

    /** Tests: what waits to be sent. */
    @Nonnull
    Collection<CompanionRecord> outboxForTests() {
        synchronized (lock) {
            return List.copyOf(outbox.values());
        }
    }
}
