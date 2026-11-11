package com.alechilles.alecstamework.access;

import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessChangeResult;
import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Answers who may do what with a companion that someone else owns, and holds the grants owners
 * have made.
 *
 * <p>A companion always has one owner. Sharing never changes it: caps, admission, owner files
 * and breeding inheritance keep using the owner alone. This service only answers authorization
 * questions, so callers that need the owner's identity must not use it.</p>
 *
 * <p>Reads are map lookups on immutable values and are safe from any thread. Changes take one
 * lock and are saved by a single background thread, so no caller waits on a file.</p>
 *
 * <p>On a server network, {@link SharedAccessNetworkSync} keeps these grants the same on every
 * server through {@link #setLocalChangeHook} and {@link #applyNetwork}. This class does no
 * network I/O.</p>
 */
public final class SharedAccessService implements AutoCloseable {
    /** Most named players one owner can share with. Bounds the file and every access check. */
    public static final int MAX_PLAYER_GRANTS_PER_OWNER = 20;

    private static final System.Logger LOGGER = System.getLogger(SharedAccessService.class.getName());
    private static final SharedAccessService INSTANCE = new SharedAccessService();

    private final Map<UUID, OwnerGrants> grantsByOwner = new ConcurrentHashMap<>();
    /** Owners who named a player, by that player. Finds "shared with me" without scanning owners. */
    private final Map<UUID, Set<UUID>> ownersByPlayer = new ConcurrentHashMap<>();
    /** Owners who turned a group on, by group id. */
    private final Map<String, Set<UUID>> ownersByGroup = new ConcurrentHashMap<>();
    private final Map<String, AccessGroupProvider> providers = new ConcurrentHashMap<>();
    private final List<Consumer<UUID>> changeListeners = new CopyOnWriteArrayList<>();
    private final Object mutationLock = new Object();

    private volatile boolean enabled = true;
    private volatile AccessLevel maxLevel = AccessLevel.MANAGER;
    private volatile java.util.function.Predicate<UUID> neverShared = profileId -> false;
    @Nullable private Path dataDirectory;
    @Nullable private ExecutorService saveExecutor;
    private boolean writable;
    /** The last network revision applied here; 0 until this server first follows a network's grants. */
    private long networkRevision;
    /** The network sync's pending owners as last loaded or queued for saving. */
    private Set<UUID> savedNetworkPending = Set.of();
    /** The network sync's live pending set, copied into each save; null until it registers one. */
    @Nullable private Set<UUID> networkPending;
    @Nullable private volatile Consumer<UUID> localChangeHook;

    private SharedAccessService() {
    }

    @Nonnull
    public static SharedAccessService get() {
        return INSTANCE;
    }

    /** Loads saved grants. Call once at plugin setup, before any world ticks. */
    public void start(@Nonnull Path targetDataDirectory) {
        Objects.requireNonNull(targetDataDirectory, "targetDataDirectory");
        close();
        SharedAccessFileStore.LoadResult loaded = SharedAccessFileStore.load(targetDataDirectory);
        if (!loaded.writable()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Cannot read shared access grants at {0}; keeping the file and not saving changes this session.",
                    SharedAccessFileStore.resolveFile(targetDataDirectory));
        }
        synchronized (mutationLock) {
            grantsByOwner.clear();
            ownersByPlayer.clear();
            ownersByGroup.clear();
            loaded.owners().forEach((ownerId, grants) -> {
                grantsByOwner.put(ownerId, grants);
                index(ownerId, OwnerGrants.EMPTY, grants);
            });
            dataDirectory = targetDataDirectory.toAbsolutePath().normalize();
            writable = loaded.writable();
            networkRevision = loaded.networkRevision();
            savedNetworkPending = loaded.pendingNetworkOwners();
            networkPending = null;
            saveExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tamework-shared-access-save");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    /** Writes pending changes and stops the save thread. */
    @Override
    public void close() {
        ExecutorService executor;
        synchronized (mutationLock) {
            executor = saveExecutor;
            saveExecutor = null;
            dataDirectory = null;
            writable = false;
        }
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            executor.awaitTermination(3L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Applies the server's sharing settings. Grants are kept while sharing is off. */
    public void configure(boolean sharingEnabled, @Nonnull AccessLevel highestLevel) {
        boolean changed = enabled != sharingEnabled || maxLevel != highestLevel;
        enabled = sharingEnabled;
        maxLevel = highestLevel.isGrantable() ? highestLevel : AccessLevel.MANAGER;
        if (changed) {
            grantsByOwner.keySet().forEach(this::notifyChanged);
        }
    }

    /**
     * Names the companions that no grant ever applies to, by profile id. Tamework uses it for
     * bonded companions, which stay with their one player. The test must be an in-memory lookup.
     */
    public void setNeverShared(@Nonnull java.util.function.Predicate<UUID> profileTest) {
        neverShared = Objects.requireNonNull(profileTest, "profileTest");
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nonnull
    public AccessLevel maxLevel() {
        return maxLevel;
    }

    /**
     * The level {@code actorId} holds on a companion of {@code ownerId}.
     *
     * @param profileId the companion's profile id, used for the private flag; null skips that check
     * @return {@link AccessLevel#OWNER} for the owner, {@link AccessLevel#NONE} for an unowned
     *         companion or a missing actor
     */
    @Nonnull
    public AccessLevel level(@Nullable UUID ownerId, @Nullable UUID actorId, @Nullable UUID profileId) {
        if (ownerId == null || actorId == null) {
            return AccessLevel.NONE;
        }
        if (ownerId.equals(actorId)) {
            return AccessLevel.OWNER;
        }
        if (!enabled) {
            return AccessLevel.NONE;
        }
        OwnerGrants grants = grantsByOwner.get(ownerId);
        if (grants == null || !grants.sharesAnything()
                || (profileId != null && (grants.privateCompanions().contains(profileId)
                || neverShared.test(profileId)))) {
            return AccessLevel.NONE;
        }
        AccessLevel best = AccessLevel.NONE;
        OwnerGrants.PlayerGrant named = grants.players().get(actorId);
        if (named != null) {
            best = named.level();
        }
        for (Map.Entry<String, AccessLevel> group : grants.groups().entrySet()) {
            if (group.getValue().compareTo(best) > 0 && isMember(group.getKey(), ownerId, actorId)) {
                best = group.getValue();
            }
        }
        return AccessLevel.lowerOf(best, maxLevel);
    }

    /** True when {@code actorId} may do {@code action} with a companion of {@code ownerId}. */
    public boolean allows(@Nullable UUID ownerId, @Nullable UUID actorId, @Nullable UUID profileId,
                          @Nonnull AccessAction action) {
        return action.allowedAt(level(ownerId, actorId, profileId));
    }

    /** True when sharing is on and the owner shares with at least one player or group. */
    public boolean sharesAnything(@Nullable UUID ownerId) {
        if (ownerId == null || !enabled) {
            return false;
        }
        OwnerGrants grants = grantsByOwner.get(ownerId);
        return grants != null && grants.sharesAnything();
    }

    /** True when no grant applies to this companion: the owner marked it private, or it is never shared. */
    public boolean isPrivate(@Nullable UUID ownerId, @Nullable UUID profileId) {
        if (ownerId == null || profileId == null) {
            return false;
        }
        OwnerGrants grants = grantsByOwner.get(ownerId);
        return (grants != null && grants.privateCompanions().contains(profileId)) || neverShared.test(profileId);
    }

    @Nonnull
    public OwnerGrants grants(@Nullable UUID ownerId) {
        OwnerGrants grants = ownerId == null ? null : grantsByOwner.get(ownerId);
        return grants == null ? OwnerGrants.EMPTY : grants;
    }

    /** Every owner with at least one player or group grant. A snapshot. */
    @Nonnull
    public Set<UUID> ownersWithGrants() {
        Set<UUID> owners = new LinkedHashSet<>();
        grantsByOwner.forEach((ownerId, grants) -> {
            if (grants.sharesAnything()) {
                owners.add(ownerId);
            }
        });
        return owners;
    }

    /**
     * Owners whose grants give {@code actorId} some access right now. The private flag is per
     * companion, so callers still check {@link #level} for each one.
     */
    @Nonnull
    public Set<UUID> ownersSharingWith(@Nullable UUID actorId) {
        if (actorId == null || !enabled) {
            return Set.of();
        }
        Set<UUID> owners = new LinkedHashSet<>();
        Set<UUID> named = ownersByPlayer.get(actorId);
        if (named != null) {
            owners.addAll(named);
        }
        ownersByGroup.forEach((groupId, groupOwners) -> {
            for (UUID ownerId : groupOwners) {
                if (!ownerId.equals(actorId) && !owners.contains(ownerId) && isMember(groupId, ownerId, actorId)) {
                    owners.add(ownerId);
                }
            }
        });
        owners.remove(actorId);
        return owners;
    }

    /** Shares {@code ownerId}'s companions with a named player, or changes that player's level. */
    @Nonnull
    public AccessChangeResult grantPlayer(@Nonnull UUID ownerId, @Nonnull UUID playerId, @Nullable String playerName,
                                          @Nonnull AccessLevel level) {
        if (!enabled) {
            return AccessChangeResult.SHARING_DISABLED;
        }
        if (ownerId.equals(playerId)) {
            return AccessChangeResult.SELF;
        }
        if (!level.isGrantable() || level.compareTo(maxLevel) > 0) {
            return AccessChangeResult.LEVEL_NOT_ALLOWED;
        }
        return change(ownerId, current -> {
            OwnerGrants.PlayerGrant existing = current.players().get(playerId);
            if (existing == null && current.players().size() >= MAX_PLAYER_GRANTS_PER_OWNER) {
                return null;
            }
            String name = playerName != null ? playerName : existing == null ? null : existing.playerName();
            return current.withPlayer(new OwnerGrants.PlayerGrant(playerId, name, level));
        }, AccessChangeResult.TOO_MANY_PLAYERS);
    }

    /** Removes a named player's grant. Also how a player leaves someone's share. */
    @Nonnull
    public AccessChangeResult revokePlayer(@Nonnull UUID ownerId, @Nonnull UUID playerId) {
        return change(ownerId, current -> current.withoutPlayer(playerId), AccessChangeResult.UNCHANGED);
    }

    /** Turns a group share on at {@code level}, or off when {@code level} is null. */
    @Nonnull
    public AccessChangeResult setGroup(@Nonnull UUID ownerId, @Nonnull String groupId, @Nullable AccessLevel level) {
        if (level != null) {
            if (!enabled) {
                return AccessChangeResult.SHARING_DISABLED;
            }
            if (!providers.containsKey(groupId)) {
                return AccessChangeResult.UNKNOWN_GROUP;
            }
            if (!level.isGrantable() || level.compareTo(maxLevel) > 0) {
                return AccessChangeResult.LEVEL_NOT_ALLOWED;
            }
        }
        return change(ownerId, current -> current.withGroup(groupId, level), AccessChangeResult.UNCHANGED);
    }

    /** Marks one companion private, so no grant applies to it, or shares it again. */
    @Nonnull
    public AccessChangeResult setPrivate(@Nonnull UUID ownerId, @Nonnull UUID profileId, boolean isPrivate) {
        return change(ownerId, current -> current.withPrivate(profileId, isPrivate), AccessChangeResult.UNCHANGED);
    }

    /**
     * Registers a group owners can share with. Closing the handle removes it; owners' saved
     * choices stay and apply again when the group returns.
     */
    @Nonnull
    public AutoCloseable registerGroup(@Nonnull AccessGroupProvider provider) {
        String id = Objects.requireNonNull(provider.id(), "provider id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Group provider id is required");
        }
        if (providers.putIfAbsent(id, provider) != null) {
            throw new IllegalStateException("A group provider is already registered for " + id);
        }
        notifyGroupOwners(id);
        return () -> {
            if (providers.remove(id, provider)) {
                notifyGroupOwners(id);
            }
        };
    }

    @Nonnull
    public List<AccessGroupProvider> groupProviders() {
        return List.copyOf(providers.values());
    }

    @Nullable
    public AccessGroupProvider groupProvider(@Nullable String groupId) {
        return groupId == null ? null : providers.get(groupId);
    }

    /** True when the provider for {@code groupId} says {@code actorId} is in {@code ownerId}'s group. */
    public boolean isMember(@Nonnull String groupId, @Nonnull UUID ownerId, @Nonnull UUID actorId) {
        AccessGroupProvider provider = providers.get(groupId);
        if (provider == null) {
            return false;
        }
        try {
            return provider.membership(ownerId, actorId) == AccessGroupMembership.MEMBER;
        } catch (RuntimeException | LinkageError failed) {
            // A broken provider, or one whose mod was unloaded, must not break gameplay checks;
            // it simply grants nothing.
            return false;
        }
    }

    /**
     * Calls {@code listener} with the owner id after that owner's effective sharing changes. The
     * listener runs on the changing thread and must be quick and thread-safe.
     */
    public void addChangeListener(@Nonnull Consumer<UUID> listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Every owner with anything stored, including owners who only marked companions private. A
     * snapshot.
     */
    @Nonnull
    public Set<UUID> storedOwners() {
        return new LinkedHashSet<>(grantsByOwner.keySet());
    }

    /** The last network revision applied on this server; 0 when it never followed a network's grants. */
    public long networkRevision() {
        synchronized (mutationLock) {
            return networkRevision;
        }
    }

    /** The network sync's pending owners saved in the file, or last queued for saving. A snapshot. */
    @Nonnull
    public Set<UUID> savedNetworkPending() {
        synchronized (mutationLock) {
            return savedNetworkPending;
        }
    }

    /**
     * Keeps the network sync's pending owners in the file, so a change not yet saved to the network
     * survives a restart. From now on every save copies {@code livePending}, under the change lock;
     * this call also queues a save when it differs from what was last saved. The save runs on the
     * save thread.
     *
     * @param livePending the live set the local change hook fills
     */
    public void saveNetworkPending(@Nonnull Set<UUID> livePending) {
        Objects.requireNonNull(livePending, "livePending");
        synchronized (mutationLock) {
            networkPending = livePending;
            if (!savedNetworkPending.equals(livePending)) {
                queueSave();
            }
        }
    }

    /**
     * Sets the one hook that hears about changes made on this server, for the network sync. It is
     * called with the owner id while the change lock is held, in the same step as the change, so
     * {@link #applyNetwork} never sees a changed owner that the hook has not heard about yet. It
     * is not called for {@link #applyNetwork}. The hook must return at once and must not call
     * back into this service.
     */
    public void setLocalChangeHook(@Nullable Consumer<UUID> hook) {
        localChangeHook = hook;
    }

    /**
     * Makes this server's grants the network's: every owner not in {@code keepLocal} takes the
     * network's value, and loses their grants when the network has none for them. Change
     * listeners hear about each owner whose grants changed; the local change hook does not.
     *
     * @param keepLocal owners whose grants changed here and are not saved to the network yet. It
     *                  is read while the change lock is held, so pass the live set that the local
     *                  change hook fills.
     * @param revision  the network document's revision, saved with the grants
     */
    public void applyNetwork(@Nonnull Map<UUID, OwnerGrants> network, @Nonnull Set<UUID> keepLocal, long revision) {
        List<UUID> changed = new ArrayList<>();
        synchronized (mutationLock) {
            Set<UUID> owners = new LinkedHashSet<>(grantsByOwner.keySet());
            owners.addAll(network.keySet());
            for (UUID ownerId : owners) {
                if (keepLocal.contains(ownerId)) {
                    continue;
                }
                OwnerGrants current = grants(ownerId);
                OwnerGrants next = network.getOrDefault(ownerId, OwnerGrants.EMPTY);
                if (next.equals(current)) {
                    continue;
                }
                if (next.isEmpty()) {
                    grantsByOwner.remove(ownerId);
                } else {
                    grantsByOwner.put(ownerId, next);
                }
                index(ownerId, current, next);
                changed.add(ownerId);
            }
            if (!changed.isEmpty() || networkRevision != revision) {
                networkRevision = revision;
                queueSave();
            }
        }
        changed.forEach(this::notifyChanged);
    }

    /** Test hook: forgets every grant, provider, setting and the local change hook. Listeners stay registered. */
    public void clearForTests() {
        close();
        synchronized (mutationLock) {
            grantsByOwner.clear();
            ownersByPlayer.clear();
            ownersByGroup.clear();
            networkRevision = 0L;
            savedNetworkPending = Set.of();
            networkPending = null;
        }
        localChangeHook = null;
        providers.clear();
        enabled = true;
        maxLevel = AccessLevel.MANAGER;
        neverShared = profileId -> false;
    }

    @Nonnull
    private AccessChangeResult change(@Nonnull UUID ownerId,
                                      @Nonnull java.util.function.UnaryOperator<OwnerGrants> edit,
                                      @Nonnull AccessChangeResult whenRefused) {
        synchronized (mutationLock) {
            OwnerGrants current = grants(ownerId);
            OwnerGrants next = edit.apply(current);
            if (next == null) {
                return whenRefused;
            }
            if (next.equals(current)) {
                return AccessChangeResult.UNCHANGED;
            }
            if (next.isEmpty()) {
                grantsByOwner.remove(ownerId);
            } else {
                grantsByOwner.put(ownerId, next);
            }
            index(ownerId, current, next);
            // The hook first, so the save below already holds this owner as pending.
            Consumer<UUID> hook = localChangeHook;
            if (hook != null) {
                try {
                    hook.accept(ownerId);
                } catch (RuntimeException failed) {
                    LOGGER.log(System.Logger.Level.WARNING, "The shared access network hook failed.", failed);
                }
            }
            queueSave();
        }
        notifyChanged(ownerId);
        return AccessChangeResult.CHANGED;
    }

    /** Keeps the reverse lookups in step with one owner's change. Caller holds the lock. */
    private void index(@Nonnull UUID ownerId, @Nonnull OwnerGrants before, @Nonnull OwnerGrants after) {
        for (UUID playerId : before.players().keySet()) {
            if (!after.players().containsKey(playerId)) {
                unindex(ownersByPlayer, playerId, ownerId);
            }
        }
        for (UUID playerId : after.players().keySet()) {
            ownersByPlayer.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(ownerId);
        }
        for (String groupId : before.groups().keySet()) {
            if (!after.groups().containsKey(groupId)) {
                unindex(ownersByGroup, groupId, ownerId);
            }
        }
        for (String groupId : after.groups().keySet()) {
            ownersByGroup.computeIfAbsent(groupId, ignored -> ConcurrentHashMap.newKeySet()).add(ownerId);
        }
    }

    private static <K> void unindex(@Nonnull Map<K, Set<UUID>> lookup, @Nonnull K key, @Nonnull UUID ownerId) {
        Set<UUID> owners = lookup.get(key);
        if (owners != null) {
            owners.remove(ownerId);
            if (owners.isEmpty()) {
                lookup.remove(key, owners);
            }
        }
    }

    /** Queues one complete snapshot for the save thread. Caller holds the lock. */
    private void queueSave() {
        ExecutorService executor = saveExecutor;
        Path target = dataDirectory;
        if (executor == null || target == null || !writable) {
            return;
        }
        Map<UUID, OwnerGrants> snapshot = new LinkedHashMap<>(grantsByOwner);
        long revision = networkRevision;
        Set<UUID> pendingOwners = networkPending == null ? savedNetworkPending : Set.copyOf(networkPending);
        savedNetworkPending = pendingOwners;
        try {
            executor.execute(() -> {
                if (!SharedAccessFileStore.save(target, snapshot, revision, pendingOwners)) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Cannot save shared access grants at {0}; the next change will retry.",
                            SharedAccessFileStore.resolveFile(target));
                }
            });
        } catch (RuntimeException rejected) {
            // Shutting down: close() has already stopped accepting saves.
        }
    }

    private void notifyGroupOwners(@Nonnull String groupId) {
        Set<UUID> owners = ownersByGroup.get(groupId);
        if (owners != null) {
            List.copyOf(owners).forEach(this::notifyChanged);
        }
    }

    private void notifyChanged(@Nonnull UUID ownerId) {
        for (Consumer<UUID> listener : changeListeners) {
            try {
                listener.accept(ownerId);
            } catch (RuntimeException failed) {
                LOGGER.log(System.Logger.Level.WARNING, "A shared access change listener failed.", failed);
            }
        }
    }
}
