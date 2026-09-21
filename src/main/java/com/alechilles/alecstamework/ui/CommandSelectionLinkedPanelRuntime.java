package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Owns standard-page linked-panel refresh and card rendering. */
final class CommandSelectionLinkedPanelRuntime {
    private final LinkedNpcLocationCopyControl locationCopy = new LinkedNpcLocationCopyControl();

    void toggleLocationCopy(String command) {
        UICommandBuilder commands = new UICommandBuilder();
        locationCopy.toggle(command, page.linkedNpcEntries, commands);
        if (commands.getCommands().length > 0) page.packetSender.send(commands, new UIEventBuilder());
    }
    private final TameworkCommandSelectionPage page;
    private long removalConfirmOverlayRevision = -1L;
    private CommandUiDefaultDecorationBinder.State renderedDecorations =
            CommandUiDefaultDecorationBinder.State.EMPTY;

    CommandSelectionLinkedPanelRuntime(TameworkCommandSelectionPage page) {
        this.page = page;
    }

    void build(UICommandBuilder commands, UIEventBuilder events) {
        CommandUiDefaultDecorationBinder.bindHeader(commands,
                page.defaultDecorations());
        bindGroupShortcuts(commands, events, page.refreshTransaction.values(), true);
        page.cardRenderState.markRendered(page.linkedNpcEntries,
                page.pendingUnlinkNpcUuid, page.featureController.presentations());
        commands.clear("#TameworkLinkedPanelList");
        boolean hasEntries = page.linkedNpcEntries.length > 0;
        commands.set("#TameworkLinkedPanelEmptyState.Text",
                emptyText(page.resolveLanguage()));
        commands.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commands.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        for (int index = 0; index < page.linkedNpcEntries.length; index++) {
            bindCard(commands, events, index, page.linkedNpcEntries[index], true,
                    page.featureController.presentation(
                            page.linkedNpcEntries[index].npcUuid()));
        }
        renderedDecorations = page.defaultDecorations();
    }

    private String emptyText(String language) {
        return page.companionBinding != null
                ? LocalizedText.resolve(language, "tamework.ui.roster.emptyFilter")
                : LinkedNpcPanelPresentationSupport.empty(page.panelEmptyStateKeySupplier, language);
    }

    private void bindDefaultDecorations(UICommandBuilder commands) {
        CommandUiDefaultDecorationBinder.bindHeader(commands,
                page.defaultDecorations());
        // These commands follow any card appends in the same packet. A separate
        // contributor dispatch could arrive before a previously queued rebuild.
        for (int index = 0; index < page.linkedNpcEntries.length; index++) {
            LinkedNpcEntry entry = page.linkedNpcEntries[index];
            CommandPanelFeaturePresentation presentation = page.featureController
                    .presentation(entry.npcUuid());
            if (presentation != null && presentation.bonded() != null) continue;
            CommandUiDefaultDecorationBinder.bindCard(commands,
                    "#TameworkLinkedPanelList[" + index + "]", entry.npcUuid(),
                    page.defaultDecorations());
        }
    }

    void dispatch(LinkedPanelRefreshCoordinator.RenderPermit permit) {
        if (page.dismissed || !page.isCurrentLinkedPanelOwner()) {
            complete(permit, false);
            return;
        }
        Ref<EntityStore> ref = page.currentPlayerRef().getReference();
        if (ref == null || !ref.isValid()) {
            complete(permit, false);
            return;
        }
        LinkedNpcPanelRefreshPermitDispatch.dispatch(
                permit, task -> CommandPageWorldDispatcher.tryDispatch(ref, task),
                () -> runRefresh(permit), rejected -> complete(rejected, false));
    }

    void scheduleFilterApply() {
        long version = ++page.pendingFilterTextApplyVersion;
        CompletableFuture.runAsync(() -> dispatchFilterApply(version),
                CompletableFuture.delayedExecutor(
                        TameworkCommandSelectionPage.PANEL_FILTER_INPUT_DEBOUNCE_MS,
                        TimeUnit.MILLISECONDS));
    }

    void cancelFilterApply() {
        page.pendingFilterTextApplyVersion++;
        page.pendingFilterTextInput = null;
    }

