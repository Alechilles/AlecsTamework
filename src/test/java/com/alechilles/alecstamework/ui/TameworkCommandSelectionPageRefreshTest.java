package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable page refresh coverage through the package-scoped packet boundary. */
class TameworkCommandSelectionPageRefreshTest {
    private static final UUID OWNER = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID CARD = UUID.fromString("a2000000-0000-0000-0000-000000000001");
    private static final LinkedNpcEntry ENTRY = new LinkedNpcEntry(CARD, "Nimbus", 10, 10, 0, 0, null, 0, 0, 0, 0, true, true, false, false, false, false, 0L, new LinkedNpcTraitIndicator[0]);

    /** Multi-group checks save immediately without rebuilding the mounted native popup. */
    @Test
    void companionGroupEditsUpdateCaptionWithoutResettingTheDropdown() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        var page = page(packets, new AtomicReference<>(), new NavigationFixture(), legacyConfig());
        AtomicReference<LinkedNpcEntry> row = new AtomicReference<>(ENTRY.withOwnedActions()
                .withCompanionGroups("profile", List.of(
                        new LinkedNpcEntry.GroupMembership("Barn", "Barn", "#445566")), true));
        replaceField(page, "linkedNpcBaseEntriesSupplier", (Supplier<List<LinkedNpcEntry>>) () -> List.of(row.get()));
        page.configureCompanions(new CompanionPanelBinding(() -> "All", ignored -> {}, () -> false, ignored -> {},
                (id, memberships) -> row.set(row.get().withCompanionGroups("profile", memberships.stream()
                        .map(group -> new LinkedNpcEntry.GroupMembership(group, group, "#445566")).toList(), true))));
        UICommandBuilder initial = new UICommandBuilder();
        page.build(null, initial, new UIEventBuilder(), null);
        var selected = java.util.Arrays.stream(initial.getCommands())
                .filter(command -> command.selector != null && command.selector.endsWith("#GroupSelector.SelectedValues"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Barn"), BsonDocument.parse(selected.data).getArray("0").stream()
                .map(value -> value.asString().getValue()).toList());
        var maximum = java.util.Arrays.stream(initial.getCommands())
                .filter(command -> command.selector != null && command.selector.endsWith("#GroupSelector.MaxSelection"))
                .findFirst().orElseThrow();
        assertEquals(0, BsonDocument.parse(maximum.data).getInt32("0").getValue(),
                "The client must allow multiple selections and keep the picker open.");
        CommandSelectionEventData data = CommandSelectionEventData.CODEC.decode(
                new BsonDocument(CommandSelectionPageEventBinder.EVENT_COMMAND_ID,
                        new org.bson.BsonString(CommandSelectionPageEventBinder.ASSIGN_GROUP_COMMAND_PREFIX + CARD))
                        .append("@CompanionGroups", new org.bson.BsonArray(List.of(
                                new org.bson.BsonString("Barn"), new org.bson.BsonString("Travel")))),
                new com.hypixel.hytale.codec.ExtraInfo());
        page.handleDataEvent(null, null, data);
        refresh(page, true);
        assertEquals(List.of("Barn", "Travel"), row.get().groupIds());
        assertTrue(packets.updates.stream().flatMap(packet -> java.util.Arrays.stream(packet.commands.getCommands()))
                .anyMatch(command -> command.selector.endsWith("#GroupSelectorLabel.Text") && command.data.contains("Barn +1")),
                () -> packets.updates.stream().flatMap(packet -> java.util.Arrays.stream(packet.commands.getCommands()))
                        .filter(command -> command.selector.endsWith("#GroupSelectorLabel.Text"))
                        .map(command -> command.selector + "=" + command.data).toList().toString());
        assertFalse(packets.updates.stream().flatMap(packet -> java.util.Arrays.stream(packet.commands.getCommands()))
                .anyMatch(command -> command.selector.endsWith("#GroupSelector.SelectedValues")
                        || command.selector.endsWith("#GroupSelector.Entries")
                        || command.selector.equals("#TameworkLinkedPanelList")),
                "Saving a check must leave the mounted dropdown and its selections untouched.");
        page.onDismiss(null, null);
    }

