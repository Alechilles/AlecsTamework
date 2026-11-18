package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.api.NetworkSettingsDocument;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.alechilles.alecstamework.items.components.TameworkCompanionCardPlacementComponent;
import com.alechilles.alecstamework.items.components.TameworkCompanionCardPreferenceComponent;
import com.alechilles.alecstamework.items.components.TameworkCompanionGroupsComponent;
import com.alechilles.alecstamework.items.components.TameworkCompanionViewsComponent;
import com.alechilles.alecstamework.items.components.TameworkHudPlacementComponent;
import com.alechilles.alecstamework.items.components.TameworkInspectPreferencesComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;

/**
 * Keeps each player's Tamework preferences (companion groups and views, HUD and card placement,
 * card F behaviour, inspect options) in a document of Tamework's own, so they no longer depend on
 * the engine's player save. Servers that turn player saving off, as Runeteria does, lost them at
 * every rejoin.
 *
 * <p><b>Where.</b> One document per player: this server's {@code Players/<uuid>.json} under the
 * Tamework universe folder, and, while a network registered a {@link NetworkSettingsStore}, the
 * store's {@code player/<uuid>} document, which is then the source of truth and follows the player
 * across servers. The six components stay the runtime form; this class only loads and saves them.
 * Keys a newer Tamework added are kept on save, and a document of a newer format is never saved
 * over.</p>
 *
 * <p><b>Join.</b> {@code PlayerConnectEvent} is asynchronous and the login waits for it, so the
 * document is read on the network settings thread and put on the player's holder before the player
 * enters a world, waiting at most {@value #CONNECT_WAIT_MS} ms. A read that takes longer is put on
 * the live entity inside {@code world.execute} once it finishes. A preference changed before that
 * keeps its new value. A player with no document anywhere offers what the holder carries as the
 * first one. Until a read succeeds nothing is saved for that player, so a store that is down cannot
 * be overwritten with defaults; a player read without a store is read again when a store appears.</p>
 *
 * <p><b>Change.</b> The components' write helpers call {@link #changed} on the world thread, which
 * encodes the document there and queues one save per player; quick changes collapse into the
 * latest. A network save expects the revision this server last read or wrote. If another server
 * wrote since, this server's save wins only while the player is still here; otherwise it is dropped,
 * so a late retry cannot roll back a newer change made elsewhere.</p>
 *
 * <p><b>Store down.</b> One failed store call marks the store down for {@value #RETRY_MS} ms. Until
 * then no player's read or save calls it; each is retried once that time has passed, while the
 * player is online for a read. So a dead store costs one call per {@value #RETRY_MS} ms per waiting
 * player at most, never a call per player per tick. Joins do not wait while the store is down.</p>
 *
 * <p><b>Lifecycle.</b> No thread of its own: all I/O runs on the {@link NetworkSettingsSync}
 * thread. {@link #close} saves what is queued, waiting up to {@value #FLUSH_TIMEOUT_MS} ms; call it
 * while a network store is still registered.</p>
 */
public final class PlayerSettingsSync implements AutoCloseable {
    /** Prefix of a player's document key in the network's store, followed by the player's UUID. */
    public static final String KEY_PREFIX = "player/";
    static final int FORMAT = 1;
    static final String FORMAT_FIELD = "Format";
    static final String COMPONENTS_FIELD = "Components";
    private static final String DIRECTORY = "Players";
    static final long CONNECT_WAIT_MS = 2_000L;
    private static final long RETRY_MS = 30_000L;
    private static final long LATE_APPLY_DELAY_MS = 1_000L;
    private static final int LATE_APPLY_TRIES = 30;
    private static final long FLUSH_TIMEOUT_MS = 3_000L;
    private static final long WARN_INTERVAL_MS = 300_000L;

