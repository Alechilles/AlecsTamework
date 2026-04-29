package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.matrix.Matrix4d;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnmarker.config.SpawnMarker;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/**
 * Toggles player-local debug rendering for loaded Hytale spawn marker entities.
 */
public final class TameworkShowSpawnMarkersCommand extends AbstractPlayerCommand {
    private static final long TRACK_INTERVAL_MS = 1000L;
    private static final float TRACK_RENDER_SECONDS = 1.25f;
    private static final TameworkShowSpawnMarkersCommandSupport.DebugStyle DEBUG_STYLE =
            TameworkShowSpawnMarkersCommandSupport.debugStyle();
    private static final Vector3f MARKER_COLOR =
            new Vector3f(DEBUG_STYLE.red(), DEBUG_STYLE.green(), DEBUG_STYLE.blue());
    private static final int SHAPE_FLAGS = resolveShapeFlags();
    private static final int MAX_SUMMARY_ROWS = 8;
    private static final int MAX_NPC_SUMMARY_ROWS = 4;
    private static final AtomicLong TRACKING_SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<UUID, TrackingSession> ACTIVE_TRACKING = new ConcurrentHashMap<>();

    public TameworkShowSpawnMarkersCommand() {
        super("showspawnmarkers", "Toggle player-local spawn marker debug rendering.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve your player UUID."));
            return;
        }

        TameworkShowSpawnMarkersCommandSupport.ParseResult parse =
                TameworkShowSpawnMarkersCommandSupport.parse(commandContext.getInputString());
        if (parse.mode() == TameworkShowSpawnMarkersCommandSupport.Mode.INVALID) {
            commandContext.sender().sendMessage(Message.raw(
                    "Usage: /tw showspawnmarkers [radius|off]"
            ));
            return;
        }
        if (parse.mode() == TameworkShowSpawnMarkersCommandSupport.Mode.OFF) {
            TrackingSession removed = ACTIVE_TRACKING.remove(playerUuid);
            clearDebugForPlayer(playerRef);
            commandContext.sender().sendMessage(Message.raw(
                    removed != null ? "Spawn marker debug rendering disabled." : "Spawn marker debug rendering was not active."
            ));
            return;
        }

        TransformComponent playerTransform = store.getComponent(ref, TransformComponent.getComponentType());
        if (playerTransform == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve your position."));
            return;
        }

        List<MarkerSnapshot> markers = collectMarkers(store, playerTransform.getPosition(), parse.radius());
        renderMarkers(playerRef, markers);

        long sessionId = TRACKING_SEQUENCE.incrementAndGet();
        ACTIVE_TRACKING.put(playerUuid, new TrackingSession(sessionId, world, parse.radius()));
        scheduleTrackingTick(world, playerRef, playerUuid, sessionId);

        commandContext.sender().sendMessage(Message.raw(
                "Spawn marker debug rendering enabled within "
                        + formatNumber(parse.radius())
                        + " blocks. Found "
                        + markers.size()
                        + " loaded marker"
                        + (markers.size() == 1 ? "" : "s")
                        + ". Run /tw showspawnmarkers off to disable."
        ));
        sendMarkerSummary(commandContext, markers);
    }

    private static void sendMarkerSummary(@Nonnull CommandContext commandContext, @Nonnull List<MarkerSnapshot> markers) {
        int rows = Math.min(MAX_SUMMARY_ROWS, markers.size());
        for (int i = 0; i < rows; i++) {
            MarkerSnapshot marker = markers.get(i);
            commandContext.sender().sendMessage(Message.raw(
                    "  "
                            + marker.markerId
                            + " at "
                            + formatPosition(marker.position)
                            + ", distance="
                            + formatNumber(marker.distance)
                            + ", npc="
                            + marker.npcSummary
                            + ", spawnCount="
                            + marker.spawnCount
                            + ", manual="
                            + marker.manualTrigger
                            + ", suppressed="
                            + marker.suppressed
            ));
        }
        if (markers.size() > rows) {
            commandContext.sender().sendMessage(Message.raw(
                    "  ...and " + (markers.size() - rows) + " more loaded marker(s)."
            ));
        }
    }

    private static List<MarkerSnapshot> collectMarkers(@Nonnull Store<EntityStore> store,
                                                       @Nonnull Vector3d playerPosition,
                                                       double radius) {
        List<MarkerSnapshot> markers = new ArrayList<>();
        double radiusSq = radius * radius;
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                SpawnMarkerEntity marker = chunk.getComponent(i, SpawnMarkerEntity.getComponentType());
                if (marker == null) {
                    continue;
                }
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) {
                    continue;
                }
                Vector3d position = transform.getPosition();
                double dx = position.x - playerPosition.x;
                double dy = position.y - playerPosition.y;
                double dz = position.z - playerPosition.z;
                double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distanceSq > radiusSq) {
                    continue;
                }
                SpawnMarker spawnMarker = marker.getCachedMarker();
                if (spawnMarker == null) {
                    spawnMarker = resolveSpawnMarker(marker.getSpawnMarkerId());
                }
                markers.add(new MarkerSnapshot(
                        marker.getSpawnMarkerId(),
                        describeNpcOptions(spawnMarker),
                        new Vector3d(position),
                        Math.sqrt(distanceSq),
                        marker.getSpawnCount(),
                        marker.isManualTrigger(),
                        marker.getSuppressedBy() != null && !marker.getSuppressedBy().isEmpty()
                ));
            }
        });
        markers.sort(Comparator.comparingDouble(marker -> marker.distance));
        return markers;
    }

    private static void renderMarkers(@Nonnull PlayerRef playerRef, @Nonnull List<MarkerSnapshot> markers) {
        for (MarkerSnapshot marker : markers) {
            drawMarker(playerRef, marker);
        }
    }

    private static void drawMarker(@Nonnull PlayerRef playerRef, @Nonnull MarkerSnapshot marker) {
        drawBox(
                playerRef,
                marker.position,
                DEBUG_STYLE.markerWidth(),
                DEBUG_STYLE.markerHeight(),
                DEBUG_STYLE.markerDepth(),
                MARKER_COLOR,
                TRACK_RENDER_SECONDS,
                DEBUG_STYLE.markerAlpha()
        );

        Vector3d capPosition = new Vector3d(marker.position);
        capPosition.y += DEBUG_STYLE.markerHeight() + 0.25;
        drawBox(
                playerRef,
                capPosition,
                DEBUG_STYLE.capWidth(),
                DEBUG_STYLE.capHeight(),
                DEBUG_STYLE.capDepth(),
                marker.suppressed ? DebugUtils.COLOR_RED : DebugUtils.COLOR_YELLOW,
                TRACK_RENDER_SECONDS,
                DEBUG_STYLE.capAlpha()
        );
    }

    private static String describeNpcOptions(SpawnMarker spawnMarker) {
        if (spawnMarker == null || spawnMarker.getWeightedConfigurations() == null) {
            return "unknown";
        }

        List<String> npcIds = new ArrayList<>();
        spawnMarker.getWeightedConfigurations().forEach(configuration -> {
            if (configuration != null && configuration.getNpc() != null && !configuration.getNpc().isBlank()) {
                npcIds.add(configuration.getNpc());
            }
        });
        return TameworkShowSpawnMarkersCommandSupport.formatNpcSummary(npcIds, MAX_NPC_SUMMARY_ROWS);
    }

    private static SpawnMarker resolveSpawnMarker(String markerId) {
        if (markerId == null || markerId.isBlank()) {
            return null;
        }
        try {
            return SpawnMarker.getAssetMap().getAsset(markerId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void drawBox(@Nonnull PlayerRef playerRef,
                                @Nonnull Vector3d center,
                                double width,
                                double height,
                                double depth,
                                @Nonnull Vector3f color,
                                float durationSeconds,
                                float alpha) {
        Matrix4d matrix = new Matrix4d();
        matrix.identity();
        matrix.translate(center.x, center.y + (height / 2.0), center.z);
        matrix.scale(width, height, depth);
        DisplayDebug packet = new DisplayDebug(
                DebugShape.Cube,
                matrix.asFloatData(),
                new com.hypixel.hytale.protocol.Vector3f(color.x, color.y, color.z),
                durationSeconds,
                (byte) SHAPE_FLAGS,
                null,
                alpha
        );
        playerRef.getPacketHandler().write((ToClientPacket) packet);
    }

    private static void clearDebugForPlayer(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().write((ToClientPacket) new ClearDebugShapes());
    }

    private static void scheduleTrackingTick(@Nonnull World world,
                                             @Nonnull PlayerRef playerRef,
                                             @Nonnull UUID playerUuid,
                                             long sessionId) {
        CompletableFuture.runAsync(
                () -> world.execute(() -> runTrackingTickOnWorldThread(world, playerRef, playerUuid, sessionId)),
                CompletableFuture.delayedExecutor(TRACK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private static void runTrackingTickOnWorldThread(@Nonnull World world,
                                                     @Nonnull PlayerRef playerRef,
                                                     @Nonnull UUID playerUuid,
                                                     long sessionId) {
        TrackingSession session = ACTIVE_TRACKING.get(playerUuid);
        if (session == null || session.sessionId != sessionId) {
            return;
        }
        if (session.world != world) {
            ACTIVE_TRACKING.remove(playerUuid, session);
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            ACTIVE_TRACKING.remove(playerUuid, session);
            return;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() != world) {
            ACTIVE_TRACKING.remove(playerUuid, session);
            return;
        }
        TransformComponent playerTransform = store.getComponent(playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            ACTIVE_TRACKING.remove(playerUuid, session);
            return;
        }

        renderMarkers(playerRef, collectMarkers(store, playerTransform.getPosition(), session.radius));
        scheduleTrackingTick(world, playerRef, playerUuid, sessionId);
    }

    private static int resolveShapeFlags() {
        return resolveOptionalDebugFlag("FLAG_FADE") | resolveOptionalDebugFlag("FLAG_NO_SOLID");
    }

    private static int resolveOptionalDebugFlag(@Nonnull String fieldName) {
        try {
            Field field = DebugUtils.class.getField(fieldName);
            return field.getInt(null);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return 0;
        }
    }

    private static String formatPosition(@Nonnull Vector3d position) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", position.x, position.y, position.z);
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record TrackingSession(long sessionId, World world, double radius) {
    }

    private record MarkerSnapshot(String markerId,
                                  String npcSummary,
                                  Vector3d position,
                                  double distance,
                                  int spawnCount,
                                  boolean manualTrigger,
                                  boolean suppressed) {
    }
}
