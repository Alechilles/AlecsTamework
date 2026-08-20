package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.CommandHotswapHudViewModel;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the command hotswap HUD packet lifecycle. */
class TameworkCommandHotswapHudTest {
    @Test
    void hideNowClearsHudWithoutTargetingRemovedElements() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HudTester", "en-US", packets, null);
        TameworkCommandHotswapHud hud = new TameworkCommandHotswapHud(
                playerRef, hiddenModel());

        hud.hideNow();

        CustomHud packet = assertInstanceOf(CustomHud.class, packets.lastPacket);
        assertEquals(TameworkCommandHotswapHud.HUD_KEY, packet.hudId);
        assertTrue(packet.clear);
        assertTrue(packet.commands == null || packet.commands.length == 0,
                "A cleared HUD cannot target elements from its removed UI tree");
    }

    private static CommandHotswapHudViewModel hiddenModel() {
        return new CommandHotswapHudViewModel(
                CommandHotswapHudViewModel.Slot.hidden("LMB"),
                CommandHotswapHudViewModel.Slot.hidden("RMB"),
                CommandHotswapHudViewModel.Slot.hidden("Q"),
                CommandHotswapHudViewModel.Slot.hidden("E"),
                CommandHotswapHudViewModel.Slot.hidden("R"),
                CommandHotswapHudViewModel.GroupStatus.hidden());
    }

    private static final class CapturingPacketHandler extends PacketHandler {
        private ToClientPacket lastPacket;

        private CapturingPacketHandler() {
            super((ChannelConnection) null, new ProtocolVersion(0));
        }

        @Override
        public String getIdentifier() {
            return "hotswap-hud-test";
        }

        @Override
        public void accept(ToServerPacket packet) {
        }

        @Override
        public void writeNoCache(ToClientPacket packet) {
            assertNotNull(packet);
            lastPacket = packet;
        }
    }
}
