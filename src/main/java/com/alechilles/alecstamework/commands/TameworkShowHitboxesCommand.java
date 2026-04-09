package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.matrix.Matrix4d;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.DetailBox;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/**
 * Toggles live hitbox/detail-box tracking for the NPC in view.
 */
public final class TameworkShowHitboxesCommand extends AbstractPlayerCommand {
    private static final long TRACK_INTERVAL_MS = 50L;
    private static final float TRACK_RENDER_SECONDS = 0.15f;
    private static final int SHAPE_FLAGS = DebugUtils.FLAG_FADE | DebugUtils.FLAG_NO_SOLID;
    private static final AtomicLong TRACKING_SEQUENCE = new AtomicLong();
    private static final Map<UUID, TrackingSession> ACTIVE_TRACKING = new ConcurrentHashMap<>();

    public TameworkShowHitboxesCommand() {
        super("showhitboxes", "Toggle live hitbox/detail-box tracking for the NPC in view.");
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

        TrackingSession removed = ACTIVE_TRACKING.remove(playerUuid);
        if (removed != null) {
            clearDebugForPlayer(playerRef);
            commandContext.sender().sendMessage(Message.raw("Hitbox tracking disabled."));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        BoundingBox boundingBoxComponent = store.getComponent(candidate.ref, BoundingBox.getComponentType());
        TransformComponent transformComponent = store.getComponent(candidate.ref, TransformComponent.getComponentType());
        NPCEntity npc = store.getComponent(candidate.ref, NPCEntity.getComponentType());
        if (boundingBoxComponent == null || transformComponent == null || npc == null) {
            commandContext.sender().sendMessage(Message.raw("Target NPC is missing required components."));
            return;
        }

        RenderStats initialRender = renderNpcHitboxes(playerRef, boundingBoxComponent, transformComponent);

        long sessionId = TRACKING_SEQUENCE.incrementAndGet();
        ACTIVE_TRACKING.put(playerUuid, new TrackingSession(sessionId, candidate.ref, world));
        scheduleTrackingTick(world, playerRef, playerUuid, sessionId);

        String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, store);
        commandContext.sender().sendMessage(Message.raw(
                "Tracking hitbox debug for NPC " + npc.getUuid()
                        + " (role=" + (roleId == null || roleId.isBlank() ? "<unknown>" : roleId)
                        + ") with per-tick updates (visible to you only). Run /tw showhitboxes again to disable."
        ));
        commandContext.sender().sendMessage(Message.raw(
                "Rendered 1 main hitbox and " + initialRender.detailBoxes + " detail boxes across "
                        + initialRender.detailGroups + " detail groups."
        ));
    }