    private static final List<Entry<?>> ENTRIES = List.of(
            new Entry<>("TameworkCompanionGroups", TameworkCompanionGroupsComponent.class,
                    TameworkCompanionGroupsComponent::getComponentType, TameworkCompanionGroupsComponent.CODEC),
            new Entry<>("TameworkCompanionViews", TameworkCompanionViewsComponent.class,
                    TameworkCompanionViewsComponent::getComponentType, TameworkCompanionViewsComponent.CODEC),
            new Entry<>("TameworkHudPlacement", TameworkHudPlacementComponent.class,
                    TameworkHudPlacementComponent::getComponentType, TameworkHudPlacementComponent.CODEC),
            new Entry<>("TameworkCompanionCardPreference", TameworkCompanionCardPreferenceComponent.class,
                    TameworkCompanionCardPreferenceComponent::getComponentType,
                    TameworkCompanionCardPreferenceComponent.CODEC),
            new Entry<>("TameworkCompanionCardPlacement", TameworkCompanionCardPlacementComponent.class,
                    TameworkCompanionCardPlacementComponent::getComponentType,
                    TameworkCompanionCardPlacementComponent.CODEC),
            new Entry<>("TameworkInspectPreferences", TameworkInspectPreferencesComponent.class,
                    TameworkInspectPreferencesComponent::getComponentType, TameworkInspectPreferencesComponent.CODEC));

    @Nullable
    private static volatile PlayerSettingsSync instance;

    private final NetworkSettingsSync sync;
    private final Supplier<Path> directory;
    @Nullable
    private final HytaleLogger logger;
    /** Online players by UUID. A state is replaced on reconnect, so stale work checks identity. */
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    /** The latest unsaved document per player. Only the network settings thread removes entries. */
    private final Map<UUID, Write> pendingWrites = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Until this wall-clock time no player read or save calls the store. */
    private volatile long storeDownUntilMs;
    private volatile long nextWarnAtMs = Long.MIN_VALUE;

    /**
     * @param sync      runs every read and write on its thread, with the network's store when one is registered
     * @param directory the Tamework universe folder; player files go in its {@code Players} folder
     */
    public PlayerSettingsSync(@Nonnull NetworkSettingsSync sync, @Nonnull Supplier<Path> directory,
                              @Nullable HytaleLogger logger) {
        this.sync = Objects.requireNonNull(sync, "sync");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = logger;
    }

    /** Makes this the instance the component write helpers report to. */
    public void start() {
        instance = this;
    }

    /**
     * Reports that a player's preference component changed. Call on the world thread that owns
     * {@code ref}, right after the write. Does nothing for a non-player entity or before start.
     */
    public static void changed(@Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store,
                               @Nonnull Class<? extends Component<EntityStore>> component) {
        PlayerSettingsSync current = instance;
        if (current == null || ref == null || store == null || !ref.isValid()) {
            return;
        }
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        if (player != null && player.getUuid() != null) {
            current.changed(player.getUuid(), ref, store, component);
        }
    }

    /**
     * Reads the player's document and puts it on {@code holder}, which is not in a store yet.
     * Call from the asynchronous {@code PlayerConnectEvent}; the login should wait for the result,
     * which completes within about {@value #CONNECT_WAIT_MS} ms and never fails.
     */
    @Nonnull
    public CompletableFuture<Void> playerConnecting(@Nonnull UUID playerId, @Nonnull Holder<EntityStore> holder) {
        PlayerState state = new PlayerState();
        players.put(playerId, state);
        return load(playerId, state)
                .completeOnTimeout(null, CONNECT_WAIT_MS, TimeUnit.MILLISECONDS)
                .thenRun(() -> {
                    synchronized (state) {
                        try {
                            // The login is still waiting for this future, so the holder is not in a store yet.
                            if (state.ready && !state.applied && players.get(playerId) == state) {
                                applyLocked(playerId, state, new HolderTarget(holder));
                            }
                        } finally {
                            state.loginProceeded = true;
                        }
                    }
                    flushIfDue(playerId, state);
                })
                .exceptionally(failure -> {
                    // Never hold up or fail a login over preferences.
                    warn(failure, "Tamework could not put the saved preferences on player " + playerId + ".");
                    return null;
                });
    }

    /** Forgets the player. Saves already queued still run. */
    public void playerDisconnected(@Nonnull UUID playerId) {
        PlayerState state = players.remove(playerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (!state.applied && !state.changedEarly.isEmpty()) {
                warn(null, "Tamework could not read the saved preferences of player " + playerId
                        + " during their visit, so the preferences they changed were not saved.");
            }
        }
    }

