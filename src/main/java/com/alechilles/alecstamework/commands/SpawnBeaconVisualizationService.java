package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.SpawningPlugin;
import com.hypixel.hytale.server.spawning.assets.spawns.config.BeaconNPCSpawn;
import com.hypixel.hytale.server.spawning.beacons.LegacySpawnBeaconEntity;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Maintains non-persistent visual proxies for loaded natural spawn beacons.
 */
public final class SpawnBeaconVisualizationService implements AutoCloseable {
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final int MAX_SUMMARIES = 8;

    private final Map<UUID, TrackingSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<SpawnBeaconVisualizationWorldState.Key, SpawnBeaconVisualizationWorldState>
            worldStates = new ConcurrentHashMap<>();
    private final SpawnBeaconVisualizationRemovedWorlds removedWorlds =
            new SpawnBeaconVisualizationRemovedWorlds();
    private volatile boolean closed;

    synchronized EnableResult enable(@Nonnull World world,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull PlayerRef playerRef,
                                     double radius) {
        UUID playerUuid = playerRef.getUuid();
        var worldKey = new SpawnBeaconVisualizationWorldState.Key(world);
        if (closed || removedWorlds.contains(world) || playerUuid == null) {
            return new EnableResult(0, 0, radius, List.of());
        }

        TrackingSession session = new TrackingSession(world, store, playerRef, radius);
        TrackingSession previous = activeSessions.put(playerUuid, session);
        if (previous != null && previous.world() != world) {
            requestRefresh(previous.world());
        }

        SpawnBeaconVisualizationWorldState state = getOrCreateWorldState(world, store);
        RefreshResult refresh = refreshWorld(state);
        if (refresh.stateActive() && state.startLoop()) {
            scheduleWorldTick(state);
        }
        return buildEnableResult(refresh.loadedBeacons(), state, playerRef, store, radius);
    }

    synchronized DisableResult disable(@Nonnull UUID playerUuid,
                                       @Nonnull World currentWorld,
                                       @Nonnull Store<EntityStore> currentStore) {
        TrackingSession removed = activeSessions.remove(playerUuid);
        if (removed == null) {
            return new DisableResult(false);
        }
        if (removed.world() == currentWorld && removed.store() == currentStore) {
            SpawnBeaconVisualizationWorldState state = worldStates.get(
                    new SpawnBeaconVisualizationWorldState.Key(currentWorld)
            );
            if (state != null) {
                refreshWorld(state);
            }
        } else {
            requestRefresh(removed.world());
        }
        return new DisableResult(true);
    }

    /** Drops all tracking and owned references for a world that is being removed. */
    public synchronized void removeWorld(@Nonnull World world) {
        var worldKey = new SpawnBeaconVisualizationWorldState.Key(world);
        removedWorlds.add(world);
        activeSessions.entrySet().removeIf(entry -> entry.getValue().world() == world);
        SpawnBeaconVisualizationWorldState state = worldStates.remove(worldKey);
        if (state != null) {
            state.deactivate();
            state.clearOwnership();
        }
    }

