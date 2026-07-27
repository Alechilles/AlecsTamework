package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bounded non-blocking cache between the world-thread panel and durable bonded
 * profile reads.
 *
 * <p>Only immutable IDs and profile views cross the worker boundary. Every
 * publication is fenced by the exact entry identity and generation that
 * requested it.</p>
 */
final class BondedCompanionPanelSnapshotCache implements AutoCloseable {
    private static final Settings DEFAULT_SETTINGS = new Settings(
            128,
            TimeUnit.SECONDS.toNanos(2L),
            TimeUnit.MINUTES.toNanos(5L),
            TimeUnit.MILLISECONDS.toNanos(100L),
            TimeUnit.SECONDS.toNanos(5L));
    private static final int WORK_QUEUE_CAPACITY = 64;

    private final Object lock = new Object();
    private final Supplier<BondedCompanionApi> api;
    private final Executor worker;
    @Nullable private final AutoCloseable ownedWorker;
    private final LongSupplier monotonicClock;
    private final Settings settings;
    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<>(16, 0.75F, true);
    @Nullable private BondedCompanionApi subscribedApi;
    @Nullable private AutoCloseable subscription;
    private boolean closed;

    BondedCompanionPanelSnapshotCache(
            @Nonnull Supplier<BondedCompanionApi> api,
            @Nonnull Executor worker,
            @Nonnull LongSupplier monotonicClock,
            @Nonnull Settings settings) {
        this(api, worker, null, monotonicClock, settings);
    }

    private BondedCompanionPanelSnapshotCache(
            Supplier<BondedCompanionApi> api,
            Executor worker,
            @Nullable AutoCloseable ownedWorker,
            LongSupplier monotonicClock,
            Settings settings) {
        this.api = Objects.requireNonNull(api, "api");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.ownedWorker = ownedWorker;
        this.monotonicClock = Objects.requireNonNull(
                monotonicClock, "monotonicClock");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    static BondedCompanionPanelSnapshotCache production(
            Supplier<BondedCompanionApi> api) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY), task -> {
                    Thread thread = new Thread(
                            task, "tamework-bonded-panel-loader");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        return new BondedCompanionPanelSnapshotCache(
                api, worker, worker::shutdownNow,
                System::nanoTime, DEFAULT_SETTINGS);
    }

    /** Returns immediately, scheduling at most one load for this roster. */
    Snapshot peek(@Nonnull UUID ownerUuid, @Nonnull String rosterId) {
        Key key = new Key(ownerUuid, rosterId);
        Load load = null;
        Snapshot snapshot;
        long now = monotonicClock.getAsLong();
        synchronized (lock) {
            if (closed) return Snapshot.closed();
            evictIdle(now);
            Entry entry = entries.get(key);
            if (entry == null) {
                evictForCapacity();
                entry = new Entry(now);
                entries.put(key, entry);
            } else {
                entry.lastAccessNanos = now;
                if (entry.state == State.READY
                        && elapsed(now, entry.loadedAtNanos)
                        >= settings.refreshAfterNanos()) {
                    invalidate(entry);
                }
            }
            if (shouldLoad(entry, now)) load = begin(key, entry);
            snapshot = snapshot(entry);
        }
        submit(load);
        return snapshot;
    }

    /**
     * Starts a loader-owned read before a page needs the roster. This is
     * intentionally equivalent to {@link #peek(UUID, String)}: it never reads
     * durable state on the caller thread.
     */
    void warm(@Nonnull UUID ownerUuid, @Nonnull String rosterId) {
        peek(ownerUuid, rosterId);
    }

    /** Invalidates one known roster and immediately schedules its replacement. */
    void refresh(@Nonnull UUID ownerUuid, @Nonnull String rosterId) {
        Key key = new Key(ownerUuid, rosterId);
        Load load;
        long now = monotonicClock.getAsLong();
        synchronized (lock) {
            if (closed) return;
            evictIdle(now);
            Entry entry = entries.get(key);
            if (entry == null) {
                evictForCapacity();
                entry = new Entry(now);
                entries.put(key, entry);
            }
            entry.lastAccessNanos = now;
            invalidate(entry);
            if (entry.loading) {
                entry.reloadRequested = true;
                return;
            }
            load = begin(key, entry);
        }
        submit(load);
    }

