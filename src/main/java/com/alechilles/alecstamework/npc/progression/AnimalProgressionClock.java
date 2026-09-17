package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.persistence.AnimalProgressionClockStore;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maintains cumulative real-time progression that is eligible under the server owner policy.
 *
 * <p>The clock is event-driven: player presence transitions and consumers settle elapsed time.
 * It neither loads chunks nor holds entity references. A null owner uses the server runtime clock
 * directly. Process downtime never contributes because {@link CompanionRuntimeClock} advances only
 * while the server systems tick.</p>
 */
public final class AnimalProgressionClock implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(AnimalProgressionClock.class.getName());
    private static final AnimalProgressionClock INSTANCE = new AnimalProgressionClock();
    private static final long CHECKPOINT_INTERVAL_MS = 30_000L;
    private static final double HOURS_TO_MILLIS = 3_600_000.0;

    private final Object lock = new Object();
    private final Map<UUID, OwnerClock> ownerClocks = new LinkedHashMap<>();

    @Nullable private Path dataDirectory;
    @Nullable private ExecutorService checkpointExecutor;
    private boolean started;
    private boolean persistenceAvailable;
    private long lastCheckpointAtMs;
    private long serverCumulativeRuntimeMs;
    private long serverLastSettledAtMs;
    private boolean checkpointQueued;
    private boolean checkpointPending;
    @Nonnull private Policy policy = Policy.defaults();

    private AnimalProgressionClock() {
    }

    @Nonnull
    public static AnimalProgressionClock get() {
        return INSTANCE;
    }

    /** Starts the universe-scoped clock and restores counters without replaying server downtime. */
    public void start(@Nonnull Path targetDataDirectory) {
        if (targetDataDirectory == null) {
            throw new IllegalArgumentException("Target data directory is required");
        }
        close();
        AnimalProgressionClockStore.LoadResult restored = AnimalProgressionClockStore.load(targetDataDirectory);
        if (!restored.available()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Cannot read animal progression clock at {0}; preserving the file and disabling checkpoint writes.",
                    AnimalProgressionClockStore.resolveFile(targetDataDirectory));
        }
        long nowMs = CompanionRuntimeClock.nowMs();
        synchronized (lock) {
            dataDirectory = targetDataDirectory.toAbsolutePath().normalize();
            ownerClocks.clear();
            for (Map.Entry<UUID, AnimalProgressionClockStore.OwnerState> entry : restored.owners().entrySet()) {
                ownerClocks.put(entry.getKey(), OwnerClock.offlineAt(
                        Math.max(0L, entry.getValue().cumulativeEligibleMs()),
                        nowMs,
                        entry.getValue().offlineElapsedMs()
                ));
            }
            policy = resolveRuntimePolicy();
            persistenceAvailable = restored.available();
            checkpointExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "animal-progression-clock-checkpoint");
                thread.setDaemon(true);
                return thread;
            });
            lastCheckpointAtMs = nowMs;
            serverCumulativeRuntimeMs = restored.serverCumulativeRuntimeMs();
            serverLastSettledAtMs = nowMs;
            checkpointQueued = false;
            checkpointPending = false;
            started = true;
        }
    }

    /** Records a connected owner without accessing player or world state. */
    public void onOwnerConnected(@Nullable UUID ownerId) {
        transition(ownerId, true);
    }

    /** Records a disconnected owner without accessing player or world state. */
    public void onOwnerDisconnected(@Nullable UUID ownerId) {
        transition(ownerId, false);
    }

    /**
     * Settles tracked owners under the prior policy before using the supplied policy going forward.
     * Call this immediately after a global settings change is committed and published.
     */
    public void onPolicyChanged(@Nonnull TwNeedsConfig.TickPolicySettings newPolicy) {
        if (newPolicy == null) {
            throw new IllegalArgumentException("Progression policy is required");
        }
        synchronized (lock) {
            long nowMs = CompanionRuntimeClock.nowMs();
            if (started) {
                settleAll(nowMs);
            }
            policy = Policy.from(newPolicy);
            scheduleCheckpoint(nowMs, true);
        }
    }

    /**
     * Returns cumulative eligible real milliseconds for an owner. Null is the server runtime clock.
     * Unknown owners begin at the current instant and are never credited retroactively.
     */
    public long current(@Nullable UUID ownerId) {
        if (ownerId == null) {
            synchronized (lock) {
                if (!started) {
                    return CompanionRuntimeClock.nowMs();
                }
                long nowMs = CompanionRuntimeClock.nowMs();
                settleServer(nowMs);
                scheduleCheckpoint(nowMs, false);
                return serverCumulativeRuntimeMs;
            }
        }
        synchronized (lock) {
            if (!started) {
                return 0L;
            }
            long nowMs = CompanionRuntimeClock.nowMs();
            OwnerClock ownerClock = ownerClocks.computeIfAbsent(
                    ownerId, ignored -> OwnerClock.offlineAt(0L, nowMs, 0L)
            );
            settle(ownerClock, nowMs);
            scheduleCheckpoint(nowMs, false);
            return ownerClock.cumulativeEligibleMs;
        }
    }

    @Override
    public void close() {
        ExecutorService executor;
        Path target;
        Snapshot snapshot;
        boolean writable;
        synchronized (lock) {
            if (!started) {
                return;
            }
            long nowMs = CompanionRuntimeClock.nowMs();
            settleAll(nowMs);
            settleServer(nowMs);
            executor = checkpointExecutor;
            target = dataDirectory;
            snapshot = snapshot(nowMs);
            writable = persistenceAvailable;
            checkpointExecutor = null;
            dataDirectory = null;
            started = false;
            persistenceAvailable = false;
        }
        if (executor == null || target == null || !writable) {
            if (executor != null) {
                executor.shutdown();
            }
            return;
        }
        try {
            executor.execute(() -> AnimalProgressionClockStore.save(
                    target, snapshot.owners(), snapshot.serverCumulativeRuntimeMs()
            ));
        } catch (RuntimeException ignored) {
            // A rejected final checkpoint is recoverable; earlier accepted work remains ordered.
        }
        executor.shutdown();
        try {
            executor.awaitTermination(3L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void clearForTests() {
        close();
        synchronized (lock) {
            ownerClocks.clear();
            policy = Policy.defaults();
            lastCheckpointAtMs = 0L;
            serverCumulativeRuntimeMs = 0L;
            serverLastSettledAtMs = 0L;
            checkpointQueued = false;
            checkpointPending = false;
        }
        CompanionRuntimeClock.resetForTests();
    }

    private void transition(@Nullable UUID ownerId, boolean nextOnline) {
        if (ownerId == null) {
            return;
        }
        synchronized (lock) {
            if (!started) {
                return;
            }
            long nowMs = CompanionRuntimeClock.nowMs();
            OwnerClock ownerClock = ownerClocks.computeIfAbsent(
                    ownerId, ignored -> OwnerClock.offlineAt(0L, nowMs, 0L)
            );
            settle(ownerClock, nowMs);
            if (ownerClock.online != nextOnline) {
                ownerClock.online = nextOnline;
                ownerClock.offlineStartedAtMs = nextOnline ? 0L : nowMs;
            }
            scheduleCheckpoint(nowMs, true);
        }
    }

    private void settleAll(long nowMs) {
        for (OwnerClock ownerClock : ownerClocks.values()) {
            settle(ownerClock, nowMs);
        }
    }

    private void settleServer(long nowMs) {
        if (nowMs <= serverLastSettledAtMs) {
            return;
        }
        serverCumulativeRuntimeMs = saturatingAdd(
                serverCumulativeRuntimeMs, nowMs - serverLastSettledAtMs
        );
        serverLastSettledAtMs = nowMs;
    }

    private void settle(@Nonnull OwnerClock ownerClock, long nowMs) {
        if (nowMs <= ownerClock.lastSettledAtMs) {
            return;
        }
        long elapsedMs = nowMs - ownerClock.lastSettledAtMs;
        long eligibleMs = eligibleElapsed(ownerClock, ownerClock.lastSettledAtMs, nowMs, elapsedMs);
        ownerClock.cumulativeEligibleMs = saturatingAdd(ownerClock.cumulativeEligibleMs, eligibleMs);
        ownerClock.lastSettledAtMs = nowMs;
    }

    private long eligibleElapsed(@Nonnull OwnerClock ownerClock,
                                 long fromMs,
                                 long toMs,
                                 long elapsedMs) {
        if (policy.mode == TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER || ownerClock.online) {
            return elapsedMs;
        }
        if (policy.offlineMultiplier <= 0.0) {
            return 0L;
        }
        long graceEndMs = saturatingAdd(ownerClock.offlineStartedAtMs, policy.graceMs);
        if (toMs <= graceEndMs) {
            return 0L;
        }
        long effectiveStartMs = Math.max(fromMs, graceEndMs);
        return scaleDuration(toMs - effectiveStartMs, policy.offlineMultiplier);
    }

    private void scheduleCheckpoint(long nowMs, boolean force) {
        if (!started || !persistenceAvailable || dataDirectory == null || checkpointExecutor == null) {
            return;
        }
        if (!force && nowMs - lastCheckpointAtMs < CHECKPOINT_INTERVAL_MS) {
            return;
        }
        if (checkpointQueued) {
            checkpointPending = true;
            return;
        }
        settleAll(nowMs);
        settleServer(nowMs);
        Snapshot checkpoint = snapshot(nowMs);
        Path target = dataDirectory;
        ExecutorService executor = checkpointExecutor;
        lastCheckpointAtMs = nowMs;
        checkpointQueued = true;
        try {
            executor.execute(() -> writeCheckpoint(target, checkpoint));
        } catch (RuntimeException ignored) {
            // The next event or bounded checkpoint retries with a complete immutable snapshot.
            checkpointQueued = false;
        }
    }

    @Nonnull
    private Snapshot snapshot(long nowMs) {
        LinkedHashMap<UUID, AnimalProgressionClockStore.OwnerState> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, OwnerClock> entry : ownerClocks.entrySet()) {
            OwnerClock ownerClock = entry.getValue();
            long offlineElapsedMs = ownerClock.online
                    ? 0L
                    : Math.max(0L, nowMs - ownerClock.offlineStartedAtMs);
            snapshot.put(entry.getKey(), new AnimalProgressionClockStore.OwnerState(
                    ownerClock.cumulativeEligibleMs, offlineElapsedMs
            ));
        }
        return new Snapshot(Map.copyOf(snapshot), serverCumulativeRuntimeMs);
    }

    private void writeCheckpoint(@Nonnull Path target, @Nonnull Snapshot checkpoint) {
        boolean saved = AnimalProgressionClockStore.save(
                target, checkpoint.owners(), checkpoint.serverCumulativeRuntimeMs()
        );
        if (!saved) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Cannot save animal progression clock at {0}; a later checkpoint will retry.",
                    AnimalProgressionClockStore.resolveFile(target));
        }
        synchronized (lock) {
            checkpointQueued = false;
            if (!checkpointPending || !started) {
                checkpointPending = false;
                return;
            }
            checkpointPending = false;
            long nowMs = CompanionRuntimeClock.nowMs();
            settleAll(nowMs);
            settleServer(nowMs);
            scheduleCheckpoint(nowMs, true);
        }
    }

    @Nonnull
    private static Policy resolveRuntimePolicy() {
        TameworkRuntimeSettings settings = TameworkRuntimeSettings.currentOrNull();
        return settings == null
                ? Policy.defaults()
                : Policy.from(settings.resolveNeedsTickPolicy(null));
    }

    private static long scaleDuration(long durationMs, double multiplier) {
        if (durationMs <= 0L || !Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0L;
        }
        double scaled = durationMs * multiplier;
        if (!Double.isFinite(scaled) || scaled <= 0.0) {
            return 0L;
        }
        return scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(scaled);
    }

    private static long resolveGraceMs(double graceHours) {
        if (!Double.isFinite(graceHours) || graceHours <= 0.0) {
            return 0L;
        }
        double graceMs = graceHours * HOURS_TO_MILLIS;
        return !Double.isFinite(graceMs) || graceMs >= Long.MAX_VALUE
                ? Long.MAX_VALUE : (long) Math.floor(graceMs);
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L || left >= Long.MAX_VALUE) {
            return left;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class OwnerClock {
        private long cumulativeEligibleMs;
        private boolean online;
        private long offlineStartedAtMs;
        private long lastSettledAtMs;

        private OwnerClock(long cumulativeEligibleMs,
                           boolean online,
                           long offlineStartedAtMs,
                           long lastSettledAtMs) {
            this.cumulativeEligibleMs = cumulativeEligibleMs;
            this.online = online;
            this.offlineStartedAtMs = offlineStartedAtMs;
            this.lastSettledAtMs = lastSettledAtMs;
        }

        private static OwnerClock offlineAt(long cumulativeEligibleMs,
                                            long nowMs,
                                            long priorOfflineElapsedMs) {
            long offlineElapsedMs = Math.max(0L, priorOfflineElapsedMs);
            // This session epoch may be negative: saved grace consumption predates runtime zero.
            long offlineStartedAtMs = nowMs - offlineElapsedMs;
            return new OwnerClock(cumulativeEligibleMs, false, offlineStartedAtMs, nowMs);
        }
    }

    private record Snapshot(@Nonnull Map<UUID, AnimalProgressionClockStore.OwnerState> owners,
                            long serverCumulativeRuntimeMs) {
    }

    private record Policy(@Nonnull TwNeedsConfig.TickPolicyMode mode,
                          long graceMs,
                          double offlineMultiplier) {
        @Nonnull
        private static Policy defaults() {
            return from(TwNeedsConfig.TickPolicySettings.of(
                    TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, 72.0, 1.0
            ));
        }

        @Nonnull
        private static Policy from(@Nonnull TwNeedsConfig.TickPolicySettings settings) {
            return new Policy(
                    settings.getMode(),
                    resolveGraceMs(settings.getOwnerOfflineGraceHours()),
                    settings.getOwnerOfflineDecayMultiplier()
            );
        }
    }
}