    /** Stops future refreshes and removes proxies from worlds that still accept work. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        activeSessions.clear();
        removedWorlds.clear();
        List<SpawnBeaconVisualizationWorldState> states = List.copyOf(worldStates.values());
        worldStates.clear();
        for (SpawnBeaconVisualizationWorldState state : states) {
            state.deactivate();
            try {
                state.world().execute(() -> removeAllProxies(state.store(), state));
            } catch (RuntimeException ignored) {
                state.clearOwnership();
            }
        }
    }

    private SpawnBeaconVisualizationWorldState getOrCreateWorldState(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store) {
        var key = new SpawnBeaconVisualizationWorldState.Key(world);
        SpawnBeaconVisualizationWorldState current = worldStates.get(key);
        if (current != null) {
            return current;
        }
        var created = new SpawnBeaconVisualizationWorldState(world, store);
        SpawnBeaconVisualizationWorldState raced = worldStates.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private void scheduleWorldTick(@Nonnull SpawnBeaconVisualizationWorldState state) {
        CompletableFuture.runAsync(
                () -> dispatchWorldTick(state),
                CompletableFuture.delayedExecutor(REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void dispatchWorldTick(@Nonnull SpawnBeaconVisualizationWorldState state) {
        if (!isCurrent(state)) {
            return;
        }
        try {
            state.world().execute(() -> runWorldTick(state));
        } catch (RuntimeException ignored) {
            removeWorld(state.world());
        }
    }

    private synchronized void runWorldTick(
            @Nonnull SpawnBeaconVisualizationWorldState state) {
        if (!isCurrent(state)) {
            return;
        }
        RefreshResult refresh = refreshWorld(state);
        if (refresh.stateActive() && isCurrent(state)) {
            scheduleWorldTick(state);
        }
    }

    private boolean isCurrent(@Nonnull SpawnBeaconVisualizationWorldState state) {
        return !closed
                && state.active()
                && worldStates.get(new SpawnBeaconVisualizationWorldState.Key(state.world())) == state;
    }

    private void requestRefresh(@Nonnull World world) {
        SpawnBeaconVisualizationWorldState state = worldStates.get(
                new SpawnBeaconVisualizationWorldState.Key(world)
        );
        if (state == null || !isCurrent(state)) {
            return;
        }
        try {
            world.execute(() -> runRequestedRefresh(state));
        } catch (RuntimeException ignored) {
            removeWorld(world);
        }
    }

    private synchronized void runRequestedRefresh(
            @Nonnull SpawnBeaconVisualizationWorldState state) {
        if (isCurrent(state)) {
            refreshWorld(state);
        }
    }

    private RefreshResult refreshWorld(@Nonnull SpawnBeaconVisualizationWorldState state) {
        World world = state.world();
        Store<EntityStore> store = state.store();
        List<SpawnBeaconVisualizationCoverage.ViewerRange> viewers = collectViewers(world, store);
        if (viewers.isEmpty()) {
            removeAllProxies(store, state);
            worldStates.remove(new SpawnBeaconVisualizationWorldState.Key(world), state);
            state.deactivate();
            return RefreshResult.EMPTY;
        }

        List<BeaconSnapshot> loaded = collectLoadedBeacons(store);
        Set<UUID> retainedSources = new HashSet<>();
        for (BeaconSnapshot beacon : loaded) {
            if (!SpawnBeaconVisualizationCoverage.isCovered(beacon.position(), viewers)) {
                continue;
            }
            retainedSources.add(beacon.sourceUuid());
            Ref<EntityStore> existing = state.proxies().get(beacon.sourceUuid());
            if (existing != null && existing.isValid()) {
                continue;
            }
            if (existing != null) {
                state.proxies().remove(beacon.sourceUuid());
            }
            Ref<EntityStore> proxy = createProxy(store, beacon, state);
            if (proxy != null) {
                state.proxies().put(beacon.sourceUuid(), proxy);
            }
        }

        removeStaleProxies(store, state, retainedSources);
        return new RefreshResult(List.copyOf(loaded), true);
    }

    private EnableResult buildEnableResult(
            @Nonnull Collection<BeaconSnapshot> loaded,
            @Nonnull SpawnBeaconVisualizationWorldState state,
            @Nonnull PlayerRef playerRef,
            @Nonnull Store<EntityStore> store,
            double radius) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()
                || playerEntityRef.getStore() != store) {
            return new EnableResult(0, 0, radius, List.of());
        }
        TransformComponent transform = store.getComponent(
                playerEntityRef, TransformComponent.getComponentType()
        );
        if (transform == null) {
            return new EnableResult(0, 0, radius, List.of());
        }

        var viewer = new SpawnBeaconVisualizationCoverage.ViewerRange(
                new Vector3d(transform.getPosition()), radius
        );
        int visible = 0;
        int skipped = 0;
        List<BeaconSummary> summaries = new ArrayList<>();
        for (BeaconSnapshot beacon : loaded) {
            if (!SpawnBeaconVisualizationCoverage.isCovered(beacon.position(), List.of(viewer))) {
                continue;
            }
            Ref<EntityStore> proxy = state.proxies().get(beacon.sourceUuid());
            if (proxy == null || !proxy.isValid()) {
                skipped++;
                continue;
            }
            visible++;
            addSummary(summaries, beacon);
        }
        return new EnableResult(visible, skipped, radius, List.copyOf(summaries));
    }

    private List<SpawnBeaconVisualizationCoverage.ViewerRange> collectViewers(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store) {
        List<SpawnBeaconVisualizationCoverage.ViewerRange> viewers = new ArrayList<>();
        for (Map.Entry<UUID, TrackingSession> entry : activeSessions.entrySet()) {
            TrackingSession session = entry.getValue();
            if (session.world() != world) {
                continue;
            }
            Ref<EntityStore> playerEntityRef = session.playerRef().getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()
                    || playerEntityRef.getStore() != store) {
                activeSessions.remove(entry.getKey(), session);
                continue;
            }
            TransformComponent transform = store.getComponent(
                    playerEntityRef, TransformComponent.getComponentType()
            );
            if (transform == null) {
                activeSessions.remove(entry.getKey(), session);
                continue;
            }
            viewers.add(new SpawnBeaconVisualizationCoverage.ViewerRange(
                    new Vector3d(transform.getPosition()), session.radius()
            ));
        }
        return viewers;
    }

    private List<BeaconSnapshot> collectLoadedBeacons(@Nonnull Store<EntityStore> store) {
        List<BeaconSnapshot> beacons = new ArrayList<>();
        ComponentType<EntityStore, LegacySpawnBeaconEntity> beaconType =
                LegacySpawnBeaconEntity.getComponentType();
        if (beaconType == null) {
            return beacons;
        }
        Query<EntityStore> query = Query.and(
                beaconType,
                TransformComponent.getComponentType(),
                UUIDComponent.getComponentType()
        );
        store.forEachChunk(query, (ArchetypeChunk<EntityStore> chunk,
                                   CommandBuffer<EntityStore> commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                LegacySpawnBeaconEntity beacon = chunk.getComponent(index, beaconType);
                TransformComponent transform = chunk.getComponent(
                        index, TransformComponent.getComponentType()
                );
                UUIDComponent uuid = chunk.getComponent(index, UUIDComponent.getComponentType());
                if (beacon == null || transform == null || uuid == null || uuid.getUuid() == null) {
                    continue;
                }
                String configId = beacon.getSpawnConfigId();
                if ((configId == null || configId.isBlank())
                        && beacon.getSpawnWrapper() != null
                        && beacon.getSpawnWrapper().getSpawn() != null) {
                    configId = beacon.getSpawnWrapper().getSpawn().getId();
                }
                beacons.add(new BeaconSnapshot(
                        uuid.getUuid(),
                        configId,
                        new Vector3d(transform.getPosition()),
                        new Rotation3f(transform.getRotation()),
                        beacon
                ));
            }
        });
        return beacons;
    }

    private Ref<EntityStore> createProxy(@Nonnull Store<EntityStore> store,
                                         @Nonnull BeaconSnapshot snapshot,
                                         @Nonnull SpawnBeaconVisualizationWorldState state) {
        try {
            Model model = resolveModel(snapshot.beacon());
            String configId = snapshot.configId();
            if (model == null || configId == null || configId.isBlank()
                    || store.getExternalData() == null) {
                return null;
            }

            Message displayName = Message.raw(configId);
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.addComponent(
                    NetworkId.getComponentType(),
                    new NetworkId(store.getExternalData().takeNextNetworkId())
            );
            holder.addComponent(
                    EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get()
            );
            holder.addComponent(
                    TransformComponent.getComponentType(),
                    new TransformComponent(snapshot.position(), snapshot.rotation())
            );
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            holder.addComponent(
                    DisplayNameComponent.getComponentType(), new DisplayNameComponent(displayName)
            );
            holder.addComponent(Nameplate.getComponentType(), new Nameplate(configId));
            holder.ensureComponent(HiddenFromAdventurePlayers.getComponentType());
            holder.ensureComponent(EntityTrackerSystems.Visible.getComponentType());
            return store.addEntity(holder, AddReason.SPAWN);
        } catch (RuntimeException failure) {
            warnOnce(snapshot, state, failure);
            return null;
        }
    }

    private void addSummary(@Nonnull List<BeaconSummary> summaries,
                            @Nonnull BeaconSnapshot beacon) {
        if (summaries.size() < MAX_SUMMARIES) {
            summaries.add(new BeaconSummary(beacon.configId(), new Vector3d(beacon.position())));
        }
    }

    private Model resolveModel(@Nonnull LegacySpawnBeaconEntity beacon) {
        if (beacon.getSpawnWrapper() == null || beacon.getSpawnWrapper().getSpawn() == null) {
            return null;
        }
        BeaconNPCSpawn spawn = beacon.getSpawnWrapper().getSpawn();
        String modelId = spawn.getModel();
        if (modelId != null && !modelId.isBlank()) {
            ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelId);
            if (modelAsset != null) {
                return Model.createUnitScaleModel(modelAsset);
            }
        }
        return SpawningPlugin.get().getSpawnMarkerModel();
    }

    private void removeStaleProxies(@Nonnull Store<EntityStore> store,
                                    @Nonnull SpawnBeaconVisualizationWorldState state,
                                    @Nonnull Set<UUID> retainedSources) {
        var iterator = state.proxies().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Ref<EntityStore>> entry = iterator.next();
            if (retainedSources.contains(entry.getKey())) {
                continue;
            }
            removeProxy(store, entry.getValue());
            iterator.remove();
            state.warnedSources().remove(entry.getKey());
        }
    }

    private void removeAllProxies(@Nonnull Store<EntityStore> store,
                                  @Nonnull SpawnBeaconVisualizationWorldState state) {
        for (Ref<EntityStore> proxy : state.proxies().values()) {
            removeProxy(store, proxy);
        }
        state.clearOwnership();
    }

    private void removeProxy(@Nonnull Store<EntityStore> store, Ref<EntityStore> proxy) {
        if (proxy != null && proxy.isValid()) {
            store.removeEntity(proxy, RemoveReason.REMOVE);
        }
    }

    private void warnOnce(@Nonnull BeaconSnapshot snapshot,
                          @Nonnull SpawnBeaconVisualizationWorldState state,
                          @Nonnull RuntimeException failure) {
        if (!state.warnedSources().add(snapshot.sourceUuid())) {
            return;
        }
        Tamework plugin = Tamework.getInstance();
        if (plugin != null) {
            plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                    "Failed to create spawn beacon debug proxy for source %s (config=%s).",
                    snapshot.sourceUuid(), snapshot.configId()
            );
        }
    }

    record EnableResult(int visibleCount,
                        int skippedCount,
                        double radius,
                        List<BeaconSummary> summaries) {
    }

    record DisableResult(boolean wasActive) {
    }

    record BeaconSummary(String configId, Vector3d position) {
    }

    private record TrackingSession(World world,
                                   Store<EntityStore> store,
                                   PlayerRef playerRef,
                                   double radius) {
    }

    private record BeaconSnapshot(UUID sourceUuid,
                                  String configId,
                                  Vector3d position,
                                  Rotation3f rotation,
                                  LegacySpawnBeaconEntity beacon) {
    }

    private record RefreshResult(List<BeaconSnapshot> loadedBeacons, boolean stateActive) {
        private static final RefreshResult EMPTY = new RefreshResult(List.of(), false);
    }

}
