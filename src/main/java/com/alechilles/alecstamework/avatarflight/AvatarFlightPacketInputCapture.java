package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightLaunchSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Observes player movement packets and stores avatar-flight input snapshots.
 */
public final class AvatarFlightPacketInputCapture {
    private static final ConcurrentHashMap<UUID, MovementIntentProjector.PositionSnapshot> LAST_ABSOLUTE_POSITIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, MovementIntentProjector.DirectionSnapshot> LAST_LOOK_DIRECTIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, MovementIntentProjector.DirectionSnapshot> LAST_BODY_DIRECTIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_MOVING_STATE_AT_MS = new ConcurrentHashMap<>();
    private static final long MOVING_DIRECTION_GRACE_MS = 150L;
    private static final double STOPPED_RESTART_SPEED_LIMIT = 0.35;
    private static final double PASSIVE_BACKWARD_FALLBACK_DEADZONE = 0.25;

    public void capture(@Nonnull ClientMovement packet, @Nonnull IPacketHandler packetHandler) {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        if (playerUuid == null || ref == null || !ref.isValid() || !AvatarFlightSessionRegistry.isActive(playerUuid)) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }
        World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> captureOnWorld(packet, playerUuid, ref, store));
    }

    public static void clear(@Nonnull UUID playerUuid) {
        LAST_ABSOLUTE_POSITIONS.remove(playerUuid);
        LAST_LOOK_DIRECTIONS.remove(playerUuid);
        LAST_BODY_DIRECTIONS.remove(playerUuid);
        LAST_MOVING_STATE_AT_MS.remove(playerUuid);
    }

    private void captureOnWorld(@Nonnull ClientMovement packet,
                                @Nonnull UUID playerUuid,
                                @Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store) {
        if (!ref.isValid() || !AvatarFlightSessionRegistry.isActive(playerUuid)
                || AvatarFlightSessionRegistry.isDisconnecting(playerUuid)) {
            return;
        }
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        ComponentType<EntityStore, AvatarFlightInputComponent> inputType = AvatarFlightInputComponent.getComponentType();
        AvatarFlightComponent flight = flightType == null ? null : store.getComponent(ref, flightType);
        if (flight == null || inputType == null) {
            return;
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        updateDirectionSnapshots(playerUuid, packet.bodyOrientation, packet.lookOrientation);
        AvatarFlightInputComponent input = store.getComponent(ref, inputType);
        if (input == null) {
            input = new AvatarFlightInputComponent();
        }
        MovementIntentProjector.MovementIntent intent = resolveIntent(playerUuid, packet);
        MovementStates packetStates = resolvePacketMovementStates(packet);
        MovementStates movementStates = packetStates != null ? packetStates : resolveMovementFallback(ref, store);
        long now = System.currentTimeMillis();
        boolean stale = input.isStale(now, Math.round(config.getInput().getIntentTimeoutMs()));
        boolean recentMovingState = isRecentMovingState(playerUuid, now);
        MovementIntentProjector.AxisProjection primary =
                resolvePrimaryProjection(playerUuid, flight, movementStates, input, stale, recentMovingState, intent, now);
        input.setForwardAxis(primary.forward());
        input.setStrafeAxis(primary.strafe());
        input.setVerticalAxis(0.0);
        boolean jumpHeld = packetStates != null && (packetStates.jumping || packetStates.swimJumping);
        boolean crouchHeld = packetStates != null && (packetStates.crouching || packetStates.forcedCrouching);
        boolean grounded = movementStates == null ? input.isOnGround() : movementStates.onGround;
        boolean jumpHoldLaunchInput = config.getLaunch().isEnabled() && usesJumpHoldLaunch(config);
        boolean crouchHoldLaunchInput = config.getLaunch().isEnabled() && usesCrouchHoldLaunch(config);
        boolean launchStateObserved = packetStates != null;
        boolean launchHeld = (jumpHoldLaunchInput && jumpHeld) || (crouchHoldLaunchInput && crouchHeld);
        handleLaunchCharge(input, now, jumpHoldLaunchInput || crouchHoldLaunchInput, launchHeld, grounded,
                launchStateObserved);
        boolean suppressLaunchJump = jumpHoldLaunchInput
                && (input.isLaunchCharging() || input.getLaunchReleasedAtMs() != 0L || (grounded && jumpHeld));
        boolean suppressLaunchCrouch = crouchHoldLaunchInput
                && (input.isLaunchCharging() || input.getLaunchReleasedAtMs() != 0L || (grounded && crouchHeld));
        input.setJumping(!suppressLaunchJump && jumpHeld);
        input.setCrouching(!suppressLaunchCrouch && crouchHeld);
        input.updateSprinting(packetStates != null && packetStates.sprinting, now);
        input.setOnGround(grounded);
        input.setYawRadians(intent.basis().yaw());
        input.setPitchRadians(intent.basis().pitch());
        if (movementStates != null || (recentMovingState && primary.hasUsableIntent())) {
            input.setLastInputAtMs(now);
        }
        store.putComponent(ref, inputType, input);
    }

    private boolean usesJumpHoldLaunch(@Nonnull TwAvatarFlightConfig config) {
        return AvatarFlightLaunchSettings.INPUT_JUMP_HOLD.equalsIgnoreCase(config.getLaunch().getPreferredInput())
                || AvatarFlightLaunchSettings.INPUT_JUMP_HOLD.equalsIgnoreCase(config.getLaunch().getFallbackInput());
    }

    private boolean usesCrouchHoldLaunch(@Nonnull TwAvatarFlightConfig config) {
        return AvatarFlightLaunchSettings.INPUT_CROUCH_HOLD.equalsIgnoreCase(config.getLaunch().getPreferredInput())
                || AvatarFlightLaunchSettings.INPUT_CROUCH_HOLD.equalsIgnoreCase(config.getLaunch().getFallbackInput());
    }

    private void handleLaunchCharge(@Nonnull AvatarFlightInputComponent input,
                                    long now,
                                    boolean launchInputConfigured,
                                    boolean launchHeld,
                                    boolean grounded,
                                    boolean launchStateObserved) {
        if (launchInputConfigured) {
            if (launchHeld && grounded) {
                input.beginLaunchCharge(now);
            } else if (launchStateObserved && !launchHeld && input.isLaunchCharging()) {
                input.queueLaunchRelease(now);
            }
        } else if (input.isLaunchCharging()) {
            input.cancelLaunchCharge();
        }
    }

    @Nullable
    private MovementStates resolvePacketMovementStates(@Nonnull ClientMovement packet) {
        return packet.riderMovementStates != null
                ? packet.riderMovementStates
                : packet.movementStates;
    }

    @Nullable
    private MovementStates resolveMovementFallback(@Nonnull Ref<EntityStore> ref,
                                                   @Nonnull Store<EntityStore> store) {
        MovementStatesComponent component = store.getComponent(ref, MovementStatesComponent.getComponentType());
        return component == null ? null : component.getMovementStates();
    }

    @Nonnull
    private MovementIntentProjector.AxisProjection resolvePrimaryProjection(
            @Nonnull UUID playerUuid,
            @Nonnull AvatarFlightComponent flight,
            @Nullable MovementStates states,
            @Nonnull AvatarFlightInputComponent input,
            boolean stale,
            boolean recentMovingState,
            @Nonnull MovementIntentProjector.MovementIntent intent,
        long now) {
        MovementIntentProjector.AxisProjection explicitWish = intent.wish();
        if (explicitWish.hasUsableIntent()) {
            LAST_MOVING_STATE_AT_MS.put(playerUuid, now);
            return explicitWish;
        }
        if (states != null && !states.horizontalIdle) {
            LAST_MOVING_STATE_AT_MS.put(playerUuid, now);
        }
        MovementIntentProjector.AxisProjection primary = intent.primaryProjection();
        primary = suppressPassiveBackwardFallback(primary, intent);
        if (isStoredFlightHorizontallyStopped(flight) && primary.hasUsableIntent()) {
            LAST_MOVING_STATE_AT_MS.put(playerUuid, now);
            return primary;
        }
        if (states != null) {
            return states.horizontalIdle ? MovementIntentProjector.AxisProjection.idle() : primary;
        }
        if (recentMovingState && intent.primaryProjection().hasUsableIntent()) {
            return intent.primaryProjection();
        }
        if (stale) {
            return MovementIntentProjector.AxisProjection.idle();
        }
        return new MovementIntentProjector.AxisProjection(
                input.getForwardAxis(),
                input.getStrafeAxis(),
                Math.hypot(input.getForwardAxis(), input.getStrafeAxis()),
                "cached"
        );
    }

    @Nonnull
    private MovementIntentProjector.AxisProjection suppressPassiveBackwardFallback(
            @Nonnull MovementIntentProjector.AxisProjection primary,
            @Nonnull MovementIntentProjector.MovementIntent intent) {
        if (intent.wish().hasUsableIntent()) {
            return primary;
        }
        if (primary.forward() < -PASSIVE_BACKWARD_FALLBACK_DEADZONE) {
            return MovementIntentProjector.AxisProjection.idle();
        }
        return primary;
    }

    private boolean isStoredFlightHorizontallyStopped(@Nonnull AvatarFlightComponent flight) {
        return Math.hypot(flight.getVelocityX(), flight.getVelocityZ()) <= STOPPED_RESTART_SPEED_LIMIT;
    }

    private boolean isRecentMovingState(@Nonnull UUID playerUuid, long now) {
        Long lastMoving = LAST_MOVING_STATE_AT_MS.get(playerUuid);
        return lastMoving != null && now - lastMoving <= MOVING_DIRECTION_GRACE_MS;
    }

    private void updateDirectionSnapshots(@Nonnull UUID playerUuid,
                                          @Nullable Direction bodyOrientation,
                                          @Nullable Direction lookOrientation) {
        if (bodyOrientation != null) {
            LAST_BODY_DIRECTIONS.put(playerUuid, MovementIntentProjector.fromDirection(bodyOrientation));
        }
        if (lookOrientation != null) {
            LAST_LOOK_DIRECTIONS.put(playerUuid, MovementIntentProjector.fromDirection(lookOrientation));
        }
    }

    @Nonnull
    private MovementIntentProjector.MovementIntent resolveIntent(@Nonnull UUID playerUuid,
                                                                 @Nonnull ClientMovement packet) {
        MovementIntentProjector.DirectionSnapshot basis = LAST_LOOK_DIRECTIONS.get(playerUuid);
        String basisName = "look";
        if (basis == null) {
            basis = LAST_BODY_DIRECTIONS.get(playerUuid);
            basisName = "body";
        }
        if (basis == null) {
            basis = new MovementIntentProjector.DirectionSnapshot(0.0, 0.0, 0.0);
            basisName = "default";
        }
        MovementIntentProjector.PositionSnapshot previous = LAST_ABSOLUTE_POSITIONS.get(playerUuid);
        Position absolute = packet.absolutePosition;
        MovementIntentProjector.MovementIntent intent = MovementIntentProjector.projectPacket(
                packet.wishMovement,
                packet.velocity,
                absolute,
                previous,
                basisName,
                basis
        );
        if (absolute != null) {
            LAST_ABSOLUTE_POSITIONS.put(playerUuid, MovementIntentProjector.fromPosition(absolute));
        }
        return intent;
    }
}