    /** Saves every queued document, waiting a short while, and stops reporting changes. Runs once. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (instance == this) {
            instance = null;
        }
        try {
            sync.run(store -> {
                for (UUID playerId : List.copyOf(pendingWrites.keySet())) {
                    flush(playerId, store);
                }
            }).get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            warn(failure, "Tamework could not save every player's preferences before stopping.");
        }
    }

    /** {@link #changed(Ref, Store, Class)} once the player is known. Runs on the world thread that owns {@code ref}. */
    void changed(@Nonnull UUID playerId, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                 @Nonnull Class<? extends Component<EntityStore>> component) {
        if (closed.get()) {
            return;
        }
        StoreTarget target = new StoreTarget(store, ref);
        PlayerState state = players.computeIfAbsent(playerId, id -> {
            // Online before this object started (a plugin reload): read their document now.
            PlayerState fresh = new PlayerState();
            fresh.loginProceeded = true;
            return fresh;
        });
        boolean reload = false;
        synchronized (state) {
            if (!state.applied) {
                state.changedEarly.add(component);
                if (!state.ready) {
                    reload = !state.loading;
                } else {
                    // A late read finished but its world task has not run yet: apply it here first.
                    applyLocked(playerId, state, target);
                }
            } else if (sync.active() && !state.readWithStore) {
                // Read before the network's store was registered: saving now could replace the network's
                // document with this server's. Read again; this change keeps its value.
                state.ready = false;
                state.applied = false;
                state.changedEarly.add(component);
                reload = true;
            } else {
                queueLocked(playerId, state, target);
            }
        }
        if (reload) {
            load(playerId, state);
        }
        flushIfDue(playerId, state);
    }

    /** Reads the document on the network settings thread. The future completes when the read ends, either way. */
    @Nonnull
    private CompletableFuture<Void> load(@Nonnull UUID playerId, @Nonnull PlayerState state) {
        synchronized (state) {
            state.loading = true;
        }
        return sync.run(store -> {
            if (players.get(playerId) != state) {
                return;
            }
            Loaded loaded;
            try {
                loaded = read(playerId, store);
            } catch (Exception failure) {
                synchronized (state) {
                    state.loading = false;
                }
                if (!(failure instanceof StoreDownException)) {
                    warn(failure, "Tamework could not read the saved preferences of player " + playerId
                            + "; it keeps what the player has and tries again.");
                }
                retryLoad(playerId, state);
                return;
            }
            boolean late;
            synchronized (state) {
                state.loading = false;
                state.ready = true;
                state.readWithStore = store != null;
                state.components = loaded.components();
                state.revision = loaded.revision();
                state.newerFormat = loaded.newerFormat();
                state.saveAfterApply = loaded.seedNetwork();
                late = state.loginProceeded && !state.applied;
            }
            if (late) {
                applyInWorld(playerId, state, 0);
            }
        }).handle((ignored, failure) -> {
            if (failure != null) {
                warn(failure, "Tamework could not read the saved preferences of player " + playerId + ".");
            }
            return null;
        });
    }

    private void retryLoad(@Nonnull UUID playerId, @Nonnull PlayerState state) {
        sync.runLater(() -> {
            Universe universe = Universe.get();
            if (players.get(playerId) != state) {
                return;
            }
            if (universe != null && universe.getPlayer(playerId) == null) {
                // Gone without a disconnect notice: stop retrying for them.
                players.remove(playerId, state);
                return;
            }
            load(playerId, state);
        }, retryDelayMs());
    }

    /** Runs on the network settings thread. Throws when the store or the local file cannot be read. */
    @Nonnull
    private Loaded read(@Nonnull UUID playerId, @Nullable NetworkSettingsStore store) throws Exception {
        if (store != null) {
            if (storeDown()) {
                throw new StoreDownException();
            }
            NetworkSettingsDocument document;
            try {
                document = store.load(KEY_PREFIX + playerId);
            } catch (Exception failure) {
                markStoreDown();
                throw failure;
            }
            if (document != null) {
                Parsed parsed = parse(document.json(), playerId);
                return new Loaded(parsed.components(), document.revision(), parsed.newerFormat(), false);
            }
            // First visit since the network began keeping player documents: this server's file, if any, seeds it.
            Parsed local = readLocal(playerId);
            return new Loaded(local == null ? null : local.components(), 0L,
                    local != null && local.newerFormat(), local != null);
        }
        Parsed local = readLocal(playerId);
        return new Loaded(local == null ? null : local.components(), 0L, local != null && local.newerFormat(), false);
    }

