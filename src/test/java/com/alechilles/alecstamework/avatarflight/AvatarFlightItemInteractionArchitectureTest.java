package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static checks for Dragon Reins item interactions driving avatar-flight controls. */
class AvatarFlightItemInteractionArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightInteractionControlService.java"
    );
    private static final Path FLAP = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "interactions",
            "TameworkFlightFlapInteraction.java"
    );
    private static final Path AIRBRAKE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "interactions",
            "TameworkFlightAirbrakeInteraction.java"
    );
    private static final Path BOOST = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "interactions",
            "TameworkFlightBoostInteraction.java"
    );
    private static final Path REINS_ITEM = Path.of(
            "src",
            "main",
            "resources",
            "Server",
            "Item",
            "Items",
            "Tools",
            "Tamework_Dragon_Reins.json"
    );
    private static final Path PACKET_HANDLER = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "network",
            "MountedRidePacketHandler.java"
    );
    private static final Path MOVEMENT_SYSTEM = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightMovementSystem.java"
    );

    @Test
    void itemInteractionsMutateAvatarFlightInputThroughCommandBuffer() throws Exception {
        String service = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(service.contains("context.getCommandBuffer()"));
        assertTrue(service.contains("context.getEntity()"));
        assertTrue(service.contains("plugin.getAvatarFlightComponentType()"));
        assertTrue(service.contains("plugin.getAvatarFlightInputComponentType()"));
        assertTrue(service.contains("commandBuffer.getComponent(playerRef, flightType) == null"));
        assertTrue(service.contains("input.queueReinsFlap(nowMs)"));
        assertTrue(service.contains("input.activateReinsAirbrake(nowMs, durationMs)"));
        assertTrue(service.contains("input.queueReinsBoost(nowMs)"));
        assertFalse(service.contains("input.setLastInputAtMs(nowMs)"),
                "item actions must not keep stale movement packet state fresh");
        assertTrue(service.contains("commandBuffer.putComponent(playerRef, inputType, input)"));
    }

    @Test
    void primaryAndSecondaryUseDedicatedFlightInteractions() throws Exception {
        String flap = Files.readString(FLAP, StandardCharsets.UTF_8);
        String airbrake = Files.readString(AIRBRAKE, StandardCharsets.UTF_8);
        String boost = Files.readString(BOOST, StandardCharsets.UTF_8);

        assertTrue(flap.contains("AvatarFlightInteractionControlService.queueFlap"));
        assertTrue(airbrake.contains("AvatarFlightInteractionControlService.activateAirbrake"));
        assertTrue(boost.contains("AvatarFlightInteractionControlService.queueBoost"));
        assertTrue(airbrake.contains("\"DurationMs\""));
        assertTrue(flap.contains("InteractionState.Failed"));
        assertTrue(airbrake.contains("InteractionState.Failed"));
        assertTrue(boost.contains("InteractionState.Failed"));
    }

    @Test
    void flightmastersReinsWireQAbilityToBoostInteraction() throws Exception {
        String item = Files.readString(REINS_ITEM, StandardCharsets.UTF_8);

        assertTrue(item.contains("\"Ability1\""),
                "Hytale maps the first item ability slot to the Q action");
        assertTrue(item.contains("\"Type\": \"TameworkFlightBoost\""));
    }

    @Test
    void dragonReinsButtonsDoNotUseRawMousePacketCapture() throws Exception {
        String handler = Files.readString(PACKET_HANDLER, StandardCharsets.UTF_8);

        assertFalse(handler.contains("AvatarFlightReinsInputCapture"));
        assertFalse(handler.contains("avatarFlightReinsInputCapture"));
    }

    @Test
    void reinsActionsDoNotDependOnMovementInputFreshness() throws Exception {
        String movementSystem = Files.readString(MOVEMENT_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(movementSystem.contains("input.consumeReinsFlap("));
        assertFalse(movementSystem.contains("!stale && input.consumeReinsFlap()"),
                "left-click flap must not be ignored just because movement packet intent is stale");
        assertTrue(movementSystem.contains("input.clearTransientVerticalIntent()"),
                "jump/crouch packet state must not latch across controller ticks");
    }
}
