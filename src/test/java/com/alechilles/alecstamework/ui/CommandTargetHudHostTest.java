package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies fixed-key target HUD hosting and controller failure isolation. */
class CommandTargetHudHostTest {
    @Test
    void delegatesInitialAndPartialUpdatesToOneFixedHud() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        RecordingController controller = new RecordingController();
        CommandTargetHudHost host = new CommandTargetHudHost(
                playerRef,
                new CommandHudOpenContext(
                        playerRef.getUuid(), "en-US", "tool", "item", "config",
                        CommandHudSurface.TARGET, "example:renderer", UUID.randomUUID(),
                        "target-key", 1L),
                controller,
                new CommandTargetHudView(snapshot(1), null)
        );

        host.build(new UICommandBuilder());
        assertTrue(host.isOpen());
        assertEquals(1, controller.initialBuilds.get());
        assertTrue(host.applyUpdate(new CommandTargetHudUpdate(
                new CommandTargetHudView(snapshot(2), null),
                new CommandTargetHudView(snapshot(1), null),
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet.full())));
        assertEquals(1, controller.updates.get());
        CustomHud updatePacket = assertInstanceOf(CustomHud.class, packets.lastPacket);
        assertEquals(CommandTargetHudHost.HUD_KEY, updatePacket.hudId);
        assertEquals(0, updatePacket.zOrder);
        assertFalse(updatePacket.clear);
    }

    @Test
    void rejectedInitialGateDoesNotBuildOrSendAndReportsFailureOnce() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        RecordingController controller = new RecordingController();
        AtomicInteger failures = new AtomicInteger();
        CommandTargetHudHost host = new CommandTargetHudHost(
                playerRef, new CommandHudOpenContext(), controller,
                new CommandTargetHudView(snapshot(1), null),
                (phase, failure) -> failures.incrementAndGet(),
                update -> { update.run(); return true; },
                (build, update) -> false);

        host.show();
        host.show();

        assertFalse(host.isOpen());
        assertEquals(1, failures.get());
        assertEquals(0, controller.initialBuilds.get());
        assertNull(packets.lastPacket);
    }

    @Test
    void rejectedUpdateGateDoesNotSendRenderedUpdate() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        RecordingController controller = new RecordingController();
        CommandTargetHudHost host = new CommandTargetHudHost(
                playerRef, new CommandHudOpenContext(), controller,
                new CommandTargetHudView(snapshot(1), null),
                (phase, failure) -> { }, update -> false);
        host.build(new UICommandBuilder());

        boolean sent = host.applyUpdate(new CommandTargetHudUpdate(
                new CommandTargetHudView(snapshot(2), null),
                new CommandTargetHudView(snapshot(1), null),
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet.full()));

        assertFalse(sent);
        assertTrue(host.isOpen());
        assertEquals(1, controller.updates.get());
        assertNull(packets.lastPacket);
    }

    @Test
    void buildFailureClosesHostAndReportsFailureOnce() {
        AtomicInteger failures = new AtomicInteger();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", null, null);
        RecordingController controller = new RecordingController();
        controller.failInitial = true;
        CommandTargetHudHost host = new CommandTargetHudHost(
                playerRef,
                new CommandHudOpenContext(),
                controller,
                new CommandTargetHudView(snapshot(1), null),
                (phase, failure) -> failures.incrementAndGet()
        );

        host.build(new UICommandBuilder());
        host.build(new UICommandBuilder());

        assertFalse(host.isOpen());
        assertEquals(1, failures.get());
        assertFalse(host.applyUpdate(new CommandTargetHudUpdate(
                new CommandTargetHudView(snapshot(2), null),
                new CommandTargetHudView(snapshot(1), null),
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet.full())));
    }

    @Test
    void hideNowClearsFixedHudWithoutTargetingRemovedElements() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        CommandTargetHudHost host = new CommandTargetHudHost(
                playerRef,
                new CommandHudOpenContext(),
                new RecordingController(),
                new CommandTargetHudView(snapshot(1), null)
        );

        host.hideNow();

        CustomHud packet = assertInstanceOf(CustomHud.class, packets.lastPacket);
        assertEquals(CommandTargetHudHost.HUD_KEY, packet.hudId);
        assertTrue(packet.clear);
        assertTrue(packet.commands == null || packet.commands.length == 0);
    }

    private static CommandTargetHudSnapshot snapshot(int health) {
        return new CommandTargetHudSnapshot(
                UUID.randomUUID(), "target-key", "Moss", null, null, null, "loaded",
                new CommandTargetHudSnapshot.Vitals(
                        health, 100, null, null, null, null, null, null, null),
                null, null, null, null, null, null, null, null);
    }

    private static final class RecordingController implements CommandTargetHudController {
        private final AtomicInteger initialBuilds = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private boolean failInitial;

        @Override
        public void buildInitial(
                CommandHudOpenContext context,
                CommandTargetHudView view,
                UICommandBuilder commands
        ) {
            if (failInitial) {
                throw new IllegalStateException("initial failure");
            }
            initialBuilds.incrementAndGet();
        }

        @Override
        public void update(CommandTargetHudUpdate update, UICommandBuilder commands) {
            updates.incrementAndGet();
        }
    }

    private static final class CapturingPacketHandler extends PacketHandler {
        private ToClientPacket lastPacket;

        private CapturingPacketHandler() {
            super((ChannelConnection) null, new ProtocolVersion(0));
        }

        @Override
        public String getIdentifier() {
            return "target-hud-host-test";
        }

        @Override
        public void accept(ToServerPacket packet) {
        }

        @Override
        public void writeNoCache(ToClientPacket packet) {
            lastPacket = packet;
        }
    }
}
