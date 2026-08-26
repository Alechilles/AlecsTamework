package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.ui.TameworkCommandHotswapHud;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void missingRequiredContributorUsesStandardHudWithoutRetrying() {
        AtomicInteger rendererCreates = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored -> {
            rendererCreates.incrementAndGet();
            return new CapturingController(null, null);
        });
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("MissingRequired");
        TwCommandItemConfig config = configWithContributors(
                new ContributorSetting("example:missing", true));
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        CommandHotswapHudPresentation first = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool, model("one"));
        CommandHotswapHudPresentation second = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool, model("two"));

        assertFalse(first.custom());
        assertSame(first, second);
        assertEquals(0, rendererCreates.get(),
                "a missing required contributor must prevent custom renderer creation");
        assertInstanceOf(TameworkCommandHotswapHud.class,
                client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void failedRequiredContributorUsesStandardHudWithoutRetrying() {
        AtomicInteger contributorCreates = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new CapturingController(null, null));
        CommandHudContributorId contributorId = CommandHudContributorId.of(
                "example:required");
        registry.registerHotswapContributor(contributorId.value(), ignored -> {
            contributorCreates.incrementAndGet();
            return (base, previous, scope) -> {
                throw new IllegalStateException("required contributor failed");
            };
        });
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("FailedRequired");
        TwCommandItemConfig config = configWithContributors(
                new ContributorSetting(contributorId.value(), true));
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        CommandHotswapHudPresentation first = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool, model("one"));
        CommandHotswapHudPresentation second = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool, model("two"));

        assertFalse(first.custom());
        assertSame(first, second);
        assertEquals(1, contributorCreates.get(),
                "a failed required contributor must not retry for the same tool");
        assertInstanceOf(TameworkCommandHotswapHud.class,
                client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void missingOptionalContributorKeepsCustomHudAndPublishesUnavailableStatus() {
        AtomicReference<CommandHotswapHudView> initial = new AtomicReference<>();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new CapturingController(initial, null));
        CommandHudContributorId contributorId = CommandHudContributorId.of(
                "example:optional-missing");
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("OptionalMissing");
        TwCommandItemConfig config = configWithContributors(
                new ContributorSetting(contributorId.value(), false));

        CommandHotswapHudPresentation presentation = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool("example:flute"), model("one"));

        assertTrue(presentation.custom());
        assertEquals(CommandHudContributionStatus.UNAVAILABLE,
                initial.get().contribution(contributorId).status());
        coordinator.closeAll();
    }

    @Test
    void failedOptionalContributorKeepsCustomHudAndPublishesFailedStatus() {
        AtomicReference<CommandHotswapHudView> initial = new AtomicReference<>();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new CapturingController(initial, null));
        CommandHudContributorId contributorId = CommandHudContributorId.of(
                "example:optional-failed");
        registry.registerHotswapContributor(contributorId.value(), ignored ->
                (base, previous, scope) -> {
                    throw new IllegalStateException("optional contributor failed");
                });
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("OptionalFailed");
        TwCommandItemConfig config = configWithContributors(
                new ContributorSetting(contributorId.value(), false));

        CommandHotswapHudPresentation presentation = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool("example:flute"), model("one"));

        assertTrue(presentation.custom());
        assertEquals(CommandHudContributionStatus.FAILED,
                initial.get().contribution(contributorId).status());
        coordinator.closeAll();
    }

    @Test
    void focusedContributorPathReachesRendererWithoutUnrelatedChanges() {
        AtomicReference<CommandHotswapHudUpdate> update = new AtomicReference<>();
        AtomicInteger focusedComposes = new AtomicInteger();
        AtomicInteger unrelatedComposes = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new CapturingController(null, update));
        CommandHudContributorId focusedId = CommandHudContributorId.of(
                "example:focused");
        CommandHudContributorId unrelatedId = CommandHudContributorId.of(
                "example:unrelated");
        registry.registerHotswapContributor(focusedId.value(), ignored ->
                contributor(focusedId, focusedComposes));
        registry.registerHotswapContributor(unrelatedId.value(), ignored ->
                contributor(unrelatedId, unrelatedComposes));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("FocusedPath");
        TwCommandItemConfig config = configWithContributors(
                new ContributorSetting(focusedId.value(), false),
                new ContributorSetting(unrelatedId.value(), false));
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        CommandHotswapHudPresentation presentation = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config,
                tool, model("one"));
        presentation.session().markPathsDirty(focusedId, Set.of("indicator/value"));
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));

        assertEquals(2, focusedComposes.get());
        assertEquals(1, unrelatedComposes.get());
        assertEquals(Set.of("indicator/value"), update.get().changeSet().pathsFor(focusedId));
        assertTrue(update.get().changeSet().pathsFor(unrelatedId).isEmpty());
        assertTrue(update.get().changeSet().changedSlots().isEmpty());
        assertFalse(update.get().changeSet().groupStatusChanged());
        assertFalse(update.get().changeSet().fullRefresh());
        coordinator.closeAll();
    }

    @Test
    void unequipClosesTheSessionOwnedByTheCurrentStore() throws Exception {
        Store<EntityStore> store = store();
        HudClient client = client("Unequip");
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller"));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });

        coordinator.present(store, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config(), tool("example:flute"), model("one"));
        Player player = allocate(Player.class);
        player.setLegacyUUID(PLAYER_UUID);
        coordinator.hide(store, player);

        assertNull(coordinator.presentation(PLAYER_UUID));
        assertNull(client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void staleStoreRemovalDoesNotCloseTheNewStorePresentation() throws Exception {
        Store<EntityStore> oldStore = store();
        Store<EntityStore> newStore = store();
        HudClient client = client("StoreRemoval");
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller"));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(oldStore, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));
        CommandHotswapHudPresentation current = coordinator.present(
                newStore, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("two"));
        coordinator.closePlayer(oldStore, PLAYER_UUID);

        assertSame(current, coordinator.presentation(PLAYER_UUID));
        assertFalse(current.closed());
        assertNotNull(client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeStore(newStore);
    }

    @Test
    void staleStoreUnequipDoesNotCloseTheNewStorePresentation() throws Exception {
        Store<EntityStore> oldStore = store();
        Store<EntityStore> newStore = store();
        HudClient client = client("StaleUnequip");
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller"));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        CommandHotswapHudPresentation current = coordinator.present(
                newStore, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));
        Player stalePlayer = allocate(Player.class);
        stalePlayer.setLegacyUUID(PLAYER_UUID);
        coordinator.hide(oldStore, stalePlayer);

        assertSame(current, coordinator.presentation(PLAYER_UUID));
        assertFalse(current.closed());
        assertNotNull(client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeStore(newStore);
    }

    @Test
    void worldTransferRemovesTheHudFromItsOldManagerBeforeOpeningTheNewOne() throws Exception {
        Store<EntityStore> oldStore = store();
        Store<EntityStore> newStore = store();
        HudClient oldClient = client("OldWorld");
        HudClient newClient = client("NewWorld");
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller"));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(oldStore, oldClient.playerRef(), PLAYER_UUID,
                oldClient.hudManager(), config, tool, model("one"));
        coordinator.present(newStore, newClient.playerRef(), PLAYER_UUID,
                newClient.hudManager(), config, tool, model("two"));

        assertNull(oldClient.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        assertNotNull(newClient.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void changedPlayerReferenceReplacesTheSameStorePresentation() throws Exception {
        Store<EntityStore> store = store();
        HudClient oldClient = client("OldConnection");
        HudClient newClient = client("NewConnection");
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller"));
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(store, oldClient.playerRef(), PLAYER_UUID,
                oldClient.hudManager(), config, tool, model("one"));
        coordinator.present(store, newClient.playerRef(), PLAYER_UUID,
                newClient.hudManager(), config, tool, model("two"));

        assertNull(oldClient.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        assertNotNull(newClient.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void rendererContextUsesLogicalToolMetadataAndItemIdSeparately() {
        AtomicReference<CommandHudOpenContext> context = new AtomicReference<>();
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerHotswapRenderer("example:hotswap", ignored -> {
            context.set(ignored);
            return new RecordingController(new ArrayList<>(), "controller");
        });
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        ItemStack stack = new MetadataItemStack("example:item", null).withMetadata(
                TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, "example:family");

        HudClient client = client("ToolMetadata");
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config(), CommandHotswapHudToolIdentity.of(stack, (byte) 0), model("one"));

        assertEquals("example:family", context.get().toolId());
        assertEquals("example:item", context.get().itemId());
        coordinator.closeAll();
    }

    @Test
    void rendererUnregisterDuringInitialBuildFallsBackToStandardHud() {
        AtomicReference<CommandHudRegistration> registration = new AtomicReference<>();
        AtomicInteger closes = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registration.set(registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller", false, null, closes,
                        () -> registration.get().close(), null)).registration());
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("InitialUnregister");

        CommandHotswapHudPresentation presentation = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(), config(),
                tool("example:flute"), model("one"));

        assertFalse(presentation.custom());
        assertEquals(1, closes.get());
        assertInstanceOf(TameworkCommandHotswapHud.class,
                client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
        coordinator.closeAll();
    }

    @Test
    void rendererUnregisterDuringUpdateFallsBackOnTheNextTick() {
        AtomicReference<CommandHudRegistration> registration = new AtomicReference<>();
        AtomicInteger updates = new AtomicInteger();
        CommandHudRegistry registry = new CommandHudRegistry();
        registration.set(registry.registerHotswapRenderer("example:hotswap", ignored ->
                new RecordingController(new ArrayList<>(), "controller", false, null,
                        new AtomicInteger(), () -> {
                            updates.incrementAndGet();
                            registration.get().close();
                        })).registration());
        CommandHotswapHudPresentationCoordinator coordinator =
                new CommandHotswapHudPresentationCoordinator(registry, ignored -> { });
        HudClient client = client("UpdateUnregister");
        TwCommandItemConfig config = config();
        CommandHotswapHudToolIdentity tool = tool("example:flute");

        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("one"));
        coordinator.present(null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("two"));
        CommandHotswapHudPresentation fallback = coordinator.present(
                null, client.playerRef(), PLAYER_UUID, client.hudManager(),
                config, tool, model("three"));

        assertEquals(1, updates.get());
        assertFalse(fallback.custom());
        assertInstanceOf(TameworkCommandHotswapHud.class,
                client.hudManager().getCustomHud(TameworkCommandHotswapHud.HUD_KEY));
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

    private static TwCommandItemConfig configWithContributors(
            ContributorSetting... settings
    ) {
        StringBuilder json = new StringBuilder(
                "{\"HotswapHudRendererId\":\"example:hotswap\","
                        + "\"HotswapHudContributors\":[");
        for (int index = 0; index < settings.length; index++) {
            if (index > 0) json.append(',');
            ContributorSetting setting = settings[index];
            json.append("{\"Id\":\"").append(setting.id())
                    .append("\",\"Required\":").append(setting.required()).append('}');
        }
        json.append("]}");
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse(json.toString()), new ExtraInfo());
    }

    private static CommandHotswapHudSessionContributor contributor(
            CommandHudContributorId id,
            AtomicInteger composeCount
    ) {
        return (base, previous, scope) -> {
            composeCount.incrementAndGet();
            return CommandHudContribution.available(id,
                    Map.of("indicator/value", CommandUiValue.of("ready")));
        };
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
        return client("HotswapTester");
    }

    private static HudClient client(String name) {
        CapturingPacketHandler packets = new CapturingPacketHandler();
        PlayerRef playerRef = new PlayerRef(
                null, PLAYER_UUID, name, "en-US", packets, null);
        return new HudClient(playerRef, new HudManager());
    }

    @SuppressWarnings("unchecked")
    private static Store<EntityStore> store() throws Exception {
        return (Store<EntityStore>) unsafe().allocateInstance(Store.class);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(unsafe().allocateInstance(type));
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private record HudClient(PlayerRef playerRef, HudManager hudManager) {
    }

    private record ContributorSetting(String id, boolean required) {
    }

    private static final class RecordingController implements CommandHotswapHudController {
        private final List<String> events;
        private final String name;
        private final boolean failUpdate;
        private final AtomicReference<CommandHotswapHudUpdate> update;
        private final AtomicInteger closes;
        private final Runnable initialAction;
        private final Runnable updateAction;

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
            this(events, name, failUpdate, update, closes, null, null);
        }

        private RecordingController(List<String> events, String name, boolean failUpdate,
                                    AtomicReference<CommandHotswapHudUpdate> update,
                                    AtomicInteger closes, Runnable updateAction) {
            this(events, name, failUpdate, update, closes, null, updateAction);
        }

        private RecordingController(List<String> events, String name, boolean failUpdate,
                                    AtomicReference<CommandHotswapHudUpdate> update,
                                    AtomicInteger closes, Runnable initialAction,
                                    Runnable updateAction) {
            this.events = events;
            this.name = name;
            this.failUpdate = failUpdate;
            this.update = update;
            this.closes = closes;
            this.initialAction = initialAction;
            this.updateAction = updateAction;
            events.add(name + "-create");
        }

        @Override
        public void buildInitial(CommandHudOpenContext context,
                                 CommandHotswapHudView view,
                                 UICommandBuilder commands) {
            events.add(name + "-build");
            if (initialAction != null) initialAction.run();
        }

        @Override
        public void update(CommandHotswapHudUpdate update,
                           UICommandBuilder commands) {
            if (failUpdate) throw new IllegalStateException("update failure");
            if (this.update != null) this.update.set(update);
            if (updateAction != null) updateAction.run();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            events.add(name + "-close");
        }
    }

    private static final class CapturingController implements CommandHotswapHudController {
        private final AtomicReference<CommandHotswapHudView> initial;
        private final AtomicReference<CommandHotswapHudUpdate> update;

        private CapturingController(AtomicReference<CommandHotswapHudView> initial,
                                     AtomicReference<CommandHotswapHudUpdate> update) {
            this.initial = initial;
            this.update = update;
        }

        @Override
        public void buildInitial(CommandHudOpenContext context,
                                 CommandHotswapHudView view,
                                 UICommandBuilder commands) {
            if (initial != null) initial.set(view);
        }

        @Override
        public void update(CommandHotswapHudUpdate value, UICommandBuilder commands) {
            if (update != null) update.set(value);
        }
    }

    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) next.remove(key);
            else next.put(key, codec.encode(value));
            return new MetadataItemStack(itemId, next.isEmpty() ? null : next);
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