    /** Rebinds active snapshots after the composition replaces its bonded API. */
    void refreshBoundApi() {
        List<Load> loads = new java.util.ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            for (Map.Entry<Key, Entry> candidate : entries.entrySet()) {
                Entry entry = candidate.getValue();
                invalidate(entry);
                if (entry.loading) entry.reloadRequested = true;
                else loads.add(begin(candidate.getKey(), entry));
            }
        }
        for (Load load : loads) submit(load);
    }

    /** Removes every open roster for an owner without creating new entries. */
    void evictOwner(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) return;
        synchronized (lock) {
            entries.keySet().removeIf(key -> ownerUuid.equals(key.ownerUuid()));
        }
    }

    private void changed(BondedCompanionChangedEvent event) {
        if (event == null) return;
        Load load = null;
        long now = monotonicClock.getAsLong();
        Key key;
        try {
            key = new Key(event.ownerUuid(), event.rosterId());
        } catch (RuntimeException invalid) {
            return;
        }
        synchronized (lock) {
            if (closed) return;
            evictIdle(now);
            Entry entry = entries.get(key);
            if (entry == null) {
                evictForCapacity();
                entry = new Entry(now);
                entries.put(key, entry);
            }
            entry.lastAccessNanos = now;
            invalidate(entry);
            if (entry.loading) entry.reloadRequested = true;
            else load = begin(key, entry);
        }
        submit(load);
    }

    private void invalidate(Entry entry) {
        entry.generation = nextGeneration(entry.generation);
        entry.state = State.REFRESHING;
        entry.consecutiveFailures = 0;
        entry.nextRetryNanos = 0L;
    }

    private boolean shouldLoad(Entry entry, long now) {
        if (entry.loading || entry.state == State.READY) return false;
        return entry.state != State.FAILED || now >= entry.nextRetryNanos;
    }

    private Load begin(Key key, Entry entry) {
        entry.loading = true;
        entry.reloadRequested = false;
        entry.state = State.REFRESHING;
        return new Load(key, entry, entry.generation);
    }

    private void submit(@Nullable Load load) {
        if (load == null) return;
        try {
            worker.execute(() -> invoke(load));
        } catch (RuntimeException rejected) {
            failed(load);
        }
    }

    private void invoke(Load load) {
        if (!current(load)) {
            releaseStale(load);
            return;
        }
        try {
            BondedCompanionApi current = Objects.requireNonNullElseGet(
                    api.get(), BondedCompanionApi::unavailable);
            if (!current.availability().available()) {
                failed(load);
                return;
            }
            ensureSubscribed(current);
            var result = current.list(
                    load.key().ownerUuid(), load.key().rosterId());
            if (result == null) {
                failed(load);
                return;
            }
            result.whenComplete((resolved, failure) -> {
                if (failure != null || !valid(load.key(), resolved)) {
                    failed(load);
                } else {
                    published(load, resolved.value());
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            failed(load);
        }
    }

    private void releaseStale(Load load) {
        Load successor = null;
        synchronized (lock) {
            Entry current = entries.get(load.key());
            if (closed || current != load.entry() || !current.loading
                    || current.generation == load.generation()) return;
            current.loading = false;
            if (current.reloadRequested) successor = begin(load.key(), current);
        }
        submit(successor);
    }

    private boolean valid(
            Key key,
            @Nullable BondedCompanionResult<
                    List<BondedCompanionProfileView>> result) {
        if (result == null || !result.successful() || result.value() == null) {
            return false;
        }
        for (BondedCompanionProfileView profile : result.value()) {
            if (profile == null
                    || !key.ownerUuid().equals(profile.ownerUuid())
                    || !key.rosterId().equals(profile.rosterId())) return false;
        }
        return true;
    }

    private void ensureSubscribed(BondedCompanionApi current) {
        synchronized (lock) {
            if (closed || subscribedApi == current && subscription != null) {
                return;
            }
        }
        AutoCloseable candidate = current.subscribe(this::changed);
        AutoCloseable replaced = null;
        boolean retain;
        synchronized (lock) {
            retain = !closed && subscribedApi != current;
            if (retain) {
                replaced = subscription;
                subscribedApi = current;
                subscription = candidate;
            }
        }
        if (!retain) closeQuietly(candidate);
        closeQuietly(replaced);
    }

    private void published(Load load, List<BondedCompanionProfileView> profiles) {
        Load successor = null;
        synchronized (lock) {
            Entry current = entries.get(load.key());
            if (closed || current != load.entry()) return;
            if (current.generation != load.generation()) {
                current.loading = false;
                if (current.reloadRequested) successor = begin(load.key(), current);
            } else {
                current.profiles = List.copyOf(profiles);
                current.state = State.READY;
                current.loading = false;
                current.reloadRequested = false;
                current.consecutiveFailures = 0;
                current.nextRetryNanos = 0L;
                current.loadedAtNanos = monotonicClock.getAsLong();
            }
        }
        submit(successor);
    }

    private void failed(Load load) {
        Load successor = null;
        long now = monotonicClock.getAsLong();
        synchronized (lock) {
            Entry current = entries.get(load.key());
            if (closed || current != load.entry()) return;
            if (current.generation != load.generation()) {
                current.loading = false;
                if (current.reloadRequested) successor = begin(load.key(), current);
            } else {
                current.loading = false;
                current.reloadRequested = false;
                current.state = State.FAILED;
                current.consecutiveFailures = Math.min(
                        63, current.consecutiveFailures + 1);
                current.nextRetryNanos = safeAdd(
                        now, retryDelay(current.consecutiveFailures));
            }
        }
        submit(successor);
    }

    private boolean current(Load load) {
        synchronized (lock) {
            return !closed && entries.get(load.key()) == load.entry()
                    && load.entry().loading
                    && load.entry().generation == load.generation();
        }
    }

    private long retryDelay(int failures) {
        long delay = settings.retryBaseNanos();
        for (int attempt = 1; attempt < failures; attempt++) {
            if (delay >= settings.retryMaxNanos() / 2L) {
                return settings.retryMaxNanos();
            }
            delay *= 2L;
        }
        return Math.min(delay, settings.retryMaxNanos());
    }

    private Snapshot snapshot(Entry entry) {
        return new Snapshot(entry.profiles, entry.generation, entry.state);
    }

    private void evictIdle(long now) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (elapsed(now, entry.lastAccessNanos)
                    >= settings.idleEvictionNanos()) iterator.remove();
        }
    }

    private void evictForCapacity() {
        while (entries.size() >= settings.maximumEntries()) {
            Iterator<Key> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static long elapsed(long now, long then) {
        if (now < then) return 0L;
        try {
            return Math.subtractExact(now, then);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long nextGeneration(long generation) {
        return generation == Long.MAX_VALUE ? 1L : generation + 1L;
    }

    @Override
    public void close() {
        AutoCloseable currentSubscription;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            entries.clear();
            currentSubscription = subscription;
            subscription = null;
            subscribedApi = null;
        }
        closeQuietly(currentSubscription);
        closeQuietly(ownedWorker);
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Shutdown and invalidation are best-effort and idempotent.
        }
    }

    enum State { REFRESHING, READY, FAILED, CLOSED }

    record Snapshot(
            @Nonnull List<BondedCompanionProfileView> profiles,
            long generation,
            @Nonnull State state) {
        Snapshot {
            profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
            state = Objects.requireNonNull(state, "state");
        }

        boolean trusted() {
            return state == State.READY;
        }

        static Snapshot closed() {
            return new Snapshot(List.of(), 0L, State.CLOSED);
        }
    }

    record Settings(
            int maximumEntries,
            long refreshAfterNanos,
            long idleEvictionNanos,
            long retryBaseNanos,
            long retryMaxNanos) {
        Settings {
            if (maximumEntries <= 0 || refreshAfterNanos <= 0L
                    || idleEvictionNanos <= 0L || retryBaseNanos <= 0L
                    || retryMaxNanos < retryBaseNanos) {
                throw new IllegalArgumentException("invalid panel cache settings");
            }
        }
    }

    private record Key(@Nonnull UUID ownerUuid, @Nonnull String rosterId) {
        private Key {
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = Objects.requireNonNull(rosterId, "rosterId").trim();
            if (rosterId.isEmpty()) {
                throw new IllegalArgumentException("rosterId is required");
            }
        }
    }

    private record Load(Key key, Entry entry, long generation) {}

    private static final class Entry {
        private List<BondedCompanionProfileView> profiles = List.of();
        private long generation = 1L;
        private State state = State.REFRESHING;
        private boolean loading;
        private boolean reloadRequested;
        private int consecutiveFailures;
        private long loadedAtNanos;
        private long lastAccessNanos;
        private long nextRetryNanos;

        private Entry(long now) {
            lastAccessNanos = now;
        }
    }
}
