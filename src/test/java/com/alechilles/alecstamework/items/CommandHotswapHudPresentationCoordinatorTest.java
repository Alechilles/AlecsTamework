package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.TameworkCommandHotswapHud;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies active-stack ownership, focused updates, and custom fallback. */
class CommandHotswapHudPresentationCoordinatorTest {
    private static final UUID PLAYER_UUID = UUID.fromString(
            "64fb45b0-142b-4930-8668-c78437a26bb4");

    @Test
    void switchingExactStacksClosesTheOldSessionBeforeOpeningTheNewOne() {
        List<String> events = new ArrayList<>();
        AtomicInteger creates = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        CommandHudRegistration registration = registry.registerHotswapRenderer(
                "example:hotswap", context -> {
                    int number = creates.incrementAndGet();
                    events.add("create-" + number);
                    return new RecordingController(events, "controller-" + number);
                }).registration();
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client();
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity first = tool("example:flute");
        CommandHotswapHudToolIdentity second = tool("example:flute");

        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, first, model("one"));
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, second, model("two"));

        assertEquals(2, creates.get());
        assertTrue(indexOf(events, "controller-1-close")
                < indexOf(events, "create-2"));
        assertTrue(coordinator.presentation(PLAYER_UUID).custom());
        coordinator.closeAll();
        registration.close();
    }

    @Test
    void modelOnlyChangeDeliversOnlyTheChangedControl() {
        AtomicReference<CommandHotswapHudUpdate> update = new AtomicReference<>();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller", false, update));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client();
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("two"));

        assertTrue(update.get().changeSet().changedSlots().contains(
                CommandHotswapHudChangeSet.Slot.Q));
        assertEquals(1, update.get().changeSet().changedSlots().size());
        assertFalse(update.get().changeSet().groupStatusChanged());
        coordinator.closeAll();
    }

    @Test
    void rendererUnregisterUsesStandardFallbackForTheCurrentTool() {
        AtomicInteger closes = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        CommandHudRegistration registration = registry.registerHotswapRenderer(
                "example:hotswap", ignored -> new RecordingController(
                        new ArrayList<>(), "controller", false, null, closes)).registration();
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client();
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));
        registration.close();
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));

        assertEquals(1, closes.get());
        assertFalse(coordinator.presentation(PLAYER_UUID).custom());
        assertInstanceOf(TameworkCommandHotswapHud.class,
                client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void playerRemovalClosesCustomSessionAndClearsPresentation() {
        AtomicInteger closes = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller", false, null, closes));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client();
        TwCommandItemConfig config = config();

        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool("example:flute"), model("one"));
        coordinator.closePlayer(PLAYER_UUID);

        assertNull(coordinator.presentation(PLAYER_UUID));
        assertEquals(1, closes.get());
    }

    @Test
    void rendererUpdateFailureFallsBackWithoutRetryLoop() {
        AtomicInteger creates = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored -> {
            creates.incrementAndGet();
            return new RecordingController(new ArrayList<>(), "controller", true, null);
        });
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client();
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("two"));
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("three"));

        assertEquals(1, creates.get());
        assertFalse(coordinator.presentation(PLAYER_UUID).custom());
        coordinator.closeAll();
    }

    private static int indexOf(List<String> events, String event) {
        int index = events.indexOf(event);
        assertTrue(index >= 0, "missing event " + event + " in " + events);
        return index;
    }

    private static CommandHotswapHudToolIdentity tool(String itemId) {
        return CommandHotswapHudToolIdentity.of(new TestItemStack(itemId), (byte) 0);
    }

    private static TwCommandItemConfig config() {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("{\"HotswapHudRendererId\":\"example:hotswap\"}"),
                new ExtraInfo());
    }

    private static CommandHotswapHudViewModel model(String qGlyph) {
        return new CommandHotswapHudViewModel(
                new CommandHotswapHudViewModel.Slot(true, "LMB", "", "P"),
                new CommandHotswapHudViewModel.Slot(true, "RMB", "", "S"),
                new CommandHotswapHudViewModel.Slot(true, "Q", "", qGlyph),
                new CommandHotswapHudViewModel.Slot(true, "E", "", "E"),
                new CommandHotswapHudViewModel.Slot(true, "R", "", "R"),
                new CommandHotswapHudViewModel.GroupStatus(true, "All", "#ffffff"));
    }

    private static HudClient client() {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, PLAYER_UUID, "HotswapTester", "en-US", packets, null);
        return new HudClient(playerRef, new HudManager());
    }

    private record HudClient(PlayerRef playerRef, HudManager hudManager) {
    }

    private static final class RecordingController implements CommandHotswapHudController {
        private final List<String> events;
        private final String name;
        private final boolean failUpdate;
        private final AtomicReference<CommandHotswapHudUpdate> update;
        private final AtomicInteger closes;

        private RecordingController(List<String> events, String name) {
            this(events, name, false, null, new AtomicInteger());
        }

        private RecordingController(List<String> events, String name, boolean failUpdate,
                                    AtomicReference<CommandHotswapHudUpdate> update) {
            this(events, name, failUpdate, update, new AtomicInteger());
        }

        private RecordingController(List<String> events, String name, boolean failUpdate,
                                    AtomicReference<CommandHotswapHudUpdate> update,
                                    AtomicInteger closes) {
            this.events = events;
            this.name = name;
            this.failUpdate = failUpdate;
            this.update = update;
            this.closes = closes;
            events.add(name + "-create");
        }

        @Override
        public void buildInitial(CommandHudOpenContext context,
                                 CommandHotswapHudView view,
                                 UICommandBuilder commands) {
            events.add(name + "-build");
        }

        @Override
        public void update(CommandHotswapHudUpdate update,
                           UICommandBuilder commands) {
            if (failUpdate) throw new IllegalStateException("update failure");
            if (this.update != null) this.update.set(update);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            events.add(name + "-close");
        }
    }

    private static final class CapturingPacketHandler extends PacketHandler {
        private CapturingPacketHandler() {
            super((ChannelConnection) null, new ProtocolVersion(0));
        }

        @Override
        public String getIdentifier() {
            return "hotswap-hud-coordinator-test";
        }

        @Override
        public void accept(ToServerPacket packet) {
        }

        @Override
        public void writeNoCache(ToClientPacket packet) {
        }
    }

    /** Asset-store-free stack used to exercise exact object identity. */
    private static final class TestItemStack extends ItemStack {
        private TestItemStack(String itemId) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
        }
    }
}
