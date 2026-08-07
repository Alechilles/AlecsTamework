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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Maintains non-persistent visual proxies for loaded natural spawn beacons.
 */
final class SpawnBeaconVisualizationService {
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final int MAX_SUMMARIES = 8;

    private final AtomicLong sessionSequence = new AtomicLong();
    private final Map<UUID, TrackingSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<World, WorldState> worldStates = new ConcurrentHashMap<>();

    EnableResult enable(@Nonnull World world,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull PlayerRef playerRef,
                        double radius) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return new EnableResult(0, 0, radius, List.of());
        }

        long sessionId = sessionSequence.incrementAndGet();
        TrackingSession session = new TrackingSession(sessionId, world, store, playerRef, radius);
        TrackingSession previous = activeSessions.put(playerUuid, session);
        if (previous != null && previous.world() != world) {
            scheduleRefresh(previous.world(), previous.store());
        }

        RefreshResult refresh = refreshWorld(world, store);
        scheduleTrackingTick(playerUuid, session);
        return new EnableResult(refresh.visibleCount(), refresh.skippedCount(), radius, refresh.summaries());
    }

    DisableResult disable(@Nonnull UUID playerUuid,
                          @Nonnull World currentWorld,
                          @Nonnull Store<EntityStore> currentStore) {
        TrackingSession removed = activeSessions.remove(playerUuid);
        if (removed == null) {
            return new DisableResult(false);
        }
        if (removed.world() == currentWorld) {
            refreshWorld(currentWorld, currentStore);
        } else {
            scheduleRefresh(removed.world(), removed.store());
        }
        return new DisableResult(true);
    }

    private void scheduleTrackingTick(@Nonnull UUID playerUuid, @Nonnull TrackingSession session) {
        CompletableFuture.runAsync(
                () -> session.world().execute(() -> runTrackingTick(playerUuid, session)),
                CompletableFuture.delayedExecutor(REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void runTrackingTick(@Nonnull UUID playerUuid, @Nonnull TrackingSession expected) {
        TrackingSession current = activeSessions.get(playerUuid);
        if (current != expected || current.sessionId() != expected.sessionId()) {
            return;
        }

        refreshWorld(expected.world(), expected.store());
        if (activeSessions.get(playerUuid) == expected) {
            scheduleTrackingTick(playerUuid, expected);
        }
    }

    private void scheduleRefresh(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        world.execute(() -> refreshWorld(world, store));
    }

    private RefreshResult refreshWorld(@Nonnull World world, @Nonnull Store<EntityStore> store) {
        List<SpawnBeaconVisualizationCoverage.ViewerRange> viewers = collectViewers(world, store);
        WorldState state = worldStates.get(world);
        if (viewers.isEmpty()) {
            if (state != null) {
                removeAllProxies(store, state);
                worldStates.remove(world, state);
            }
            return RefreshResult.EMPTY;
        }

        if (state == null) {
            state = new WorldState();
            worldStates.put(world, state);
        }

        List<BeaconSnapshot> covered = collectCoveredBeacons(store, viewers);
        Set<UUID> retainedSources = new HashSet<>();
        int skipped = 0;
        List<BeaconSummary> summaries = new ArrayList<>();
        for (BeaconSnapshot beacon : covered) {
            retainedSources.add(beacon.sourceUuid());
            Ref<EntityStore> existing = state.proxies.get(beacon.sourceUuid());
            if (existing != null && existing.isValid()) {
                addSummary(summaries, beacon);
                continue;
            }
            if (existing != null) {
                state.proxies.remove(beacon.sourceUuid());
            }
            Ref<EntityStore> proxy = createProxy(store, beacon, state);
            if (proxy == null) {
                skipped++;
            } else {
                state.proxies.put(beacon.sourceUuid(), proxy);
                addSummary(summaries, beacon);
            }
        }

        removeStaleProxies(store, state, retainedSources);
        return new RefreshResult(covered.size() - skipped, skipped, List.copyOf(summaries));
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
            if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() != store) {
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

    private List<BeaconSnapshot> collectCoveredBeacons(
            @Nonnull Store<EntityStore> store,
            @Nonnull Collection<SpawnBeaconVisualizationCoverage.ViewerRange> viewers) {
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
                LegacySpawnBeaconEntity beacon = chunk.getComponent(
                        index, beaconType
                );
                TransformComponent transform = chunk.getComponent(
                        index, TransformComponent.getComponentType()
                );
                UUIDComponent uuid = chunk.getComponent(index, UUIDComponent.getComponentType());
                if (beacon == null || transform == null || uuid == null || uuid.getUuid() == null) {
                    continue;
                }
                Vector3d position = new Vector3d(transform.getPosition());
                if (!SpawnBeaconVisualizationCoverage.isCovered(position, viewers)) {
                    continue;
                }
                String configId = beacon.getSpawnConfigId();
                if ((configId == null || configId.isBlank()) && beacon.getSpawnWrapper() != null) {
                    configId = beacon.getSpawnWrapper().getSpawn().getId();
                }
                beacons.add(new BeaconSnapshot(
                        uuid.getUuid(),
                        configId,
                        position,
                        new Rotation3f(transform.getRotation()),
                        beacon
                ));
            }
        });
        return beacons;
    }

    private Ref<EntityStore> createProxy(@Nonnull Store<EntityStore> store,
                                         @Nonnull BeaconSnapshot snapshot,
                                         @Nonnull WorldState state) {
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
                                    @Nonnull WorldState state,
                                    @Nonnull Set<UUID> retainedSources) {
        var iterator = state.proxies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Ref<EntityStore>> entry = iterator.next();
            if (retainedSources.contains(entry.getKey())) {
                continue;
            }
            removeProxy(store, entry.getValue());
            iterator.remove();
            state.warnedSources.remove(entry.getKey());
        }
    }

    private void removeAllProxies(@Nonnull Store<EntityStore> store, @Nonnull WorldState state) {
        for (Ref<EntityStore> proxy : state.proxies.values()) {
            removeProxy(store, proxy);
        }
        state.proxies.clear();
        state.warnedSources.clear();
    }

    private void removeProxy(@Nonnull Store<EntityStore> store, Ref<EntityStore> proxy) {
        if (proxy != null && proxy.isValid()) {
            store.removeEntity(proxy, RemoveReason.REMOVE);
        }
    }

    private void warnOnce(@Nonnull BeaconSnapshot snapshot,
                          @Nonnull WorldState state,
                          @Nonnull RuntimeException failure) {
        if (!state.warnedSources.add(snapshot.sourceUuid())) {
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

    private record TrackingSession(long sessionId,
                                   World world,
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

    private record RefreshResult(int visibleCount,
                                 int skippedCount,
                                 List<BeaconSummary> summaries) {
        private static final RefreshResult EMPTY = new RefreshResult(0, 0, List.of());
    }

    private static final class WorldState {
        private final Map<UUID, Ref<EntityStore>> proxies = new HashMap<>();
        private final Set<UUID> warnedSources = new HashSet<>();
    }
}
