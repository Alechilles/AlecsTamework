package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies target selection ownership and custom target fallback behavior. */
class CommandTargetHudPresentationCoordinatorTest {
    private static final UUID PLAYER_UUID = UUID.fromString(
            "64fb45b0-142b-4930-8668-c78437a26bb4");
    private static final UUID TARGET_A = UUID.fromString(
            "b8b6a5d2-bab4-4a87-a7d3-88b9cfed2e6d");
    private static final UUID TARGET_B = UUID.fromString(
            "2ef48f27-68c3-47e8-9629-b9e1c9cf2ddf");

    @Test
    void targetTransitionClosesPreviousContributorsBeforeOpeningNextRenderer() {
        List<String> events = new ArrayList<>();
        CommandHudContributorId contributorId = CommandHudContributorId.of(
                "example:target-data");
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerTargetRenderer("example:target", context ->
                new RecordingController(events, "renderer-" + context.targetKey(), false));
        registry.registerTargetContributor(contributorId.value(), context ->
                new RecordingContributor(events, "contributor-" + context.openContext().targetKey(),
                        contributorId));
        CommandTargetHudPresentationCoordinator coordinator = new CommandTargetHudPresentationCoordinator(
                registry, ignored -> { });
        TargetHudClient player = player();
        TwCommandItemConfig config = configWithRendererAndContributor(contributorId);

        coordinator.present(null, player.playerRef(), PLAYER_UUID, player.hudManager(),
                config, "target-a", model(TARGET_A), "example:flute");
        coordinator.present(null, player.playerRef(), PLAYER_UUID, player.hudManager(),
                config, "target-b", model(TARGET_B), "example:flute");

        assertEquals("target-b", coordinator.activeTargetKey(PLAYER_UUID));
        assertTrue(indexOf(events, "contributor-target-a-close")
                < indexOf(events, "renderer-target-b-create"));
        assertTrue(indexOf(events, "renderer-target-a-close")
                < indexOf(events, "renderer-target-b-create"));
        coordinator.closeAll();
    }

    @Test
    void targetLossClosesCustomPresentationAndClearsActiveTarget() {
        AtomicInteger closes = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerTargetRenderer("example:target", ignored ->
                new RecordingController(new ArrayList<>(), "renderer", false, closes));
        CommandTargetHudPresentationCoordinator coordinator = new CommandTargetHudPresentationCoordinator(
                registry, ignored -> { });
        TargetHudClient player = player();
        TwCommandItemConfig config = configWithRendererAndContributor(null);

        coordinator.present(null, player.playerRef(), PLAYER_UUID, player.hudManager(),
                config, "target-a", model(TARGET_A), "example:flute");
        coordinator.hide(PLAYER_UUID, player.hudManager());

        assertNull(coordinator.activeTargetKey(PLAYER_UUID));
        assertEquals(1, closes.get());
        coordinator.closeAll();
    }

    @Test
    void initialBuildFailureFallsBackAndRetriesOnlyAfterTargetActivation() {
        AtomicInteger creates = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerTargetRenderer("example:target", ignored -> {
            creates.incrementAndGet();
            return new RecordingController(new ArrayList<>(), "renderer", true);
        });
        CommandTargetHudPresentationCoordinator coordinator = new CommandTargetHudPresentationCoordinator(
                registry, ignored -> { });
        TargetHudClient player = player();
        TwCommandItemConfig config = configWithRendererAndContributor(null);

        CommandTargetHudPresentation first = coordinator.present(
                null, player.playerRef(), PLAYER_UUID, player.hudManager(), config,
                "target-a", model(TARGET_A), "example:flute");
        assertFalse(first.custom());
        assertInstanceOf(com.alechilles.alecstamework.ui.TameworkCommandTargetHud.class,
                player.hudManager().getCustomHud(
                        com.alechilles.alecstamework.ui.TameworkCommandTargetHud.HUD_KEY));
        assertEquals(1, creates.get());

        coordinator.present(null, player.playerRef(), PLAYER_UUID, player.hudManager(),
                config, "target-a", model(TARGET_A), "example:flute");
        assertEquals(1, creates.get(), "same target must not retry a failed custom build");

        coordinator.present(null, player.playerRef(), PLAYER_UUID, player.hudManager(),
                config, "target-b", model(TARGET_B), "example:flute");
        assertEquals(2, creates.get(), "a new target activation may retry custom presentation");
        coordinator.closeAll();
    }

    private static int indexOf(List<String> events, String value) {
        int index = events.indexOf(value);
        assertTrue(index >= 0, "missing event: " + value + " in " + events);
        return index;
    }

    private static TwCommandItemConfig configWithRendererAndContributor(
            CommandHudContributorId contributorId
    ) {
        String contributor = contributorId == null ? "" :
                ",\"TargetHudContributors\":[{\"Id\":\"" + contributorId.value()
                        + "\",\"Required\":true}]";
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("{\"TargetHudRendererId\":\"example:target\""
                        + contributor + "}"), new ExtraInfo());
    }

    private static CommandTargetHudViewModel model(UUID targetUuid) {
        LinkedNpcEntry status = new LinkedNpcEntry(
                targetUuid, "Moss", 100, 100, 60, 100, null,
                50, 100, 40, 100, true, true, false, false,
                false, false, 0L, null);
        return new CommandTargetHudViewModel(status, null, List.of(), List.of(), null, null);
    }

    private static TargetHudClient player() {
        PlayerRef playerRef = new PlayerRef(
                null, PLAYER_UUID, "TargetHudTester", "en-US",
                new CapturingPacketHandler(), null);
        return new TargetHudClient(playerRef, new HudManager());
    }

    private record TargetHudClient(PlayerRef playerRef, HudManager hudManager) {
    }

    private static final class RecordingController implements CommandTargetHudController {
        private final List<String> events;
        private final String name;
        private final boolean failInitial;
        private final AtomicInteger closeCount;

        private RecordingController(List<String> events, String name, boolean failInitial) {
            this(events, name, failInitial, new AtomicInteger());
        }

        private RecordingController(List<String> events, String name,
                                    boolean failInitial, AtomicInteger closeCount) {
            this.events = events;
            this.name = name;
            this.failInitial = failInitial;
            this.closeCount = closeCount;
            events.add(name + "-create");
        }

        @Override
        public void buildInitial(CommandHudOpenContext context,
                                 CommandTargetHudView view,
                                 UICommandBuilder commands) {
            if (failInitial) {
                throw new IllegalStateException("test renderer failure");
            }
            events.add(name + "-build");
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            events.add(name + "-close");
        }
    }

    private static final class RecordingContributor implements CommandTargetHudSessionContributor {
        private final List<String> events;
        private final String name;
        private final CommandHudContributorId id;

        private RecordingContributor(List<String> events, String name, CommandHudContributorId id) {
            this.events = events;
            this.name = name;
            this.id = id;
            events.add(name + "-create");
        }

        @Override
        public CommandHudContribution compose(CommandTargetHudSnapshot base,
                                               CommandHudContribution previous,
                                               com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope scope) {
            events.add(name + "-compose");
            return CommandHudContribution.available(id, Map.of());
        }

        @Override
        public void close() {
            events.add(name + "-close");
        }
    }

    private static final class CapturingPacketHandler extends PacketHandler {
        private CapturingPacketHandler() {
            super((ChannelConnection) null, new ProtocolVersion(0));
        }

        @Override
        public String getIdentifier() {
            return "target-hud-coordinator-test";
        }

        @Override
        public void accept(ToServerPacket packet) {
        }

        @Override
        public void writeNoCache(ToClientPacket packet) {
        }
    }
}