    private static Vector3f colorForDetailGroup(String name) {
        if (name == null) {
            return DebugUtils.COLOR_CYAN;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if ("head".equals(normalized)) {
            return DebugUtils.COLOR_YELLOW;
        }
        if ("body".equals(normalized)) {
            return DebugUtils.COLOR_CYAN;
        }
        return DebugUtils.COLOR_LIME;
    }

    private static void drawLocalBox(@Nonnull PlayerRef playerRef,
                                     @Nonnull Box localBox,
                                     @Nonnull Vector3d origin,
                                     @Nonnull Vector3f color,
                                     float durationSeconds) {
        double width = localBox.width();
        double height = localBox.height();
        double depth = localBox.depth();
        if (!(width > 0.0) || !(height > 0.0) || !(depth > 0.0)) {
            return;
        }
        Matrix4d matrix = new Matrix4d();
        matrix.identity();
        matrix.translate(
                origin.x + localBox.middleX(),
                origin.y + localBox.middleY(),
                origin.z + localBox.middleZ()
        );
        matrix.scale(width, height, depth);
        DisplayDebug packet = new DisplayDebug(
                DebugShape.Cube,
                matrix.asFloatData(),
                new com.hypixel.hytale.protocol.Vector3f(color.x, color.y, color.z),
                durationSeconds,
                (byte) SHAPE_FLAGS,
                null,
                0.8F
        );
        playerRef.getPacketHandler().write((ToClientPacket) packet);
    }

    private static void clearDebugForPlayer(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().write((ToClientPacket) new ClearDebugShapes());
    }

    private static RenderStats renderNpcHitboxes(@Nonnull PlayerRef playerRef,
                                                 @Nonnull BoundingBox boundingBoxComponent,
                                                 @Nonnull TransformComponent transformComponent) {
        Vector3d entityPosition = new Vector3d(transformComponent.getPosition());
        float yaw = transformComponent.getRotation().getYaw();

        int renderedDetailBoxes = 0;
        int renderedDetailGroups = 0;

        drawLocalBox(playerRef, boundingBoxComponent.getBoundingBox(), entityPosition, DebugUtils.COLOR_RED, TRACK_RENDER_SECONDS);

        Map<String, DetailBox[]> detailBoxes = boundingBoxComponent.getDetailBoxes();
        if (detailBoxes != null && !detailBoxes.isEmpty()) {
            for (Map.Entry<String, DetailBox[]> entry : detailBoxes.entrySet()) {
                DetailBox[] boxes = entry.getValue();
                if (boxes == null || boxes.length == 0) {
                    continue;
                }
                renderedDetailGroups++;
                Vector3f color = colorForDetailGroup(entry.getKey());
                for (DetailBox detailBox : boxes) {
                    if (detailBox == null || detailBox.getBox() == null) {
                        continue;
                    }
                    Vector3d detailOrigin = new Vector3d(entityPosition);
                    Vector3d offset = detailBox.getOffset() != null ? new Vector3d(detailBox.getOffset()) : new Vector3d();
                    offset.rotateY(yaw);
                    detailOrigin.add(offset);
                    drawLocalBox(playerRef, detailBox.getBox(), detailOrigin, color, TRACK_RENDER_SECONDS);
                    renderedDetailBoxes++;
                }
            }
        }

        return new RenderStats(renderedDetailBoxes, renderedDetailGroups);
    }

    /**
     * Re-renders target hitboxes on a short interval so visuals follow moving NPCs.
     */
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

        Ref<EntityStore> npcRef = session.npcRef;
        if (!npcRef.isValid()) {
            stopTracking(playerUuid, session, playerRef, "Hitbox tracking stopped: target NPC no longer exists.");
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            ACTIVE_TRACKING.remove(playerUuid, session);
            return;
        }

        Store<EntityStore> store = npcRef.getStore();
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() != world) {
            stopTracking(playerUuid, session, playerRef, "Hitbox tracking stopped: target NPC is no longer available in this world.");
            return;
        }

        BoundingBox boundingBoxComponent = store.getComponent(npcRef, BoundingBox.getComponentType());
        TransformComponent transformComponent = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (boundingBoxComponent == null || transformComponent == null) {
            stopTracking(playerUuid, session, playerRef, "Hitbox tracking stopped: target NPC is missing required components.");
            return;
        }

        renderNpcHitboxes(playerRef, boundingBoxComponent, transformComponent);

        scheduleTrackingTick(world, playerRef, playerUuid, sessionId);
    }

    private static void stopTracking(@Nonnull UUID playerUuid,
                                     @Nonnull TrackingSession session,
                                     @Nonnull PlayerRef playerRef,
                                     @Nonnull String reason) {
        if (!ACTIVE_TRACKING.remove(playerUuid, session)) {
            return;
        }
        clearDebugForPlayer(playerRef);
        if (!reason.isBlank()) {
            playerRef.sendMessage(Message.raw(reason));
        }
    }

    private static final class TrackingSession {
        private final long sessionId;
        private final Ref<EntityStore> npcRef;
        private final World world;

        private TrackingSession(long sessionId, @Nonnull Ref<EntityStore> npcRef, @Nonnull World world) {
            this.sessionId = sessionId;
            this.npcRef = npcRef;
            this.world = world;
        }
    }

    private static final class RenderStats {
        private final int detailBoxes;
        private final int detailGroups;

        private RenderStats(int detailBoxes, int detailGroups) {
            this.detailBoxes = detailBoxes;
            this.detailGroups = detailGroups;
        }
    }
}
