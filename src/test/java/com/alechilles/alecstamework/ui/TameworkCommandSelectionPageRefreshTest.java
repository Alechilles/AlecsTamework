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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    void genericActivationSupplierDoesNotDirtyAnUnchangedSafetyRefresh() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)),
                new NavigationFixture(), genericConfig(), genericActivationEntries());
        build(page); refresh(page, true);
        assertEquals(0, packets.updates.size());
    }

    @Test
    void genericPagesKeepTheirOwnStableActivationEntries() throws Exception {
        Object service = genericActivationService();
        CapturedPackets englishPackets = new CapturedPackets();
        CapturedPackets otherPackets = new CapturedPackets();
        AtomicInteger englishReads = new AtomicInteger();
        AtomicInteger otherReads = new AtomicInteger();
        TameworkCommandSelectionPage englishPage = page(englishPackets,
                new AtomicReference<>(feature(4, false)), new NavigationFixture(), genericConfig(),
                OWNER, counted(englishReads, genericActivationEntries(service, "en-US")));
        TameworkCommandSelectionPage otherPage = page(otherPackets,
                new AtomicReference<>(feature(4, false)), new NavigationFixture(), genericConfig(),
                UUID.fromString("a1000000-0000-0000-0000-000000000002"),
                counted(otherReads, genericActivationEntries(service, "de-DE")));
        build(englishPage); build(otherPage);
        int englishReadsBeforeRefresh = englishReads.get();
        int otherReadsBeforeRefresh = otherReads.get();
        refresh(englishPage, true); refresh(otherPage, true);
        assertTrue(englishReads.get() > englishReadsBeforeRefresh);
        assertTrue(otherReads.get() > otherReadsBeforeRefresh);
        assertEquals(0, englishPackets.updates.size());
        assertEquals(0, otherPackets.updates.size());
    }

    @Test
    void changedActivationEntriesEmitAnUpdatedSelector() throws Exception {
        AtomicReference<String> entryValue = new AtomicReference<>("one");
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)),
                new NavigationFixture(), genericConfig(), () -> List.of(
                        new com.hypixel.hytale.server.core.ui.DropdownEntryInfo(
                                com.hypixel.hytale.server.core.ui.LocalizableString.fromString(entryValue.get()),
                                entryValue.get())));
        build(page); refresh(page, true);
        assertEquals(0, packets.updates.size());
        entryValue.set("two"); refresh(page, true);
        assertCommand(packets.updates.getFirst(), "#TameworkLinkedPanelGroupSelectorDropdown.Entries");
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
    void dynamicFlightRefreshRebindsTheToggleForTheNextClick() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<CommandPanelFeaturePresentation> feature =
                new AtomicReference<>(feature(4, true, false));
        TameworkCommandSelectionPage page = page(packets, feature);

        build(page);
        feature.set(feature(4, true, true));
        refresh(page, true);

        CapturedUpdate update = packets.updates.getFirst();
        assertCommand(update,
                "#TameworkLinkedPanelList[0] #BondedFlightModeAirborneIcon.Visible");
        assertTrue(java.util.Arrays.stream(update.events.getEvents()).anyMatch(event ->
                        event.type == com.hypixel.hytale.protocol.packets.interface_
                                .CustomUIEventBindingType.Activating
                                && ("#TameworkLinkedPanelList[0] "
                                + "#BondedFlightToggleButton").equals(event.selector)
                                && event.data.contains("__bonded_flight_toggle__:" + CARD)),
                "A dynamic visual refresh must preserve the next flight-toggle click.");
    }

    @Test
    void flightToggleRetriesRefreshAfterTheNpcHookHasTimeToSettle() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets,
                new AtomicReference<>(feature(4, true)));
        invoke(page, "refreshLinkedNpcEntries");
        RecordingScheduler scheduler = new RecordingScheduler();
        AtomicLong clock = new AtomicLong();
        AtomicInteger refreshes = new AtomicInteger();
        LinkedPanelRefreshCoordinator coordinator = new LinkedPanelRefreshCoordinator(
                clock::get, scheduler, ignored -> refreshes.incrementAndGet());
        coordinator.seedInitialRender(true,
                LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
        replaceRefreshLifecycle(page,
                new LinkedNpcPanelRefreshLifecycle(LinkedPanelRefreshSignalSource.none(),
                        coordinator));

        clock.set(10_000L);
        event(page, "__bonded_flight_toggle__:" + CARD);
        clock.set(10_250L);
        event(page, "__bonded_flight_toggle__:" + CARD);

        assertEquals(List.of(500L, 1_500L, 500L, 1_500L), scheduler.delays);
        scheduler.runAll();
        assertEquals(2, refreshes.get());
    }

    @Test
    void legacyReviveRetriesRefreshAfterTheNpcHookHasTimeToSettle() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets,
                new AtomicReference<>(feature(4, false)), new NavigationFixture(),
                genericConfig());
        invoke(page, "refreshLinkedNpcEntries");
        AtomicReference<UUID> revived = new AtomicReference<>();
        replaceField(page, "respawnCallback", (Consumer<UUID>) revived::set);
        RecordingScheduler scheduler = new RecordingScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        replaceRefreshLifecycle(page,
                new LinkedNpcPanelRefreshLifecycle(LinkedPanelRefreshSignalSource.none(),
                        new LinkedPanelRefreshCoordinator(() -> 10_000L, scheduler,
                                ignored -> refreshes.incrementAndGet())));

        event(page, "__respawn__:" + CARD);

        assertEquals(CARD, revived.get());
        assertEquals(List.of(500L, 1_500L), scheduler.delays);
        scheduler.runAll();
        assertEquals(2, refreshes.get());
    }

    @Test
    void legacyRecallCountdownRefreshDoesNotRebindCardEvents() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<List<LinkedNpcEntry>> entries =
                new AtomicReference<>(List.of(recallEntry(3_500L)));
        TameworkCommandSelectionPage page = page(packets,
                new AtomicReference<>(),
                new NavigationFixture(), legacyConfig());
        replaceField(page, "linkedNpcBaseEntriesSupplier",
                (Supplier<List<LinkedNpcEntry>>) entries::get);
        build(page);

        entries.set(List.of(recallEntry(2_500L)));
        refresh(page, false);

        CapturedUpdate update = packets.updates.getFirst();
        assertCommand(update, "#TameworkLinkedPanelList[0] #RecallCountdown.Text");
        assertEquals(0, update.events.getEvents().length);
        assertEquals(1, update.commands.getCommands().length);
    }

    @Test
    void dynamicTalentRefreshKeepsUnlinkConfirmationHidden() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<List<LinkedNpcEntry>> entries =
                new AtomicReference<>(List.of(talentEntry(1)));
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(),
                new NavigationFixture(), legacyConfig());
        replaceField(page, "linkedNpcBaseEntriesSupplier",
                (Supplier<List<LinkedNpcEntry>>) entries::get);
        replaceField(page, "pendingUnlinkNpcUuid", CARD);
        build(page);

        entries.set(List.of(talentEntry(2)));
        refresh(page, false);

        CapturedUpdate update = packets.updates.getFirst();
        assertCommand(update, "#TameworkLinkedPanelList[0] #TalentPointAction.Visible",
                "false");
        assertEquals(0, update.events.getEvents().length);
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
        return page(packets, feature, fixture, commandConfig, List::of);
    }
    private static TameworkCommandSelectionPage page(CapturedPackets packets, AtomicReference<CommandPanelFeaturePresentation> feature, NavigationFixture fixture, TwCommandItemConfig commandConfig, Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> activationEntries) throws Exception {
        return page(packets, feature, fixture, commandConfig, OWNER, activationEntries);
    }
    private static TameworkCommandSelectionPage page(CapturedPackets packets, AtomicReference<CommandPanelFeaturePresentation> feature, NavigationFixture fixture, TwCommandItemConfig commandConfig, UUID owner, Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> activationEntries) throws Exception {
        try (AutoCloseable ignored = LinkedNpcPanelRefreshTestSeam.installPacketSender(packets::capture);
             AutoCloseable ignoredNavigator = LinkedNpcPanelRefreshTestSeam.installDeferredNavigator(fixture::defer)) {
            PlayerRef player = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
            put(player, "uuid", owner); put(player, "username", "PageRefreshTester"); put(player, "language", "en-US");
            Consumer<UUID> noUuid = value -> { }; Consumer<String> noString = value -> { }; BiConsumer<UUID, String> noGroup = (a, b) -> { };
            return new TameworkCommandSelectionPage(player, commandConfig, null, true,
                    () -> List.of(ENTRY), () -> List.of(ENTRY), () -> feature.get() == null
                            ? Map.of() : Map.of(CARD, feature.get()), () -> null,
                    () -> "LinkedMode", () -> false, () -> "16", () -> "Default", () -> "None", () -> "", activationEntries, () -> "", List::of, value -> true, true,
                    noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, noUuid, noUuid, noUuid, noUuid, fixture::talent, noString, value->{}, ()->{}, ()->{}, fixture::groups, noString, noString, noString, ()->{}, noString, noGroup, noString, fixture.source);
        }
    }
    @SuppressWarnings("unchecked")
    private static Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> genericActivationEntries() throws Exception {
        return genericActivationEntries(genericActivationService(), "en-US");
    }
    private static Object genericActivationService() throws Exception {
        Class<?> serviceType = Class.forName("com.alechilles.alecstamework.items.CommandGroupActivationService");
        var constructor = serviceType.getDeclaredConstructor(
                Class.forName("com.alechilles.alecstamework.items.CommandLinkedNpcRecordStore"),
                Class.forName("com.alechilles.alecstamework.items.CommandGroupService"));
        constructor.setAccessible(true);
        return constructor.newInstance(null, null);
    }
    @SuppressWarnings("unchecked")
    private static Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> genericActivationEntries(Object service, String language) throws Exception {
        Class<?> serviceType = service.getClass();
        Method resolver = serviceType.getDeclaredMethod("resolveDropdownEntries",
                com.hypixel.hytale.server.core.inventory.ItemStack.class, String.class);
        resolver.setAccessible(true);
        return () -> {
            try {
                return (List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>) resolver.invoke(service, null, language);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        };
    }
    private static Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> counted(
            AtomicInteger reads, Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> supplier) {
        return () -> {
            reads.incrementAndGet();
            return supplier.get();
        };
    }
    private static void event(TameworkCommandSelectionPage page, String command) throws Exception {
        CommandSelectionEventData data = new CommandSelectionEventData(); Field field = CommandSelectionEventData.class.getDeclaredField("commandId"); field.setAccessible(true); unsafe().putObject(data, unsafe().objectFieldOffset(field), command); page.handleDataEvent(null, null, data);
    }
    private static void replaceRefreshLifecycle(TameworkCommandSelectionPage page,
                                                LinkedNpcPanelRefreshLifecycle lifecycle)
            throws Exception {
        replaceField(page, "refreshLifecycle", lifecycle);
    }
    private static void replaceField(TameworkCommandSelectionPage page, String name, Object value)
            throws Exception {
        Field field = TameworkCommandSelectionPage.class.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(page, unsafe().objectFieldOffset(field), value);
    }
    private static TwCommandItemConfig config() { return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{\"RosterStorage\":\"BondedCompanions\",\"BondedRosterId\":\"test:roster\",\"CommandList\":[]}"), new com.hypixel.hytale.codec.ExtraInfo()); }
    private static TwCommandItemConfig genericConfig() { return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{\"RosterStorage\":\"OwnerCommandFamily\",\"CommandFamilyId\":\"test:family\",\"CommandList\":[]}"), new com.hypixel.hytale.codec.ExtraInfo()); }
    private static TwCommandItemConfig legacyConfig() { return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{\"CommandList\":[]}"), new com.hypixel.hytale.codec.ExtraInfo()); }
    private static LinkedNpcEntry recallEntry(long remainingMs) {
        return new LinkedNpcEntry(
                CARD, "Nimbus", null, 100, 100, 50, 100, 50, "",
                50, 100, 50, 100, false, false, false, false, false, false,
                -1L, null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, true, true,
                "species", "Species", null, null, null,
                false, false, false, 0L, 0.0, false,
                false, 0L, 0.0, false, true, remainingMs
        );
    }
    private static LinkedNpcEntry talentEntry(int availablePoints) {
        return new LinkedNpcEntry(
                CARD, "Nimbus", 100, 100, 50, 100, "",
                50, 100, 50, 100, true, false, false, false, false, false,
                -1L, null,
                new LinkedNpcEntry.FutureStat("Talent Points", availablePoints, 99),
                LinkedNpcTraitIndicator.EMPTY, false, false, true, true
        );
    }
    private static CommandPanelFeaturePresentation feature(int level, boolean available) {
        return feature(level, available, false);
    }
    private static CommandPanelFeaturePresentation feature(
            int level, boolean available, boolean airborne) {
        return CommandPanelFeaturePresentation.bonded(
                new BondedCompanionPanelPresentation("profile", "test:roster",
                        "role", 1L, "Nimbus", "Nimbus", "Female", null,
                        Map.of("level", Integer.toString(level), "currentXp", "12",
                                "levelingConfigId", "levels", "talentConfigId", "talents",
                                "talentSpentPoints", "1", "bonded.flightToggle.available",
                                Boolean.toString(available),
                                "bonded.flightToggle.airborne",
                                Boolean.toString(airborne)),
                        Map.of(), new BondedCompanionStatusPresentation(
                                BondedCompanionStateView.ACTIVE,
                                BondedCompanionStatusPresentation.Action.DISMISS,
                                true, null, 0L), null));
    }
    private static void assertCommand(CapturedUpdate update, String selector) { assertTrue(java.util.Arrays.stream(update.commands.getCommands()).anyMatch(command -> selector.equals(command.selector))); }
    private static void assertCommand(CapturedUpdate update, String selector, String expected) {
        assertTrue(java.util.Arrays.stream(update.commands.getCommands())
                .anyMatch(command -> selector.equals(command.selector)
                        && command.data.contains(expected)));
    }
    private static void put(Object target, String name, Object value) throws Exception { Field field = PlayerRef.class.getDeclaredField(name); field.setAccessible(true); unsafe().putObject(target, unsafe().objectFieldOffset(field), value); }
    private static Unsafe unsafe() throws Exception { Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); return (Unsafe) field.get(null); }
    private static final class CapturedPackets { private final List<CapturedUpdate> updates = new ArrayList<>(); private boolean fail; private int attempts; private void capture(UICommandBuilder commands, UIEventBuilder events) { attempts++; if (fail) throw new IllegalStateException("synthetic send failure"); updates.add(new CapturedUpdate(commands, events)); } }
    private static final class NavigationFixture { private final SignalSource source = new SignalSource(); private Runnable deferred; private int talents; private int groups; private void defer(PlayerRef player, Runnable action) { deferred = action; } private void talent(UUID ignored) { talents++; } private void groups() { groups++; } private void run() { deferred.run(); } }
    private static final class SignalSource implements LinkedPanelRefreshSignalSource { private int closes; @Override public AutoCloseable subscribe(Consumer<LinkedPanelRefreshSignal> listener) { return () -> closes++; } }
    private static final class RecordingScheduler
            implements LinkedPanelRefreshCoordinator.DelayedScheduler {
        private final List<Long> delays = new ArrayList<>();
        private final List<Runnable> callbacks = new ArrayList<>();
        @Override public void schedule(long delayMs, Runnable callback) {
            delays.add(delayMs);
            callbacks.add(callback);
        }
        private void runAll() { callbacks.forEach(Runnable::run); }
    }
    private record CapturedUpdate(UICommandBuilder commands, UIEventBuilder events) { }
}
