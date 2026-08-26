package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies fixed-key hotswap HUD hosting and controller failure isolation. */
class CommandHotswapHudHostTest {
    @Test
    void delegatesInitialAndPartialUpdatesWithFixedKeyAndZOrder() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        RecordingController controller = new RecordingController();
        CommandHotswapHudHost host = new CommandHotswapHudHost(
                playerRef, new CommandHudOpenContext(), controller,
                new CommandHotswapHudView(snapshot(1)));

        host.build(new UICommandBuilder());
        assertTrue(host.isOpen());
        assertEquals(CommandHotswapHudHost.HUD_KEY, host.getKey());
        assertEquals(1, host.getZOrder());
        assertEquals(1, controller.initialBuilds.get());
        assertTrue(host.applyUpdate(new CommandHotswapHudUpdate(
                new CommandHotswapHudView(snapshot(2)),
                new CommandHotswapHudView(snapshot(1)),
                CommandHotswapHudChangeSet.full())));
        assertEquals(1, controller.updates.get());
        CustomHud updatePacket = assertInstanceOf(CustomHud.class, packets.lastPacket);
        assertEquals(CommandHotswapHudHost.HUD_KEY, updatePacket.hudId);
        assertFalse(updatePacket.clear);
    }

    @Test
    void buildFailureClosesHostAndReportsFailureOnce() {
        AtomicInteger failures = new AtomicInteger();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", null, null);
        RecordingController controller = new RecordingController();
        controller.failInitial = true;
        CommandHotswapHudHost host = new CommandHotswapHudHost(
                playerRef, new CommandHudOpenContext(), controller,
                new CommandHotswapHudView(snapshot(1)),
                (phase, failure) -> failures.incrementAndGet());

        host.build(new UICommandBuilder());
        host.build(new UICommandBuilder());

        assertFalse(host.isOpen());
        assertEquals(1, failures.get());
        assertFalse(host.applyUpdate(new CommandHotswapHudUpdate(
                new CommandHotswapHudView(snapshot(2)),
                new CommandHotswapHudView(snapshot(1)),
                CommandHotswapHudChangeSet.full())));
    }

    @Test
    void hideNowClearsFixedHudWithoutTargetingRemovedControls() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        CommandHotswapHudHost host = new CommandHotswapHudHost(
                playerRef, new CommandHudOpenContext(), new RecordingController(),
                new CommandHotswapHudView(snapshot(1)));

        host.hideNow();

        CustomHud packet = assertInstanceOf(CustomHud.class, packets.lastPacket);
        assertEquals(CommandHotswapHudHost.HUD_KEY, packet.hudId);
        assertTrue(packet.clear);
        assertTrue(packet.commands == null || packet.commands.length == 0);
    }

    private static CommandHotswapHudSnapshot snapshot(int q) {
        CommandHotswapHudSnapshot.Slot slot =
                new CommandHotswapHudSnapshot.Slot(true, "Q", "", Integer.toString(q));
        return new CommandHotswapHudSnapshot(
                slot, slot, slot, slot, slot,
                new CommandHotswapHudSnapshot.GroupStatus(true, "All", "#fff"));
    }

    private static final class RecordingController implements CommandHotswapHudController {
        private final AtomicInteger initialBuilds = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private boolean failInitial;

        @Override
        public void buildInitial(
                CommandHudOpenContext context,
                CommandHotswapHudView view,
                UICommandBuilder commands
        ) {
            if (failInitial) throw new IllegalStateException("initial failure");
            initialBuilds.incrementAndGet();
        }

        @Override
        public void update(CommandHotswapHudUpdate update, UICommandBuilder commands) {
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
            return "hotswap-hud-host-test";
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
