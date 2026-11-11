package com.alechilles.alecstamework.access;

import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import java.nio.file.Path;
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
 */
public final class SharedAccessService implements AutoCloseable {
    /** Most named players one owner can share with. Bounds the file and every access check. */
    public static final int MAX_PLAYER_GRANTS_PER_OWNER = 20;

    private static final System.Logger LOGGER = System.getLogger(SharedAccessService.class.getName());
    private static final SharedAccessService INSTANCE = new SharedAccessService();

    /** The outcome of a request to change an owner's grants. */
    public enum ChangeResult {
        CHANGED,
        UNCHANGED,
        SHARING_DISABLED,
        /** An owner cannot share with themselves. */
        SELF,
        /** The level is not grantable, or is above the server's highest allowed level. */
        LEVEL_NOT_ALLOWED,
        TOO_MANY_PLAYERS,
        /** No provider is registered for the group. */
        UNKNOWN_GROUP
    }

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
    @Nullable private Path dataDirectory;
    @Nullable private ExecutorService saveExecutor;
    private boolean writable;

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
                || (profileId != null && grants.privateCompanions().contains(profileId))) {
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

    public boolean isPrivate(@Nullable UUID ownerId, @Nullable UUID profileId) {
        if (ownerId == null || profileId == null) {
            return false;
        }
        OwnerGrants grants = grantsByOwner.get(ownerId);
        return grants != null && grants.privateCompanions().contains(profileId);
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
    public ChangeResult grantPlayer(@Nonnull UUID ownerId, @Nonnull UUID playerId, @Nullable String playerName,
                                    @Nonnull AccessLevel level) {
        if (!enabled) {
            return ChangeResult.SHARING_DISABLED;
        }
        if (ownerId.equals(playerId)) {
            return ChangeResult.SELF;
        }
        if (!level.isGrantable() || level.compareTo(maxLevel) > 0) {
            return ChangeResult.LEVEL_NOT_ALLOWED;
        }
        return change(ownerId, current -> {
            OwnerGrants.PlayerGrant existing = current.players().get(playerId);
            if (existing == null && current.players().size() >= MAX_PLAYER_GRANTS_PER_OWNER) {
                return null;
            }
            String name = playerName != null ? playerName : existing == null ? null : existing.playerName();
            return current.withPlayer(new OwnerGrants.PlayerGrant(playerId, name, level));
        }, ChangeResult.TOO_MANY_PLAYERS);
    }

    /** Removes a named player's grant. Also how a player leaves someone's share. */
    @Nonnull
    public ChangeResult revokePlayer(@Nonnull UUID ownerId, @Nonnull UUID playerId) {
        return change(ownerId, current -> current.withoutPlayer(playerId), ChangeResult.UNCHANGED);
    }

    /** Turns a group share on at {@code level}, or off when {@code level} is null. */
    @Nonnull
    public ChangeResult setGroup(@Nonnull UUID ownerId, @Nonnull String groupId, @Nullable AccessLevel level) {
        if (level != null) {
            if (!enabled) {
                return ChangeResult.SHARING_DISABLED;
            }
            if (!providers.containsKey(groupId)) {
                return ChangeResult.UNKNOWN_GROUP;
            }
            if (!level.isGrantable() || level.compareTo(maxLevel) > 0) {
                return ChangeResult.LEVEL_NOT_ALLOWED;
            }
        }
        return change(ownerId, current -> current.withGroup(groupId, level), ChangeResult.UNCHANGED);
    }

    /** Marks one companion private, so no grant applies to it, or shares it again. */
    @Nonnull
    public ChangeResult setPrivate(@Nonnull UUID ownerId, @Nonnull UUID profileId, boolean isPrivate) {
        return change(ownerId, current -> current.withPrivate(profileId, isPrivate), ChangeResult.UNCHANGED);
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
        } catch (RuntimeException failed) {
            // A broken provider must not break gameplay checks; it simply grants nothing.
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

    void clearForTests() {
        close();
        synchronized (mutationLock) {
            grantsByOwner.clear();
            ownersByPlayer.clear();
            ownersByGroup.clear();
        }
        providers.clear();
        changeListeners.clear();
        enabled = true;
        maxLevel = AccessLevel.MANAGER;
    }

    @Nonnull
    private ChangeResult change(@Nonnull UUID ownerId,
                                @Nonnull java.util.function.UnaryOperator<OwnerGrants> edit,
                                @Nonnull ChangeResult whenRefused) {
        synchronized (mutationLock) {
            OwnerGrants current = grants(ownerId);
            OwnerGrants next = edit.apply(current);
            if (next == null) {
                return whenRefused;
            }
            if (next.equals(current)) {
                return ChangeResult.UNCHANGED;
            }
            if (next.isEmpty()) {
                grantsByOwner.remove(ownerId);
            } else {
                grantsByOwner.put(ownerId, next);
            }
            index(ownerId, current, next);
            queueSave();
        }
        notifyChanged(ownerId);
        return ChangeResult.CHANGED;
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
        try {
            executor.execute(() -> {
                if (!SharedAccessFileStore.save(target, snapshot)) {
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