    @Test
    void decorationsOnlyTargetRenderedCardsWhileOwnerListRefreshIsPending() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(),
                new NavigationFixture(), legacyConfig());
        build(page);
        // A mode change refreshes the model before the card rebuild reaches the client.
        LinkedNpcEntry second = new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 10, 10,
                0, 0, null, 0, 0, 0, 0, true, true, false, false, false, false,
                0L, new LinkedNpcTraitIndicator[0]);
        replaceField(page, "linkedNpcBaseEntriesSupplier",
                (Supplier<List<LinkedNpcEntry>>) () -> List.of(ENTRY, second));
        invoke(page, "refreshLinkedNpcEntries");
        var snapshot = new com.alechilles.alecstamework.api.commandui.CommandUiSnapshot(
                OWNER, 1L, 1L, null, List.of(), List.of(),
                new com.alechilles.alecstamework.api.commandui.CommandUiPanelState("owned"));
        UICommandBuilder commands = new UICommandBuilder();
        page.updateDefaultDecorations(snapshot, commands);
        assertTrue(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                "#TameworkLinkedPanelList[0] #ContributorPortraitStars.Visible".equals(command.selector)));
        assertFalse(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                command.selector.startsWith("#TameworkLinkedPanelList[1]")),
                "Contributor updates must not target a card before it is appended.");
        refresh(page, true);
        commands = new UICommandBuilder();
        page.updateDefaultDecorations(snapshot, commands);
        assertTrue(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                "#TameworkLinkedPanelList[1] #ContributorPortraitStars.Visible".equals(command.selector)));
        page.onDismiss(null, null);
    }

    @Test
    void modeTabClicksNeedNoClientPropertyLookupAndUpdateTheHeader() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(),
                new NavigationFixture(), legacyConfig());
        AtomicReference<String> mode = new AtomicReference<>("LinkedMode");
        replaceField(page, "panelModeValueSupplier", (Supplier<String>) mode::get);
        replaceField(page, "panelSetModeCallback", (Consumer<String>) mode::set);
        UIEventBuilder events = new UIEventBuilder();
        page.build(null, new UICommandBuilder(), events, null);
        for (String tab : List.of("Nearby", "Owned", "Linked")) {
            var binding = java.util.Arrays.stream(events.getEvents())
                    .filter(event -> event.selector.equals("#TameworkMode" + tab)).findFirst().orElseThrow();
            BsonDocument payload = BsonDocument.parse(binding.data);
            assertTrue(payload.keySet().stream().noneMatch(key -> key.startsWith("@")),
                    "A tab click must not ask the client to resolve a literal mode as a UI property.");
            page.handleDataEvent(null, null, CommandSelectionEventData.CODEC.decode(payload,
                    new com.hypixel.hytale.codec.ExtraInfo()));
            assertEquals(tab + "Mode", mode.get());
            refresh(page, true);
            assertTrue(java.util.Arrays.stream(packets.updates.getLast().commands.getCommands()).anyMatch(command ->
                    command.selector.equals("#TameworkCommandMenuTitle.Text")
                            && command.data.contains("Command Menu - 1 " + tab)));
        }
        page.onDismiss(null, null);
    }

    @Test
    void groupShortcutsActivateTheExistingSelectionAndRefreshWithoutRepeatedPackets() throws Exception {
        var all = new com.hypixel.hytale.server.core.ui.DropdownEntryInfo(
                com.hypixel.hytale.server.core.ui.LocalizableString.fromString("All"), "__all__");
        var pasture = new com.hypixel.hytale.server.core.ui.DropdownEntryInfo(
                com.hypixel.hytale.server.core.ui.LocalizableString.fromString("Pasture"), "pasture");
        AtomicReference<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> groups =
                new AtomicReference<>(List.of(all, pasture));
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(),
                new NavigationFixture(), legacyConfig(), groups::get);
        AtomicReference<String> selection = new AtomicReference<>("__all__");
        replaceField(page, "panelGroupActivationValueSupplier", (Supplier<String>) selection::get);
        replaceField(page, "panelSetGroupActivationCallback", (Consumer<String>) selection::set);
        UIEventBuilder events = new UIEventBuilder();
        page.build(null, new UICommandBuilder(), events, null);
        for (int index : List.of(1, 0)) {
            String selector = "#TameworkGroupQuickSelectList[" + index + "] #QuickGroupButton";
            var binding = java.util.Arrays.stream(events.getEvents())
                    .filter(event -> event.selector.equals(selector)).findFirst().orElseThrow();
            BsonDocument payload = BsonDocument.parse(binding.data);
            assertTrue(payload.keySet().stream().noneMatch(key -> key.startsWith("@")));
            page.handleDataEvent(null, null, CommandSelectionEventData.CODEC.decode(payload,
                    new com.hypixel.hytale.codec.ExtraInfo()));
            assertEquals(index == 0 ? "__all__" : "pasture", selection.get());
            refresh(page, true);
            assertCommand(packets.updates.getLast(), selector + ".Style");
        }
        packets.updates.clear();
        refresh(page, true);
        assertEquals(0, packets.updates.size());
        groups.set(List.of(all));
        refresh(page, true);
        assertTrue(java.util.Arrays.stream(packets.updates.getLast().events.getEvents())
                .anyMatch(event -> event.data.contains("__all__")));
        page.onDismiss(null, null);
    }

    @Test
    void inlineGroupSelectionRoutesAssignmentAndClearingWithoutOpeningAModal() throws Exception {
        LinkedNpcEntry unlinked = new LinkedNpcEntry(CARD, "Nimbus", 10, 10, 0, 0, 0,
                "", 0, 0, 0, 0, true, false, false, false, false, false,
                -1L, null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, false, false,
                "duck", "Duck", null, null, null, false, false, 0L, 0.0, false);
        for (LinkedNpcEntry entry : List.of(ENTRY, unlinked)) {
            TameworkCommandSelectionPage page = page(new CapturedPackets(), new AtomicReference<>(),
                    new NavigationFixture(), legacyConfig(), OWNER, List::of, entry);
            List<String> assignments = new ArrayList<>();
            replaceField(page, "panelAssignGroupCallback", (BiConsumer<UUID, String>)
                    (id, group) -> assignments.add(id + "/" + group));
            UIEventBuilder events = new UIEventBuilder();
            page.build(null, new UICommandBuilder(), events, null);
            assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(binding ->
                    binding.type == com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.ValueChanged
                            && binding.data.contains("__assigngroup__:" + CARD)
                            && binding.data.contains("#GroupSelector.Value")));

            groupEvent(page, CARD, "pasture");
            groupEvent(page, CARD, "None");
            groupEvent(page, UUID.randomUUID(), "pasture");

            assertEquals(List.of(CARD + "/pasture", CARD + "/null"), assignments);
            page.onDismiss(null, null);
        }
    }

    @Test
    void inlineGroupSelectionRejectsManagedRosterTargets() throws Exception {
        for (TwCommandItemConfig config : List.of(config(), genericConfig())) {
            TameworkCommandSelectionPage page = page(new CapturedPackets(), new AtomicReference<>(),
                    new NavigationFixture(), config);
            AtomicInteger assignments = new AtomicInteger();
            replaceField(page, "panelAssignGroupCallback", (BiConsumer<UUID, String>)
                    (id, group) -> assignments.incrementAndGet());
            build(page);
            groupEvent(page, CARD, "pasture");
            assertEquals(0, assignments.get());
            page.onDismiss(null, null);
        }
    }

    private static void groupEvent(TameworkCommandSelectionPage page, UUID id, String group) throws Exception {
        CommandSelectionEventData data = new CommandSelectionEventData();
        Field command = CommandSelectionEventData.class.getDeclaredField("commandId");
        command.setAccessible(true);
        unsafe().putObject(data, unsafe().objectFieldOffset(command), "__assigngroup__:" + id);
        data.panelGroupAssignValue = group;
        page.handleDataEvent(null, null, data);
    }

    @Test
    void primaryAssignmentsReuseSelectionWithoutClosingOrAcceptingHiddenCommands() throws Exception {
        for (String roster : List.of("", "\"RosterStorage\":\"OwnerCommandFamily\",\"CommandFamilyId\":\"test:family\",")) {
            TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(BsonDocument.parse(
                    "{" + roster + "\"CommandList\":[{\"Id\":\"Follow\",\"ShowInRadial\":true},"
                            + "{\"Id\":\"Stay\",\"ShowInRadial\":true},{\"Id\":\"Hidden\",\"ShowInRadial\":false}]}"),
                    new com.hypixel.hytale.codec.ExtraInfo());
            NavigationFixture fixture = new NavigationFixture();
            TameworkCommandSelectionPage page = page(new CapturedPackets(),
                    new AtomicReference<>(), fixture, config);
            for (String value : List.of("Follow", "forged", "Hidden", "Stay")) {
                CommandSelectionEventData data = new CommandSelectionEventData();
                data.primaryCommandValue = value;
                page.handleDataEvent(null, null, data);
            }
            assertEquals(List.of("Follow", "Stay"), fixture.selections);
            assertEquals(0, fixture.source.closes);
            page.onDismiss(null, null);
        }
    }

    @Test
    void guideBlocksUnderlyingCommandsAndReturnsToBothRosterKinds() throws Exception {
        for (String roster : List.of("",
                "\"RosterStorage\":\"OwnerCommandFamily\",\"CommandFamilyId\":\"test:family\",",
                "\"RosterStorage\":\"BondedCompanions\",\"BondedRosterId\":\"test:roster\",")) {
            TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(BsonDocument.parse(
                    "{" + roster + "\"CommandList\":[{\"Id\":\"Follow\",\"ShowInRadial\":true}]}"),
                    new com.hypixel.hytale.codec.ExtraInfo());
            NavigationFixture fixture = new NavigationFixture();
            CapturedPackets packets = new CapturedPackets();
            TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(), fixture, config);
            build(page);
            page.handleDataEvent(null, null, guideEvent("guide:open"));
            CommandSelectionEventData assignment = new CommandSelectionEventData();
            assignment.primaryCommandValue = "Follow";
            page.handleDataEvent(null, null, assignment);
            assertTrue(fixture.selections.isEmpty(), "Reading help must not change a command assignment.");
            page.handleDataEvent(null, null, guideEvent("guide:close"));
            assertEquals(0, fixture.source.closes, "Returning from help must keep the roster session alive.");
            page.handleDataEvent(null, null, assignment);
            assertEquals(List.of("Follow"), fixture.selections);
            page.onDismiss(null, null);
        }
    }

    private static CommandSelectionEventData guideEvent(String action) {
        return CommandSelectionEventData.CODEC.decode(
                new BsonDocument("CommandId", new org.bson.BsonString(action)),
                new com.hypixel.hytale.codec.ExtraInfo());
    }

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
    void removalMenuMustOpenBeforeItRoutesTheUnlinkAction() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicInteger unlinks = new AtomicInteger();
        TameworkCommandSelectionPage page = removalPage(packets, unlinks);
        build(page);

        event(page, "__unlink__:" + CARD);
        assertEquals(0, unlinks.get());

        event(page, "__removal_menu__:" + CARD);
        assertTrue(page.isPendingUnlink(CARD));
        assertEquals(0, unlinks.get());

        event(page, "__unlink__:" + CARD);
        assertEquals(1, unlinks.get());
        assertTrue(!page.isPendingUnlink(CARD));
    }

    @Test
    void unsupportedActiveHighlightsAreHiddenFromTheGenericPanel() throws Exception {
        TameworkCommandSelectionPage page = page(
                new CapturedPackets(),
                new AtomicReference<>(feature(4, false)),
                new NavigationFixture(),
                genericConfig()
        );
        UICommandBuilder commands = new UICommandBuilder();

        page.build(null, commands, new UIEventBuilder(), null);

        assertTrue(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                "#TameworkLinkedPanelActiveHighlightControls.Visible".equals(command.selector)
                        && command.data.contains("false")));
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
    void dynamicProgressionRefreshKeepsBondedFlightToggleClickable() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        AtomicReference<CommandPanelFeaturePresentation> feature =
                new AtomicReference<>(feature(4, true));
        TameworkCommandSelectionPage page = page(packets, feature);
        build(page); feature.set(feature(5, true)); refresh(page, true);
        CapturedUpdate update = packets.updates.getFirst();
        assertCommand(update, "#TameworkLinkedPanelList[0] #BondedLevelText.Text");
        assertEquals(0, update.events.getEvents().length,
                "An unrelated refresh must preserve the existing input handlers.");
        assertFalse(java.util.Arrays.stream(update.commands.getCommands()).anyMatch(command ->
                command.selector.contains("#BondedFlight")),
                "Progression changes must leave the flight button untouched.");
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
    void dynamicFlightRefreshPreservesTheExistingToggleBinding() throws Exception {
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
        assertEquals(0, update.events.getEvents().length,
                "Flight feedback must preserve the existing input handlers.");
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
    void changingAppearanceUsesRegisteredIconAndClearsUnsupportedCaptureImage() throws Exception {
        Field storeField = Item.class.getDeclaredField("ASSET_STORE");
        storeField.setAccessible(true);
        Object previousStore = storeField.get(null);
        try {
            storeField.set(null, new TestItemAssetStore(new DefaultAssetMap<>(
                    Map.of("Sheep", portraitItem("Sheep", "Icons/ItemsGenerated/Sheep.png"),
                            "Sheep_Shorn", portraitItem("Sheep_Shorn", "Icons/ItemsGenerated/Sheep_Shorn.png")))));
            CapturedPackets packets = new CapturedPackets();
            AtomicReference<List<LinkedNpcEntry>> entries = new AtomicReference<>(
                    List.of(ENTRY.withPortraitIcon("Icons/ItemsGenerated/Sheep.png")));
            TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(),
                    new NavigationFixture(), legacyConfig());
            replaceField(page, "linkedNpcBaseEntriesSupplier", (Supplier<List<LinkedNpcEntry>>) entries::get);
            build(page);

            entries.set(List.of(ENTRY.withPortraitIcon("Icons/ItemsGenerated/Sheep_Shorn.png")));
            refresh(page, false);
            assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #Portrait.Slots", "Sheep_Shorn");
            assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #Portrait.Visible", "true");

            entries.set(List.of(ENTRY.withPortraitIcon("Icons/CaptureOnlyVariant.png")));
            refresh(page, false);
            assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #Portrait.Visible", "false");
            assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #Portrait.Slots", "[]");
        } finally {
            storeField.set(null, previousStore);
        }
    }

    private static Item portraitItem(String id, String icon) throws Exception {
        Item item = new Item(id);
        Field iconField = Item.class.getDeclaredField("icon");
        iconField.setAccessible(true);
        iconField.set(item, icon);
        return item;
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
    void cancelRosterDeletionRestoresActionsAndIgnoresAnotherCardsCancel() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)));
        build(page);
        event(page, CommandSelectionPageEventBinder.UNLINK_COMMAND_PREFIX + CARD);
        refresh(page, true);
        assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #BondedUnlinkConfirmButton.Visible", "true");
        int beforeStaleCancel = packets.updates.size();
        event(page, BondedCompanionCardPresenter.CANCEL_UNLINK_COMMAND_PREFIX + UUID.randomUUID());
        assertEquals(beforeStaleCancel, packets.updates.size());
        var cancel = java.util.Arrays.stream(packets.updates.getLast().events.getEvents())
                .filter(binding -> binding.selector.endsWith(" #BondedUnlinkCancelButton"))
                .findFirst().orElseThrow();
        page.handleDataEvent(null, null, CommandSelectionEventData.CODEC.decode(
                BsonDocument.parse(cancel.data), new com.hypixel.hytale.codec.ExtraInfo()));
        refresh(page, true);
        assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #BondedUnlinkConfirmButton.Visible", "false");
        assertCommand(packets.updates.getLast(), "#TameworkLinkedPanelList[0] #BondedPrimaryActionNoTooltip.Visible", "true");
        page.onDismiss(null, null);
    }

    @Test
    void availabilityEmblemsPreserveSavedHealthAndRemainVisibleDuringRemoval() throws Exception {
        String[] states = {"Dead", "Unloaded", "Lost", "Captured", "InCoop", "Live"};
        for (String state : states) {
            for (boolean removing : new boolean[] {false, true}) {
                boolean dead = state.equals("Dead");
                LinkedNpcEntry entry = new LinkedNpcEntry(CARD, "Nimbus", dead ? 0 : 75, 100, 50, 100, "",
                        50, 100, 50, 100, state.equals("Live"), false, dead,
                        state.equals("Captured"), state.equals("InCoop"), state.equals("Lost"),
                        0L, null, new LinkedNpcEntry.FutureStat("Talent Points", 2, 99),
                        LinkedNpcTraitIndicator.EMPTY, false, false, true, false);
                TameworkCommandSelectionPage page = page(new CapturedPackets(), new AtomicReference<>(),
                        new NavigationFixture(), legacyConfig(), OWNER, List::of, entry);
                if (removing) {
                    replaceField(page, "pendingUnlinkNpcUuid", CARD);
                }
                UICommandBuilder commands = new UICommandBuilder();
                UIEventBuilder events = new UIEventBuilder();
                page.build(null, commands, events, null);
                CapturedUpdate update = new CapturedUpdate(commands, events);
                String card = "#TameworkLinkedPanelList[0]";
                assertCommand(update, card + " #StatusEmblem.Visible", Boolean.toString(!state.equals("Live")));
                if (!state.equals("Live")) {
                    assertCommand(update, card + " #StatusEmblem.Background", "Tamework/StatusEmblems/" + state + ".png");
                    assertCommand(update, card + " #StatusUnloaded.Visible", "true");
                }
                assertCommand(update, card + " #HealthText.Text", dead ? "0/100" : "75/100");
                page.onDismiss(null, null);
            }
        }
    }

    @Test
    void deadCardRetainsSavedTalentPointsWithoutOfferingLiveOnlyEditing() throws Exception {
        LinkedNpcEntry dead = new LinkedNpcEntry(CARD, "Nimbus", 0, 100, 50, 100, "",
                50, 100, 50, 100, false, false, true, false, false, false,
                0L, null, new LinkedNpcEntry.FutureStat("Talent Points", 2, 99),
                LinkedNpcTraitIndicator.EMPTY, false, false, true, false);
        TameworkCommandSelectionPage page = page(new CapturedPackets(), new AtomicReference<>(),
                new NavigationFixture(), legacyConfig(), OWNER, List::of, dead);
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        page.build(null, commands, events, null);
        CapturedUpdate update = new CapturedUpdate(commands, events);
        String action = "#TameworkLinkedPanelList[0] #TalentPointAction";
        assertCommand(update, action + ".Visible", "true");
        assertCommand(update, action + " #TalentPointButton.Disabled", "true");
        assertTrue(java.util.Arrays.stream(events.getEvents()).noneMatch(binding ->
                binding.selector.equals(action + " #TalentPointButton")));
        assertCommand(update, "#TameworkLinkedPanelList[0] #HealthText.Text", "0/100");
        page.onDismiss(null, null);
    }

    @Test
    void removalMenuKeepsProgressionGroupActiveAndCooldownControls() throws Exception {
        LinkedNpcEntry removalEntry = new LinkedNpcEntry(CARD, "Nimbus", null,
                100, 100, 50, 100, 50, null, 60, 100, 70, 100,
                true, false, false, false, false, false, 0L, null, null,
                new LinkedNpcEntry.FutureStat("Talent Points", 2, 99), LinkedNpcTraitIndicator.EMPTY,
                false, false, true, true, true, false, null, null, null, null, null,
                true, true, true, 65_000L, 0.5, true, true, 125_000L, 0.25, true, false, 0L);
        TameworkCommandSelectionPage page = page(new CapturedPackets(), new AtomicReference<>(),
                new NavigationFixture(), legacyConfig(), OWNER, List::of, removalEntry);
        replaceField(page, "panelAssignGroupCallback", (BiConsumer<UUID, String>) (id, group) -> {});
        replaceField(page, "pendingUnlinkNpcUuid", CARD);
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        page.build(null, commands, events, null);
        CapturedUpdate update = new CapturedUpdate(commands, events);
        String card = "#TameworkLinkedPanelList[0]";
        for (String control : List.of("TalentPointAction", "GroupSelector", "ActiveToggleInactiveButton", "CooldownRow")) {
            assertCommand(update, card + " #" + control + ".Visible", "true");
        }
        assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(binding ->
                binding.selector.equals(card + " #TalentPointAction #TalentPointButton")));
        assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(binding ->
                binding.selector.equals(card + " #GroupSelector")));
        page.onDismiss(null, null);
    }

    @Test
    void dynamicTalentRefreshKeepsTalentsVisibleDuringRemoval() throws Exception {
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
                "true");
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
    void feedbackClickDrainsRefreshAndIgnoresDuplicateNavigation() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture);
        UIEventBuilder events = new UIEventBuilder();
        page.build(null, new UICommandBuilder(), events, null);
        var binding = java.util.Arrays.stream(events.getEvents())
                .filter(value -> value.selector.equals("#CommandMenuFeedbackButton")).findFirst().orElseThrow();
        var data = CommandSelectionEventData.CODEC.decode(BsonDocument.parse(binding.data),
                new com.hypixel.hytale.codec.ExtraInfo());
        page.handleDataEvent(null, null, data);
        Runnable firstNavigation = fixture.deferred;
        assertTrue(firstNavigation != null);
        page.handleDataEvent(null, null, data);
        acceptedRefresh(page, 17L);
        assertEquals(firstNavigation, fixture.deferred);
        assertEquals(1, fixture.source.closes);
        assertEquals(0, packets.updates.size());
    }

    @Test
    void settingsWithoutAuthorizedPlayerKeepsCommandMenuOpen() throws Exception {
        CapturedPackets packets = new CapturedPackets();
        NavigationFixture fixture = new NavigationFixture();
        TameworkCommandSelectionPage page = page(packets, new AtomicReference<>(feature(4, false)), fixture);
        build(page);
        event(page, "__settings__");
        assertEquals(0, fixture.source.closes);
        assertNull(fixture.deferred);
        event(page, "__talents__:" + CARD);
        fixture.run();
        assertEquals(1, fixture.talents);
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
        return page(packets, feature, fixture, commandConfig, owner, activationEntries, ENTRY);
    }
    private static TameworkCommandSelectionPage page(CapturedPackets packets, AtomicReference<CommandPanelFeaturePresentation> feature, NavigationFixture fixture, TwCommandItemConfig commandConfig, UUID owner, Supplier<List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo>> activationEntries, LinkedNpcEntry entry) throws Exception {
        try (AutoCloseable ignored = LinkedNpcPanelRefreshTestSeam.installPacketSender(packets::capture);
             AutoCloseable ignoredNavigator = LinkedNpcPanelRefreshTestSeam.installDeferredNavigator(fixture::defer)) {
            PlayerRef player = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
            put(player, "uuid", owner); put(player, "username", "PageRefreshTester"); put(player, "language", "en-US");
            Consumer<UUID> noUuid = value -> { }; Consumer<String> noString = value -> { }; BiConsumer<UUID, String> noGroup = (a, b) -> { };
            return new TameworkCommandSelectionPage(player, commandConfig, null, true,
                    () -> List.of(entry), () -> List.of(entry), () -> feature.get() == null
                            ? Map.of() : Map.of(CARD, feature.get()), () -> null,
                    () -> "LinkedMode", () -> false, () -> "16", () -> "Default", () -> "None", () -> "", activationEntries, () -> "", List::of, value -> true, true,
                    noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, noUuid, noUuid, noUuid, noUuid, fixture::talent, noString, value->{}, ()->{}, ()->{}, fixture::groups, noString, noString, noString, ()->{}, noString, noGroup, fixture.selections::add, fixture.source);
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
    private static TameworkCommandSelectionPage removalPage(
            CapturedPackets packets, AtomicInteger unlinks) throws Exception {
        try (AutoCloseable ignored = LinkedNpcPanelRefreshTestSeam.installPacketSender(packets::capture);
             AutoCloseable ignoredNavigator = LinkedNpcPanelRefreshTestSeam.installDeferredNavigator((player, action) -> { })) {
            PlayerRef player = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
            put(player, "uuid", OWNER); put(player, "username", "PageRefreshTester"); put(player, "language", "en-US");
            Consumer<UUID> noUuid = value -> { }; Consumer<String> noString = value -> { };
            BiConsumer<UUID, String> noGroup = (a, b) -> { };
            return new TameworkCommandSelectionPage(player, legacyConfig(), null, true,
                    () -> List.of(ENTRY), () -> List.of(ENTRY), Map::of, () -> null,
                    () -> "LinkedMode", () -> false, () -> "16", () -> "Default", () -> "None", () -> "", List::of, () -> "", List::of, value -> true, true,
                    noUuid, ignoredNpc -> unlinks.incrementAndGet(), noUuid, noUuid, noUuid,
                    noUuid, noUuid, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, (a,b,c)->{}, noUuid, noUuid, noUuid, noUuid, noUuid, noString, value->{}, ()->{}, ()->{}, ()->{}, noString, noString, noString, ()->{}, noString, noGroup, noString, LinkedPanelRefreshSignalSource.none());
        }
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
    private static final class NavigationFixture { private final List<String> selections = new ArrayList<>(); private final SignalSource source = new SignalSource(); private Runnable deferred; private int talents; private int groups; private void defer(PlayerRef player, Runnable action) { deferred = action; } private void talent(UUID ignored) { talents++; } private void groups() { groups++; } private void run() { deferred.run(); } }
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