    /** Puts a late read on the live entity, waiting for it to enter its world. */
    private void applyInWorld(@Nonnull UUID playerId, @Nonnull PlayerState state, int attempt) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(playerId);
        UUID worldId = player == null ? null : player.getWorldUuid();
        World world = worldId == null ? null : universe.getWorld(worldId);
        if (world == null) {
            retryApplyInWorld(playerId, state, attempt);
            return;
        }
        world.execute(() -> {
            Ref<EntityStore> ref = world.getEntityRef(playerId);
            if (ref == null || !ref.isValid()) {
                retryApplyInWorld(playerId, state, attempt);
                return;
            }
            synchronized (state) {
                if (players.get(playerId) == state) {
                    applyLocked(playerId, state, new StoreTarget(world.getEntityStore().getStore(), ref));
                }
            }
            flushIfDue(playerId, state);
        });
    }

    private void retryApplyInWorld(@Nonnull UUID playerId, @Nonnull PlayerState state, int attempt) {
        if (attempt + 1 >= LATE_APPLY_TRIES || players.get(playerId) != state) {
            return;
        }
        // Still joining. A change before then also applies the document (see changed).
        sync.runLater(() -> applyInWorld(playerId, state, attempt + 1), LATE_APPLY_DELAY_MS);
    }

    /** Puts the read document on {@code target}. Hold the state's monitor; the target must be safe to touch here. */
    private void applyLocked(@Nonnull UUID playerId, @Nonnull PlayerState state, @Nonnull Target target) {
        if (!state.ready || state.applied) {
            return;
        }
        boolean save = state.saveAfterApply || !state.changedEarly.isEmpty();
        if (state.components == null) {
            // No document anywhere: what the player carries becomes the first one.
            save |= ENTRIES.stream().anyMatch(entry -> entry.has(target));
        } else {
            for (Entry<?> entry : ENTRIES) {
                if (!state.changedEarly.contains(entry.type())) {
                    entry.applyFrom(state.components, target);
                }
            }
        }
        state.applied = true;
        state.saveAfterApply = false;
        state.changedEarly.clear();
        if (save) {
            queueLocked(playerId, state, target);
        }
    }

    /** Encodes the target into the state's document and queues its save. Hold the state's monitor. */
    private void queueLocked(@Nonnull UUID playerId, @Nonnull PlayerState state, @Nonnull Target target) {
        if (state.newerFormat) {
            warn(null, "The saved Tamework preferences of player " + playerId + " come from a newer Tamework;"
                    + " this server does not change them. Update Tamework on every server.");
            return;
        }
        BsonDocument components = state.components == null ? new BsonDocument() : state.components.clone();
        for (Entry<?> entry : ENTRIES) {
            entry.encodeInto(target, components);
        }
        state.components = components;
        state.queued = new Write(state, new BsonDocument(FORMAT_FIELD, new BsonInt32(FORMAT))
                .append(COMPONENTS_FIELD, components).toJson());
    }

    /** Moves a write queued under the state's monitor to the save queue. Call without holding it. */
    private void flushIfDue(@Nonnull UUID playerId, @Nonnull PlayerState state) {
        Write write;
        synchronized (state) {
            write = state.queued;
            state.queued = null;
        }
        if (write != null && pendingWrites.put(playerId, write) == null) {
            submitFlush(playerId);
        }
    }

    private void submitFlush(@Nonnull UUID playerId) {
        sync.run(store -> flush(playerId, store)).exceptionally(failure -> {
            warn(failure, "Tamework could not save the preferences of player " + playerId + ".");
            return null;
        });
    }

    /** Runs on the network settings thread. */
    private void flush(@Nonnull UUID playerId, @Nullable NetworkSettingsStore store) {
        Write write = pendingWrites.remove(playerId);
        if (write == null) {
            return;
        }
        writeLocal(playerId, write.json());
        if (store == null) {
            return;
        }
        if (storeDown()) {
            requeue(playerId, write);
            return;
        }
        String key = KEY_PREFIX + playerId;
        try {
            long expected;
            synchronized (write.state()) {
                expected = write.state().revision;
            }
            if (!store.save(key, write.json(), expected)) {
                if (players.get(playerId) != write.state()) {
                    // Another server saved since this one last read, and the player has left: theirs is newer.
                    return;
                }
                // The player is here, so this server's document is the current one.
                NetworkSettingsDocument current = store.load(key);
                if (!store.save(key, write.json(), current == null ? 0L : current.revision())) {
                    throw new IllegalStateException("another server keeps saving this player's preferences");
                }
            }
            NetworkSettingsDocument saved = store.load(key);
            synchronized (write.state()) {
                write.state().revision = saved == null ? 0L : saved.revision();
            }
        } catch (Exception failure) {
            markStoreDown();
            warn(failure, "Tamework could not save the preferences of player " + playerId
                    + " to the network; it tries again.");
            requeue(playerId, write);
        }
    }

    /** Keeps a failed save for a later try, unless a newer one is queued. */
    private void requeue(@Nonnull UUID playerId, @Nonnull Write write) {
        if (!closed.get() && pendingWrites.putIfAbsent(playerId, write) == null) {
            sync.runLater(() -> submitFlush(playerId), retryDelayMs());
        }
    }

    private boolean storeDown() {
        return System.currentTimeMillis() < storeDownUntilMs;
    }

    private void markStoreDown() {
        storeDownUntilMs = System.currentTimeMillis() + RETRY_MS;
    }

    /** Past the store-down window a failure just opened, so the retry asks the store again. */
    private static long retryDelayMs() {
        return RETRY_MS + 50L;
    }

    /** This server's copy, or null when it has none. Throws when the file exists but cannot be read. */
    @Nullable
    private Parsed readLocal(@Nonnull UUID playerId) throws IOException {
        Path file = localFile(playerId);
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return null;
        }
        Parsed parsed = parse(json, playerId);
        return parsed.components() == null ? null : parsed;
    }

    private void writeLocal(@Nonnull UUID playerId, @Nonnull String json) {
        Path file = localFile(playerId);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            warn(failure, "Tamework could not write " + file + ".");
        }
    }

    @Nonnull
    private Path localFile(@Nonnull UUID playerId) {
        return directory.get().resolve(DIRECTORY).resolve(playerId + ".json").normalize();
    }

    /** A document's components, null when it cannot be read; a malformed document is replaced at the next save. */
    @Nonnull
    private Parsed parse(@Nonnull String json, @Nonnull UUID playerId) {
        try {
            BsonDocument document = BsonDocument.parse(json);
            BsonValue format = document.get(FORMAT_FIELD);
            boolean newer = format != null && format.isNumber() && format.asNumber().intValue() > FORMAT;
            BsonValue components = document.get(COMPONENTS_FIELD);
            if (components != null && components.isDocument()) {
                return new Parsed(components.asDocument(), newer);
            }
        } catch (RuntimeException malformed) {
            warn(malformed, "The saved Tamework preferences of player " + playerId + " are not readable; they are"
                    + " replaced by what the player has.");
            return new Parsed(null, false);
        }
        warn(null, "The saved Tamework preferences of player " + playerId + " have no components; they are"
                + " replaced by what the player has.");
        return new Parsed(null, false);
    }

    private void warn(@Nullable Throwable cause, @Nonnull String message) {
        long now = System.currentTimeMillis();
        if (logger == null || now < nextWarnAtMs) {
            return;
        }
        nextWarnAtMs = now + WARN_INTERVAL_MS;
        if (cause == null) {
            logger.at(Level.WARNING).log(message);
        } else {
            logger.at(Level.WARNING).withCause(cause).log(message);
        }
    }

    /** One player's progress on this server. Guarded by its own monitor. */
    private static final class PlayerState {
        /** A read is queued or running. */
        boolean loading;
        /** The last read finished. */
        boolean ready;
        /** The document was put on the player; changes are saved from now on. */
        boolean applied;
        /** The login went on, so the holder is in a world now. */
        boolean loginProceeded;
        /** The last read asked a network store. */
        boolean readWithStore;
        /** The document comes from a newer Tamework: never saved over here. */
        boolean newerFormat;
        /** Save right after applying: the network had no document yet. */
        boolean saveAfterApply;
        /** The network revision this server last read or wrote; 0 when it has none. */
        long revision;
        /** The document's components as last read or saved, or null when none exists. */
        @Nullable
        BsonDocument components;
        /** Components the player changed before the document was applied; they keep their new value. */
        final Set<Class<?>> changedEarly = new HashSet<>();
        /** A save encoded under this monitor, handed to the save queue once it is released. */
        @Nullable
        Write queued;
    }

    private record Write(@Nonnull PlayerState state, @Nonnull String json) {
    }

    private record Loaded(@Nullable BsonDocument components, long revision, boolean newerFormat,
                          boolean seedNetwork) {
    }

    private record Parsed(@Nullable BsonDocument components, boolean newerFormat) {
    }

    /** The store failed recently; nothing was asked. */
    private static final class StoreDownException extends Exception {
        StoreDownException() {
            super("the network store failed recently", null, false, false);
        }
    }

    /** Where components are read and written: a live entity or a holder not yet in a world. */
    interface Target {
        @Nullable
        <C extends Component<EntityStore>> C get(@Nonnull ComponentType<EntityStore, C> type);

        <C extends Component<EntityStore>> void put(@Nonnull ComponentType<EntityStore, C> type, @Nonnull C value);

        <C extends Component<EntityStore>> void remove(@Nonnull ComponentType<EntityStore, C> type);
    }

    private record StoreTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) implements Target {
        @Override
        public <C extends Component<EntityStore>> C get(@Nonnull ComponentType<EntityStore, C> type) {
            return store.getComponent(ref, type);
        }

        @Override
        public <C extends Component<EntityStore>> void put(@Nonnull ComponentType<EntityStore, C> type,
                                                            @Nonnull C value) {
            // Runs from world.execute or a page callback, never inside an ECS system iteration.
            store.putComponent(ref, type, value);
        }

        @Override
        public <C extends Component<EntityStore>> void remove(@Nonnull ComponentType<EntityStore, C> type) {
            store.tryRemoveComponent(ref, type);
        }
    }

    private record HolderTarget(@Nonnull Holder<EntityStore> holder) implements Target {
        @Override
        public <C extends Component<EntityStore>> C get(@Nonnull ComponentType<EntityStore, C> type) {
            return holder.getComponent(type);
        }

        @Override
        public <C extends Component<EntityStore>> void put(@Nonnull ComponentType<EntityStore, C> type,
                                                            @Nonnull C value) {
            holder.putComponent(type, value);
        }

        @Override
        public <C extends Component<EntityStore>> void remove(@Nonnull ComponentType<EntityStore, C> type) {
            holder.tryRemoveComponent(type);
        }
    }

    /** One saved component: its key in the document, its class, its registered type and its codec. */
    private record Entry<C extends Component<EntityStore>>(@Nonnull String key, @Nonnull Class<C> type,
                                                          @Nonnull Supplier<ComponentType<EntityStore, C>> componentType,
                                                          @Nonnull Codec<C> codec) {
        boolean has(@Nonnull Target target) {
            ComponentType<EntityStore, C> registered = componentType.get();
            return registered != null && target.get(registered) != null;
        }

        /** Writes or removes this component's key; keys of other components stay as they are. */
        void encodeInto(@Nonnull Target target, @Nonnull BsonDocument components) {
            ComponentType<EntityStore, C> registered = componentType.get();
            if (registered == null) {
                return;
            }
            C value = target.get(registered);
            if (value == null) {
                components.remove(key);
            } else {
                components.put(key, codec.encode(value, EmptyExtraInfo.EMPTY));
            }
        }

        /** The document is the truth: a component it lacks was cleared by the player. */
        void applyFrom(@Nonnull BsonDocument components, @Nonnull Target target) {
            ComponentType<EntityStore, C> registered = componentType.get();
            if (registered == null) {
                return;
            }
            BsonValue saved = components.get(key);
            if (saved == null) {
                target.remove(registered);
                return;
            }
            C value;
            try {
                value = codec.decode(saved, EmptyExtraInfo.EMPTY);
            } catch (RuntimeException malformed) {
                // Keep what the player has. Its key stays in the document until the player changes it.
                return;
            }
            if (value != null) {
                target.put(registered, value);
            }
        }
    }
}