    private void dispatchFilterApply(long version) {
        if (page.dismissed || !page.isCurrentLinkedPanelOwner()
                || version != page.pendingFilterTextApplyVersion) return;
        Ref<EntityStore> ref = page.currentPlayerRef().getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null
                || store.getExternalData().getWorld() == null) return;
        CommandPageWorldDispatcher.dispatch(ref,
                () -> runFilterApply(version));
    }

    private void runFilterApply(long version) {
        if (page.dismissed || !page.isCurrentLinkedPanelOwner()
                || version != page.pendingFilterTextApplyVersion) return;
        if (page.panelSetFilterTextCallback != null) {
            page.panelSetFilterTextCallback.accept(page.pendingFilterTextInput);
        }
        page.pendingFilterTextInput = null;
        page.pendingUnlinkNpcUuid = null;
        applyLocalFilter();
        requestRefresh();
    }

    void runRefresh(LinkedPanelRefreshCoordinator.RenderPermit permit) {
        if (page.dismissed || !page.isCurrentLinkedPanelOwner()
                || page.isFilterEditPending()) {
            complete(permit, false);
            return;
        }
        try {
            refreshEntries();
            LinkedNpcPanelRefreshOutcome outcome = refresh(
                    permit.progressionEligible());
            complete(permit, outcome.progressionIncluded(),
                    outcome.shortestCountdownRemainingMs());
        } catch (Throwable failure) {
            complete(permit, false);
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_linked_panel_refresh_failed", failure,
                    TameworkTelemetryContext.uiPage(
                            "TameworkCommandSelectionPage", "command_item",
                            "refresh", "Failed to refresh linked panel.").build());
        }
    }

    private void complete(LinkedPanelRefreshCoordinator.RenderPermit permit,
                          boolean progressionIncluded) {
        complete(permit, progressionIncluded,
                LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
    }

    private void complete(LinkedPanelRefreshCoordinator.RenderPermit permit,
                          boolean progressionIncluded,
                          long shortestCountdownRemainingMs) {
        page.refreshLifecycle.recordRendered(permit, progressionIncluded,
                shortestCountdownRemainingMs);
    }

    void requestRefresh() {
        page.refreshLifecycle.requestStateMutation();
    }

    LinkedNpcPanelRefreshOutcome refresh(boolean progressionEligible) {
        if (page.dismissed || !page.isCurrentLinkedPanelOwner()) {
            return LinkedNpcPanelRefreshOutcome.notSent(shortestCountdown());
        }
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        LinkedNpcPanelRefreshValues values = page.refreshTransaction.stagedValues();
        String language = page.resolveLanguage();
        bindGroupShortcuts(commands, events, values, false);
        if (!page.config.usesBondedCompanionRoster()) values.set(commands, "#TameworkCommandMenuTitle.Text",
                LinkedNpcPanelPresentationSupport.title(page.panelModeValueSupplier,
                        page.linkedNpcEntries, language));
        values.set(commands, "#TameworkLinkedPanelGroupSelectorDropdown.Entries",
                LinkedNpcPanelPresentationSupport.entries(
                        page.panelGroupActivationEntriesSupplier));
        values.set(commands, "#TameworkLinkedPanelGroupSelectorDropdown.Value",
                LinkedNpcPanelPresentationSupport.value(
                        page.panelGroupActivationValueSupplier, ""));
        values.set(commands, "#TameworkLinkedPanelModeDropdown.Entries",
                CommandSelectionPanelOptions.resolveModeDropdownEntries(language));
        values.set(commands, "#TameworkLinkedPanelModeDropdown.Value",
                LinkedNpcPanelPresentationSupport.mode(page.panelModeValueSupplier));
        LinkedNpcPanelPresentationSupport.bindModeTabs(commands, page.panelModeValueSupplier, values);
        if (!page.config.usesBondedCompanionRoster() && page.companionBinding == null) {
            LinkedNpcPanelPresentationSupport.bindFilterWidth(commands, page.panelModeValueSupplier, values);
        }
        values.set(commands, "#TameworkLinkedPanelAutoLinkCheck.Value",
                LinkedNpcPanelPresentationSupport.autoLink(
                        page.panelAutoLinkEnabledSupplier));
        values.set(commands, "#TameworkLinkedPanelActiveHighlightCheck.Value",
                LinkedNpcPanelPresentationSupport.activeHighlight(
                        page.activeHighlightBinding.enabledSupplier()));
        values.set(commands, "#TameworkLinkedPanelSubtitleRadiusControls.Visible",
                LinkedNpcPanelPresentationSupport.nearby(page.panelModeValueSupplier));
        values.set(commands, "#TameworkLinkedPanelRadiusValue.Text",
                LinkedNpcPanelPresentationSupport.radius(
                        page.panelRadiusLabelSupplier, language));
        if (!page.config.usesBondedCompanionRoster()) values.set(commands, "#TameworkLinkedPanelSortDropdown.Entries",
                CommandSelectionPanelOptions.resolveSortDropdownEntries(language));
        values.set(commands, "#TameworkLinkedPanelSortDropdown.Value",
                LinkedNpcPanelPresentationSupport.sort(page.panelSortValueSupplier));
        values.set(commands, "#TameworkLinkedPanelFilterDropdown.Entries",
                CommandSelectionPanelOptions.resolveFilterModeDropdownEntries(language));
        values.set(commands, "#TameworkLinkedPanelFilterDropdown.Value",
                LinkedNpcPanelPresentationSupport.filterMode(
                        page.panelFilterModeValueSupplier));
        if (!page.config.usesBondedCompanionRoster()) values.set(commands, "#TameworkLinkedPanelInlineFilterTextControls.Visible",
                LinkedNpcPanelPresentationSupport.showFilter(
                        page.panelFilterModeValueSupplier));
        if (!page.isFilterEditPending()) {
            values.set(commands, "#TameworkLinkedPanelFilterInput.Value",
                    LinkedNpcPanelPresentationSupport.input(
                            page.panelFilterInputValueSupplier));
        }
        long groupRevision = page.refreshTransaction.applyGroupOverlay(
                page.groupAssignOverlay, commands, language);
        long reviveRevision = page.refreshTransaction.applyReviveOverlay(
                page.featureController, commands, language);
        long removalConfirmRevision = page.removalConfirmOverlay.revision();
        if (removalConfirmOverlayRevision != removalConfirmRevision) {
            page.removalConfirmOverlay.applyTo(commands, language);
            page.bindRemovalConfirmationEvents(events);
        }
        boolean hasEntries = page.linkedNpcEntries.length > 0;
        values.set(commands, "#TameworkLinkedPanelEmptyState.Text",
                emptyText(language));
        values.set(commands, "#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        values.set(commands, "#TameworkLinkedPanelListViewport.Visible", hasEntries);
        Map<UUID, CommandPanelFeaturePresentation> features =
                new java.util.HashMap<>();
        page.featureController.presentations().forEach((id, presentation) ->
                features.put(id, BondedCompanionProgressionProjection.project(
                        page.cardRenderState.presentation(id), presentation,
                        progressionEligible)));
        renderCards(commands, events, hasEntries, features, language);
        if (!renderedDecorations.equals(page.defaultDecorations())) {
            bindDefaultDecorations(commands);
        }
        BondedCompanionPanelChrome.bindToolbar(commands, events, page, values);
        CompanionPanelChrome.bind(commands, events, page, values);
        if (commands.getCommands().length == 0 && events.getEvents().length == 0) {
            return LinkedNpcPanelRefreshOutcome.evaluated(
                    progressionEligible, shortestCountdown());
        }
        page.packetSender.send(commands, events);
        renderedDecorations = page.defaultDecorations();
        page.refreshTransaction.commit(values, groupRevision, reviveRevision);
        removalConfirmOverlayRevision = removalConfirmRevision;
        page.cardRenderState.markRendered(page.linkedNpcEntries,
                page.pendingUnlinkNpcUuid, features);
        return LinkedNpcPanelRefreshOutcome.sent(
                progressionEligible, shortestCountdown());
    }

    private void renderCards(UICommandBuilder commands, UIEventBuilder events,
                             boolean hasEntries,
                             Map<UUID, CommandPanelFeaturePresentation> features,
                             String language) {
        boolean structureChanged = page.cardRenderState.requiresRebuild(
                page.linkedNpcEntries, features);
        if (structureChanged) {
            commands.clear("#TameworkLinkedPanelList");
            for (int index = 0; index < page.linkedNpcEntries.length; index++) {
                LinkedNpcEntry entry = page.linkedNpcEntries[index];
                bindCard(commands, events, index, entry, true,
                        features.get(entry.npcUuid()));
            }
            return;
        }
        if (!hasEntries) return;
        for (int index = 0; index < page.linkedNpcEntries.length; index++) {
            LinkedNpcEntry entry = page.linkedNpcEntries[index];
            UUID id = entry.npcUuid();
            LinkedNpcPanelCardRenderState.Update update =
                    page.cardRenderState.updateAt(index, page.linkedNpcEntries,
                            page.pendingUnlinkNpcUuid, features);
            if (update == LinkedNpcPanelCardRenderState.Update.FULL) {
                bindCard(commands, events, index, entry, false, features.get(id));
            } else if (update == LinkedNpcPanelCardRenderState.Update.DYNAMIC) {
                LinkedNpcPanelCardDynamicPresenter.refresh(
                        commands, events, "#TameworkLinkedPanelList[" + index + "]",
                        id, page.cardRenderState.entryAt(index), entry,
                        page.cardRenderState.presentation(id), features.get(id),
                        page.isPendingUnlink(id), page.cardBindingConfig, language);
            }
        }
    }

    long shortestCountdown() {
        long removal = page.pendingRemovals.remainingMillis();
        long visible = LinkedNpcPanelCountdowns.shortest(
                page.featureController.presentations(), page.linkedNpcEntries);
        if (removal == Long.MAX_VALUE) return visible;
        return visible == LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS
                ? removal : Math.min(removal, visible);
    }

    void seedRefreshValues() {
        LinkedNpcPanelRefreshValueSeeder.seed(
                page.refreshTransaction.values(), page.resolveLanguage(),
                page.linkedNpcEntries, page.pendingFilterTextInput,
                page.panelEmptyStateKeySupplier, page.panelModeValueSupplier,
                page.panelAutoLinkEnabledSupplier,
                page.activeHighlightBinding.enabledSupplier(),
                page.panelRadiusLabelSupplier, page.panelSortValueSupplier,
                page.panelFilterModeValueSupplier,
                page.panelFilterInputValueSupplier,
                page.panelGroupActivationEntriesSupplier,
                page.panelGroupActivationValueSupplier);
        // The initial roster chrome overrides generic values; seed those final values too.
        if (!page.config.usesBondedCompanionRoster() && page.companionBinding == null) {
            LinkedNpcPanelPresentationSupport.bindFilterWidth(new UICommandBuilder(),
                    page.panelModeValueSupplier, page.refreshTransaction.values());
        }
        BondedCompanionPanelChrome.bindToolbar(new UICommandBuilder(), new UIEventBuilder(),
                page, page.refreshTransaction.values());
    }

    void seedRemovalConfirmOverlayRevision() {
        removalConfirmOverlayRevision = page.removalConfirmOverlay.revision();
    }

    void bindCard(UICommandBuilder commands, UIEventBuilder events, int index,
                  LinkedNpcEntry entry, boolean append,
                  CommandPanelFeaturePresentation presentation) {
        LinkedNpcPanelCardBinder.bind(commands, events, index, entry, append,
                page.isPendingUnlink(entry.npcUuid()), page.cardBindingConfig,
                page.resolveLanguage(), presentation);
        if (presentation != null && presentation.bonded() != null) return;
        CommandUiDefaultDecorationBinder.bindCard(commands,
                "#TameworkLinkedPanelList[" + index + "]", entry.npcUuid(),
                page.defaultDecorations());
        locationCopy.bind(commands, index, entry);
        String selector = "#TameworkLinkedPanelList[" + index + "] #GroupSelector";
        boolean available = canAssignGroup(entry, presentation);
        commands.set(selector + ".Visible", available);
        commands.set(selector + "Marker.Visible", available);
        commands.set(selector + "Label.Visible", available);
        if (!available) return;
        List<DropdownEntryInfo> entries = resolveGroupEntries();
        if (entries.isEmpty()) entries = LinkedNpcPanelGroupAssignOverlayState.fallbackEntries(page.resolveLanguage());
        if (page.companionBinding != null) {
            entries = entries.stream().filter(option -> !"None".equalsIgnoreCase(option.value())).toList();
            // Reapplying Entries/SelectedValues can reset an open native popup.
            // Group definitions change on the manager page, which rebuilds this page on return.
            if (append) {
                commands.set(selector + ".Style", com.hypixel.hytale.server.core.ui.Value.ref(
                        "TameworkPanelActionStyles.ui", "CompanionGroupDropdown"));
                commands.set(selector + ".MaxSelection", 0);
                commands.set(selector + ".Entries", entries);
                // The builder registers LocalizableString, not String, for array values.
                // Literal wrappers encode the IDs as plain strings without translating them.
                commands.set(selector + ".SelectedValues", entry.groupIds().stream()
                        .map(LocalizableString::fromString).toList());
            }
            bindCompanionGroupLabel(commands, selector, entry);
            if (append) events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                    EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID, CommandSelectionPageEventBinder.ASSIGN_GROUP_COMMAND_PREFIX + entry.npcUuid())
                            .append("@CompanionGroups", selector + ".SelectedValues"), false);
            return;
        }
        commands.set(selector + ".MaxSelection", 1);
        commands.set(selector + ".Style", com.hypixel.hytale.server.core.ui.Value.ref(
                "TameworkPanelActionStyles.ui", "GroupDropdown"));
        String selectedGroup = LinkedNpcPanelGroupAssignOverlayState.normalizeDropdownValue(entry.groupId());
        commands.set(selector + ".Entries", entries);
        commands.set(selector + ".Value", selectedGroup);
        commands.setObject(selector + "Label.Text", entries.stream()
                .filter(option -> option.value().equals(selectedGroup))
                .findFirst().orElse(entries.get(0)).label());
        LinkedNpcPanelGroupTabBinder.bind(commands, selector, entry);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID,
                        CommandSelectionPageEventBinder.ASSIGN_GROUP_COMMAND_PREFIX + entry.npcUuid())
                        .append(CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ASSIGN_VALUE, selector + ".Value"), false);
    }

    private void bindCompanionGroupLabel(UICommandBuilder commands, String selector, LinkedNpcEntry entry) {
        String label = entry.groups().isEmpty() ? LocalizedText.resolve(page.resolveLanguage(), "tamework.ui.companions.noGroups")
                : entry.groups().size() == 1 ? entry.groups().getFirst().name()
                : LocalizedText.format(page.resolveLanguage(), "tamework.ui.companions.groupSummary", entry.groups().getFirst().name(), entry.groups().size() - 1);
        commands.set(selector + "Label.Text", label);
        commands.set(selector + ".TooltipText", entry.groupName());
        LinkedNpcPanelGroupTabBinder.bind(commands, selector, entry);
    }

    void companionGroupsChanged(UUID id) {
        page.preserveCompanionOrder = true;
        refreshEntries();
        LinkedNpcEntry current = resolveEntry(id);
        if (current == null) return;
        for (int index = 0; index < page.cardRenderState.entryCount(); index++) {
            if (!id.equals(page.cardRenderState.entryAt(index).npcUuid())) continue;
            UICommandBuilder commands = new UICommandBuilder();
            // Only the adjacent caption/swatch changes; never reset the native checkbox popup.
            bindCompanionGroupLabel(commands, "#TameworkLinkedPanelList[" + index + "] #GroupSelector", current);
            page.packetSender.send(commands, new UIEventBuilder());
            return;
        }
    }

    private void bindGroupShortcuts(UICommandBuilder commands, UIEventBuilder events,
                                    LinkedNpcPanelRefreshValues values, boolean initial) {
        CommandGroupQuickSelectBinder.bind(commands, events, values,
                LinkedNpcPanelPresentationSupport.entries(page.panelGroupActivationEntriesSupplier),
                LinkedNpcPanelPresentationSupport.value(page.panelGroupActivationValueSupplier, ""),
                !page.config.usesBondedCompanionRoster() && !page.cardBindingConfig.ownerCommandFamilyRoster(), initial,
                page.panelGroupColorsSupplier.get(), page.companionBinding == null ? null : page.baseLinkedNpcEntries);
    }

    private boolean canAssignGroup(LinkedNpcEntry entry, CommandPanelFeaturePresentation presentation) {
        return entry != null && !page.cardBindingConfig.ownerCommandFamilyRoster()
                && (presentation == null || presentation.bonded() == null
                && (!presentation.managesRosterRow()
                || page.companionBinding != null && entry.captured() && entry.ownedActions()));
    }

    void assignGroup(UUID npcUuid, String value) {
        LinkedNpcEntry entry = resolveEntry(npcUuid);
        if (!canAssignGroup(entry, page.featureController.presentation(npcUuid))
                || page.isPendingUnlink(npcUuid) || page.panelAssignGroupCallback == null) return;
        // The existing callback revalidates the tool and group, and links eligible unlinked NPCs.
        page.panelAssignGroupCallback.accept(npcUuid,
                LinkedNpcPanelGroupAssignOverlayState.normalizeGroupIdForAssignment(value));
        page.pendingUnlinkNpcUuid = null;
        refreshEntries();
    }

    void openGroupAssignOverlay(UUID npcUuid) {
        LinkedNpcEntry entry = resolveEntry(npcUuid);
        if (entry == null) {
            refreshEntries();
            entry = resolveEntry(npcUuid);
        }
        if (entry != null) page.groupAssignOverlay.open(
                npcUuid, entry, resolveGroupEntries(), page.resolveLanguage());
    }

    void applyGroupAssignSelection() {
        LinkedNpcPanelGroupAssignOverlayState.AppliedSelection selection =
                page.groupAssignOverlay.consumeSelection(page.resolveLanguage());
        if (selection.npcUuid() == null
                || page.panelAssignGroupCallback == null) return;
        page.panelAssignGroupCallback.accept(
                selection.npcUuid(), selection.groupId());
        page.pendingUnlinkNpcUuid = null;
        refreshEntries();
    }

    private List<DropdownEntryInfo> resolveGroupEntries() {
        List<DropdownEntryInfo> entries = page.panelGroupAssignEntriesSupplier == null
                ? List.of() : page.panelGroupAssignEntriesSupplier.get();
        return entries == null ? List.of() : entries.stream().map(entry ->
                LinkedNpcPanelGroupAssignOverlayState.NONE_VALUE.equalsIgnoreCase(entry.value())
                        ? new DropdownEntryInfo(LinkedNpcPanelGroupAssignOverlayState.fallbackEntries(page.resolveLanguage()).get(0).label(), entry.value())
                        : entry).toList();
    }

    LinkedNpcEntry resolveEntry(UUID npcUuid) {
        for (LinkedNpcEntry entry : page.linkedNpcEntries) {
            if (entry != null && npcUuid.equals(entry.npcUuid())) return entry;
        }
        return null;
    }

    void refreshEntries() {
        List<LinkedNpcEntry> entries = page.linkedNpcBaseEntriesSupplier != null
                ? page.linkedNpcBaseEntriesSupplier.get()
                : page.linkedNpcEntriesSupplier != null
                ? page.linkedNpcEntriesSupplier.get() : List.of();
        page.baseLinkedNpcEntries = LinkedNpcEntrySnapshotMapper.build(entries,
                LocalizedText.resolve(page.resolveLanguage(),
                        "tamework.ui.linkedPanel.subtitle.defaultNpcName"));
        page.featureController.refresh();
        applyLocalFilter();
        if (page.pendingUnlinkNpcUuid != null
                && resolveEntry(page.pendingUnlinkNpcUuid) == null) {
            page.pendingUnlinkNpcUuid = null;
        }
        if (page.removalConfirmOverlay.isVisible()
                && !page.isPendingUnlink(page.removalConfirmOverlay.npcUuid())) {
            page.removalConfirmOverlay.clear();
        }
    }

    void applyLocalFilter() {
        if (page.companionBinding != null) {
            var previous = page.linkedNpcEntries;
            page.linkedNpcEntries = CompanionPanelChrome.filter(page.pendingRemovals.filter(page.baseLinkedNpcEntries),
                    page.companionBinding.state().get(), page.companionBinding.nearby().get(),
                    LinkedNpcPanelPresentationSupport.input(page.panelFilterInputValueSupplier));
            if (page.preserveCompanionOrder && previous != null) {
                var current = new java.util.HashMap<UUID, LinkedNpcEntry>();
                for (var entry : page.baseLinkedNpcEntries) current.put(entry.npcUuid(), entry);
                page.linkedNpcEntries = java.util.Arrays.stream(previous).map(entry -> current.get(entry.npcUuid()))
                        .filter(java.util.Objects::nonNull).toArray(LinkedNpcEntry[]::new);
            }
            return;
        }
        page.linkedNpcEntries = LinkedNpcPanelPresentationSupport.filter(
                page.pendingRemovals.filter(page.baseLinkedNpcEntries),
                LinkedNpcPanelPresentationSupport.filterMode(
                        page.panelFilterModeValueSupplier),
                LinkedNpcPanelPresentationSupport.input(
                        page.panelFilterInputValueSupplier));
        if (page.config.usesBondedCompanionRoster()) {
            page.linkedNpcEntries = BondedCompanionPanelChrome.filter(page.linkedNpcEntries,
                    page.featureController.presentations(), page.rosterStateFilter);
        }
    }
}
