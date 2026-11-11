package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.api.NetworkSettingsDocument;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps this server's {@link NetworkSettingsLayer} in step with a network's
 * {@link NetworkSettingsStore}, and runs the admin actions that move settings between the two.
 * Without a store it only serves the layer saved on disk.
 *
 * <p><b>Thread.</b> Every store call and every settings file rewrite runs on the single
 * {@code tamework-network-settings} thread this object owns. Callers get a future.</p>
 *
 * <p><b>Check.</b> One {@code load} of one small document every {@value #CHECK_INTERVAL_SECONDS}
 * seconds, plus one right after {@link #register} and each {@link #notifyChanged}. The timer is
 * needed because other servers change the document and a network mod may never send a notice. A
 * failed check keeps the saved layer and is tried again at the next one; nothing is queued.</p>
 *
 * <p><b>Other documents.</b> {@link #follow} adds a document of the same store, under its own key,
 * that another part of Tamework owns. Its task runs right after the settings check, on the same
 * thread, and again on {@link #notifyChanged} with its key. Leaving the network's settings
 * ({@link #leave}) does not stop these: it only concerns settings.</p>
 */
public final class NetworkSettingsSync implements AutoCloseable {
    static final long CHECK_INTERVAL_SECONDS = 60L;
    private static final long WARN_INTERVAL_MS = 300_000L;

    /** Work on the registered store for one followed document. Runs on this object's thread. */
    @FunctionalInterface
    public interface StoreTask {
        void run(@Nonnull NetworkSettingsStore store) throws Exception;
    }

    /** How a {@link #publish} ended. */
    public enum PublishResult {
        /** The network now has this server's settings. */
        SAVED,
        /** Another server saved first. The network's newer settings were read; nothing was written. */
        CONFLICT,
        /** The store failed. */
        FAILED,
        /** No store is installed, or this server left the network. */
        UNAVAILABLE
    }

    private final ScheduledExecutorService executor;
    private final Supplier<Path> cacheFile;
    private final NetworkSettingsFiles files;
    private final Runnable settingsChanged;
    @Nullable
    private final HytaleLogger logger;
    /** Followed documents by key. Read on the executor thread, added to from any thread. */
    private final Map<String, StoreTask> followed = new ConcurrentHashMap<>();

    @Nullable
    private volatile NetworkSettingsStore store;
    private volatile boolean closed;
    private long nextWarnAtMs = Long.MIN_VALUE;

    /**
     * @param executor        a dedicated single-thread executor, owned by this object
     * @param cacheFile       where the layer is saved
     * @param files           this server's settings files
     * @param settingsChanged drops every settings cache and applies the new values; called on the
     *                        executor thread after the layer or a local file changed
     */
    public NetworkSettingsSync(@Nonnull ScheduledExecutorService executor, @Nonnull Supplier<Path> cacheFile,
                               @Nonnull NetworkSettingsFiles files, @Nonnull Runnable settingsChanged,
                               @Nullable HytaleLogger logger) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile");
        this.files = Objects.requireNonNull(files, "files");
        this.settingsChanged = Objects.requireNonNull(settingsChanged, "settingsChanged");
        this.logger = logger;
    }

    /** Starts the periodic check. */
    public void start() {
        executor.scheduleWithFixedDelay(this::checkAll, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Follows one more document of the store. {@code task} runs on this object's thread when a
     * store is registered, at every periodic check, and on {@link #notifyChanged} with
     * {@code key}; the owner calls that too when it has something to save. It never runs without
     * a store. A task that throws is logged and runs again at the next check. Following a key
     * again replaces its task.
     *
     * @throws IllegalArgumentException for the settings key, which this object reads itself
     */
    public void follow(@Nonnull String key, @Nonnull StoreTask task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        if (NetworkSettingsStore.SERVER_KEY.equals(key)) {
            throw new IllegalArgumentException("the settings document cannot be followed");
        }
        followed.put(key, task);
        notifyChanged(key);
    }

    /** Whether a store is installed. */
    public boolean active() {
        return store != null && !closed;
    }

    /**
     * Installs the store and reads the network's settings in the background.
     *
     * @throws IllegalStateException when a store is installed already or this object is closed
     */
    @Nonnull
    public synchronized AutoCloseable register(@Nonnull NetworkSettingsStore newStore) {
        Objects.requireNonNull(newStore, "store");
        if (closed) {
            throw new IllegalStateException("network settings sync is closed");
        }
        if (store != null) {
            throw new IllegalStateException("a network settings store is already registered");
        }
        store = newStore;
        notifyChanged(NetworkSettingsStore.SERVER_KEY);
        return new Registration(this, newStore);
    }

    private synchronized void unregister(@Nonnull NetworkSettingsStore registered) {
        if (store == registered) {
            store = null;
        }
    }

    /** The handle {@link #register} returns. A public class, so a mod that closes it by reflection can. */
    public static final class Registration implements AutoCloseable {
        private final NetworkSettingsSync sync;
        private final NetworkSettingsStore registered;

        private Registration(@Nonnull NetworkSettingsSync sync, @Nonnull NetworkSettingsStore registered) {
            this.sync = sync;
            this.registered = registered;
        }

        @Override
        public void close() {
            sync.unregister(registered);
        }
    }

    /** Reads {@code key} soon instead of at the next check. Safe from any thread. */
    public void notifyChanged(@Nonnull String key) {
        Objects.requireNonNull(key, "key");
        if (!active()) {
            return;
        }
        Runnable work;
        if (NetworkSettingsStore.SERVER_KEY.equals(key)) {
            work = this::checkAll;
        } else {
            StoreTask task = followed.get(key);
            if (task == null) {
                return;
            }
            work = () -> runFollowed(key, task);
        }
        try {
            executor.execute(work);
        } catch (RejectedExecutionException shuttingDown) {
            // Closed between the check and the submit.
        }
    }

    /**
     * Makes this server's settings in effect the network's settings. This server then has no
     * overrides, because its settings are the network's.
     */
    @Nonnull
    public CompletableFuture<PublishResult> publish() {
        return submit(() -> {
            NetworkSettingsStore current = store;
            NetworkSettingsLayer.State state = NetworkSettingsLayer.state();
            if (current == null || state.left()) {
                return PublishResult.UNAVAILABLE;
            }
            try {
                JsonObject sections = files.effectiveSections();
                if (!current.save(NetworkSettingsStore.SERVER_KEY, NetworkSettingsLayer.toJson(sections), state.revision())) {
                    check();
                    return PublishResult.CONFLICT;
                }
                NetworkSettingsDocument saved = current.load(NetworkSettingsStore.SERVER_KEY);
                if (saved == null) {
                    throw new IllegalStateException("the store has no document right after a save");
                }
                // The layer first, then the files: a stop in between leaves files that equal the layer.
                NetworkSettingsLayer.install(new NetworkSettingsLayer.State(saved.revision(), false,
                        JsonParser.parseString(saved.json()).getAsJsonObject()), cacheFile.get(), logger);
                files.clearOverrides();
                settingsChanged.run();
                return PublishResult.SAVED;
            } catch (Exception failure) {
                warn(failure, "Could not publish this server's settings to the network.");
                return PublishResult.FAILED;
            }
        }, PublishResult.FAILED);
    }

    /** Drops this server's overrides so it follows the network for every setting. False when it follows none. */
    @Nonnull
    public CompletableFuture<Boolean> useNetworkSettings() {
        return submit(() -> {
            if (!NetworkSettingsLayer.active()) {
                return false;
            }
            files.clearOverrides();
            settingsChanged.run();
            return true;
        }, false);
    }

    /**
     * Stops following the network on this server: the settings in effect are written into its own
     * files in full, so nothing changes now, and later network changes are ignored. False when this
     * server follows no network.
     */
    @Nonnull
    public CompletableFuture<Boolean> leave() {
        return submit(() -> {
            NetworkSettingsLayer.State state = NetworkSettingsLayer.state();
            if (!state.active()) {
                return false;
            }
            files.flatten();
            NetworkSettingsLayer.install(new NetworkSettingsLayer.State(0L, true, null), cacheFile.get(), logger);
            settingsChanged.run();
            return true;
        }, false);
    }

    /** Follows the network again after {@link #leave}; this server's files are converted to overrides as on a first join. */
    @Nonnull
    public CompletableFuture<Boolean> rejoin() {
        return submit(() -> {
            if (!NetworkSettingsLayer.state().left()) {
                return false;
            }
            NetworkSettingsLayer.install(NetworkSettingsLayer.State.NONE, cacheFile.get(), logger);
            checkSafely();
            return true;
        }, false);
    }

    /** How many settings this server's files set; its overrides while it follows a network. Blocks on file I/O. */
    @Nonnull
    public CompletableFuture<Integer> localSettingCount() {
        return submit(files::localSettingCount, 0);
    }

    @Override
    public void close() {
        closed = true;
        store = null;
        executor.shutdownNow();
    }

    /** The settings check, then every followed document. One that fails does not stop the others. */
    private void checkAll() {
        checkSafely();
        followed.forEach(this::runFollowed);
    }

    private void runFollowed(@Nonnull String key, @Nonnull StoreTask task) {
        NetworkSettingsStore current = store;
        if (current == null || closed) {
            return;
        }
        try {
            task.run(current);
        } catch (Exception failure) {
            warn(failure, "Could not sync the network document '" + key
                    + "'; this server keeps what it has and tries again at the next check.");
        }
    }

    private void checkSafely() {
        try {
            check();
        } catch (Exception failure) {
            warn(failure, "Could not read the network's settings; this server keeps the last ones it read.");
        }
    }

    private void check() throws Exception {
        NetworkSettingsStore current = store;
        NetworkSettingsLayer.State state = NetworkSettingsLayer.state();
        if (current == null || closed || state.left()) {
            return;
        }
        NetworkSettingsDocument document = current.load(NetworkSettingsStore.SERVER_KEY);
        if (document == null || (state.sections() != null && document.revision() == state.revision())) {
            return;
        }
        JsonObject sections = JsonParser.parseString(document.json()).getAsJsonObject();
        if (state.sections() == null) {
            if (logger != null) {
                logger.at(Level.INFO).log("This server now follows the network's Tamework settings. Values it had"
                        + " that differ from the network's and from the defaults stay as its overrides.");
            }
            files.convertToOverrides(sections);
        }
        NetworkSettingsLayer.install(new NetworkSettingsLayer.State(document.revision(), false, sections),
                cacheFile.get(), logger);
        settingsChanged.run();
    }

    @Nonnull
    private <T> CompletableFuture<T> submit(@Nonnull Supplier<T> work, @Nonnull T whenClosed) {
        try {
            return CompletableFuture.supplyAsync(work, executor);
        } catch (RejectedExecutionException shuttingDown) {
            return CompletableFuture.completedFuture(whenClosed);
        }
    }

    private void warn(@Nonnull Throwable cause, @Nonnull String message) {
        long now = System.currentTimeMillis();
        if (logger != null && now >= nextWarnAtMs) {
            nextWarnAtMs = now + WARN_INTERVAL_MS;
            logger.at(Level.WARNING).withCause(cause).log(message);
        }
    }
}
