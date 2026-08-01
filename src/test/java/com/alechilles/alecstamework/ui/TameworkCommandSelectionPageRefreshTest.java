package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable page refresh coverage through the package-scoped packet boundary. */
class TameworkCommandSelectionPageRefreshTest {
    private static final UUID OWNER = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID CARD = UUID.fromString("a2000000-0000-0000-0000-000000000001");
    private static final LinkedNpcEntry ENTRY = new LinkedNpcEntry(CARD, "Nimbus", 10, 10, 0, 0, null, 0, 0, 0, 0, true, true, false, false, false, false, 0L, new LinkedNpcTraitIndicator[0]);

    @Test
    void initialBuildSeedsDedupAndUnchangedSafetyRefreshSendsNothing() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<CommandPanelFeaturePresentation> feature = new AtomicReference<>(feature(4, false));
        TameworkCommandSelectionPage page = page(packets, feature);
        build(page); refresh(page, true);
        assertEquals(0, packets.updates.size(), () -> java.util.Arrays.stream(
                packets.updates.getFirst().commands.getCommands()).map(command -> command.selector)
                .toList().toString());
    }

    @Test
    void failedSendDoesNotCommitProgressionDeltaAndRetryResendsIt() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<CommandPanelFeaturePresentation> feature = new AtomicReference<>(feature(4, false));
        TameworkCommandSelectionPage page = page(packets, feature);
        build(page); feature.set(feature(5, false)); packets.fail = true;
        try { refresh(page, true); } catch (RuntimeException expected) { }
        packets.fail = false; refresh(page, true);
        assertEquals(2, packets.attempts);
        assertEquals(1, packets.updates.size());
        assertCommand(packets.updates.getFirst(), "#TameworkLinkedPanelList[0] #BondedLevelText.Text");
    }

    @Test
    void dynamicProgressionWritesProgressionSelectorsWithoutStableEvents() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<CommandPanelFeaturePresentation> feature = new AtomicReference<>(feature(4, false));
        TameworkCommandSelectionPage page = page(packets, feature);
        build(page); feature.set(feature(5, false)); refresh(page, true);
        CapturedUpdate update = packets.updates.getFirst();
        assertCommand(update, "#TameworkLinkedPanelList[0] #BondedLevelText.Text");
        assertEquals(0, update.events.getEvents().length);
    }

    @Test
    void flightAvailabilityChangeUsesFullCardBindingWithFlightEvent() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<CommandPanelFeaturePresentation> feature = new AtomicReference<>(feature(4, false));
        TameworkCommandSelectionPage page = page(packets, feature);
        build(page); feature.set(feature(4, true)); refresh(page, true);
        assertTrue(packets.updates.getFirst().events.getEvents().length > 0);
    }

    @Test
    void dismissClosesLifecycleAndFencesStaleRefresh() throws Exception {
        CapturedPackets packets = new CapturedPackets(); NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture, genericConfig());
        build(page); page.onDismiss(null, null); refresh(page, true);
        assertEquals(1, fixture.source.closes); assertEquals(0, packets.updates.size());
    }

    @Test
    void talentReplacementClosesLifecycleAndRunsDeferredAction() throws Exception {
        CapturedPackets packets = new CapturedPackets(); NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture);
        build(page); event(page, "__talents__:" + CARD);
        assertEquals(1, fixture.source.closes); assertEquals(0, fixture.talents); fixture.run(); assertEquals(1, fixture.talents);
    }

    @Test
    void groupReplacementClosesLifecycleAndRunsDeferredAction() throws Exception {
        CapturedPackets packets = new CapturedPackets(); NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture, genericConfig());
        build(page); event(page, "__panel_manage_groups__");
        assertEquals(1, fixture.source.closes); assertEquals(0, fixture.groups); fixture.run(); assertEquals(1, fixture.groups);
    }

    @Test
    void acceptedRefreshWorkAfterNavigationCannotSend() throws Exception {
        CapturedPackets packets = new CapturedPackets(); NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture);
        build(page); event(page, "__talents__:" + CARD); acceptedRefresh(page, 17L);
        assertEquals(0, packets.updates.size());
    }

    @Test
    void acceptedRefreshFromSupersededPageCannotSend() throws Exception {
        CapturedPackets oldPackets = new CapturedPackets(); CapturedPackets currentPackets = new CapturedPackets();
        TameworkCommandSelectionPage oldPage = page(oldPackets, new AtomicReference<>(feature(4, false)));
        build(oldPage);
        TameworkCommandSelectionPage currentPage = page(currentPackets, new AtomicReference<>(feature(4, false)));
        build(currentPage); acceptedRefresh(oldPage, 18L);
        assertEquals(0, oldPackets.updates.size()); assertEquals(0, currentPackets.updates.size());
    }

    @Test
    void closeCommandClosesActualPageLifecycle() throws Exception {
        CapturedPackets packets = new CapturedPackets(); NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture);
        build(page); event(page, "__close__");
        assertEquals(1, fixture.source.closes);
    }

    private static void build(TameworkCommandSelectionPage page) { page.build(null, new UICommandBuilder(), new UIEventBuilder(), null); }
    private static void refresh(TameworkCommandSelectionPage page, boolean eligible) throws Exception {
        invoke(page, "refreshLinkedNpcEntries");
        Method method = TameworkCommandSelectionPage.class.getDeclaredMethod("sendCardRefreshUpdate", boolean.class);
        method.setAccessible(true);
        try { method.invoke(page, eligible); } catch (java.lang.reflect.InvocationTargetException exception) {
            throw (RuntimeException) exception.getCause();
        }
    }
    private static void invoke(TameworkCommandSelectionPage page, String name) throws Exception {
        Method method = TameworkCommandSelectionPage.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(page);
    }
    private static void acceptedRefresh(TameworkCommandSelectionPage page, long id) throws Exception {
        Method method = TameworkCommandSelectionPage.class.getDeclaredMethod("runRefreshOnWorldThread", LinkedPanelRefreshCoordinator.RenderPermit.class);
        method.setAccessible(true); method.invoke(page, new LinkedPanelRefreshCoordinator.RenderPermit(id, true));
    }
    private static TameworkCommandSelectionPage page(CapturedPackets packets, AtomicReference<CommandPanelFeaturePresentation> feature) throws Exception {
        return page(packets, feature, new NavigationFixture());
    }
    private static TameworkCommandSelectionPage page(CapturedPackets packets, AtomicReference<CommandPanelFeaturePresentation> feature, NavigationFixture fixture) throws Exception {
        return page(packets, feature, fixture, config());
    }
    private static TameworkCommandSelectionPage page(CapturedPackets packets, AtomicReference<CommandPanelFeaturePresentation> feature, NavigationFixture fixture, TwCommandItemConfig commandConfig) throws Exception {
        try (AutoCloseable ignored = LinkedNpcPanelRefreshTestSeam.installPacketSender(packets::capture);
             AutoCloseable ignoredNavigator = LinkedNpcPanelRefreshTestSeam.installDeferredNavigator(fixture::defer)) {
            PlayerRef player = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
            put(player, "uuid", OWNER); put(player, "username", "PageRefreshTester"); put(player, "language", "en-US");
            Consumer<UUID> noUuid = value -> { }; Consumer<String> noString = value -> { }; BiConsumer<UUID, String> noGroup = (a, b) -> { };
            return new TameworkCommandSelectionPage(player, commandConfig, null, true,
                    () -> List.of(ENTRY), () -> List.of(ENTRY), () -> Map.of(CARD, feature.get()), () -> null,
                    () -> "LinkedMode", () -> false, () -> "16", () -> "Default", () -> "None", () -> "", List::of, () -> "", List::of, value -> true, true,
                    noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, noUuid, noUuid, noUuid, noUuid, fixture::talent, noString, value->{}, ()->{}, ()->{}, fixture::groups, noString, noString, noString, ()->{}, noString, noGroup, noString, fixture.source);
        }
    }
    private static void event(TameworkCommandSelectionPage page, String command) throws Exception {
        CommandSelectionEventData data = new CommandSelectionEventData(); Field field = CommandSelectionEventData.class.getDeclaredField("commandId"); field.setAccessible(true); unsafe().putObject(data, unsafe().objectFieldOffset(field), command); page.handleDataEvent(null, null, data);
    }
    private static TwCommandItemConfig config() { return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{\"RosterStorage\":\"BondedCompanions\",\"BondedRosterId\":\"test:roster\",\"CommandList\":[]}"), new com.hypixel.hytale.codec.ExtraInfo()); }
    private static TwCommandItemConfig genericConfig() { return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{\"RosterStorage\":\"OwnerCommandFamily\",\"CommandFamilyId\":\"test:family\",\"CommandList\":[]}"), new com.hypixel.hytale.codec.ExtraInfo()); }
    private static CommandPanelFeaturePresentation feature(int level, boolean available) { return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation("profile", "test:roster", "role", 1L, "Nimbus", "Nimbus", "Female", null, Map.of("level", Integer.toString(level), "currentXp", "12", "levelingConfigId", "levels", "talentConfigId", "talents", "talentSpentPoints", "1", "bonded.flightToggle.available", Boolean.toString(available)), Map.of(), new BondedCompanionStatusPresentation(BondedCompanionStateView.ACTIVE, BondedCompanionStatusPresentation.Action.DISMISS, true, null, 0L), null)); }
    private static void assertCommand(CapturedUpdate update, String selector) { assertTrue(java.util.Arrays.stream(update.commands.getCommands()).anyMatch(command -> selector.equals(command.selector))); }
    private static void put(Object target, String name, Object value) throws Exception { Field field = PlayerRef.class.getDeclaredField(name); field.setAccessible(true); unsafe().putObject(target, unsafe().objectFieldOffset(field), value); }
    private static Unsafe unsafe() throws Exception { Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); return (Unsafe) field.get(null); }
    private static final class CapturedPackets { private final List<CapturedUpdate> updates = new ArrayList<>(); private boolean fail; private int attempts; private void capture(UICommandBuilder commands, UIEventBuilder events) { attempts++; if (fail) throw new IllegalStateException("synthetic send failure"); updates.add(new CapturedUpdate(commands, events)); } }
    private static final class NavigationFixture { private final SignalSource source = new SignalSource(); private Runnable deferred; private int talents; private int groups; private void defer(PlayerRef player, Runnable action) { deferred = action; } private void talent(UUID ignored) { talents++; } private void groups() { groups++; } private void run() { deferred.run(); } }
    private static final class SignalSource implements LinkedPanelRefreshSignalSource { private int closes; @Override public AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener) { return () -> closes++; } }
    private record CapturedUpdate(UICommandBuilder commands, UIEventBuilder events) { }
}
