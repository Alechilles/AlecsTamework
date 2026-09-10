package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
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
    private final TameworkCommandSelectionPage page;

    CommandSelectionLinkedPanelRuntime(TameworkCommandSelectionPage page) {
        this.page = page;
    }

    void build(UICommandBuilder commands, UIEventBuilder events) {
        page.cardRenderState.markRendered(page.linkedNpcEntries,
                page.pendingUnlinkNpcUuid, page.featureController.presentations());
        commands.clear("#TameworkLinkedPanelList");
        boolean hasEntries = page.linkedNpcEntries.length > 0;
        commands.set("#TameworkLinkedPanelEmptyState.Text",
                LinkedNpcPanelPresentationSupport.empty(
                        page.panelEmptyStateKeySupplier, page.resolveLanguage()));
        commands.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commands.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        for (int index = 0; index < page.linkedNpcEntries.length; index++) {
            bindCard(commands, events, index, page.linkedNpcEntries[index], true,
                    page.featureController.presentation(
                            page.linkedNpcEntries[index].npcUuid()));
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
        values.set(commands, "#TameworkLinkedPanelTitle.Text",
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
        values.set(commands, "#TameworkLinkedPanelSortDropdown.Entries",
                CommandSelectionPanelOptions.resolveSortDropdownEntries(language));
        values.set(commands, "#TameworkLinkedPanelSortDropdown.Value",
                LinkedNpcPanelPresentationSupport.sort(page.panelSortValueSupplier));
        values.set(commands, "#TameworkLinkedPanelFilterDropdown.Entries",
                CommandSelectionPanelOptions.resolveFilterModeDropdownEntries(language));
        values.set(commands, "#TameworkLinkedPanelFilterDropdown.Value",
                LinkedNpcPanelPresentationSupport.filterMode(
                        page.panelFilterModeValueSupplier));
        values.set(commands, "#TameworkLinkedPanelInlineFilterTextControls.Visible",
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
        boolean hasEntries = page.linkedNpcEntries.length > 0;
        values.set(commands, "#TameworkLinkedPanelEmptyState.Text",
                LinkedNpcPanelPresentationSupport.empty(
                        page.panelEmptyStateKeySupplier, language));
        values.set(commands, "#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        values.set(commands, "#TameworkLinkedPanelListViewport.Visible", hasEntries);
        Map<UUID, CommandPanelFeaturePresentation> features =
                new java.util.HashMap<>();
        page.featureController.presentations().forEach((id, presentation) ->
                features.put(id, BondedCompanionProgressionProjection.project(
                        page.cardRenderState.presentation(id), presentation,
                        progressionEligible)));
        renderCards(commands, events, hasEntries, features, language);
        if (commands.getCommands().length == 0 && events.getEvents().length == 0) {
            return LinkedNpcPanelRefreshOutcome.evaluated(
                    progressionEligible, shortestCountdown());
        }
        page.packetSender.send(commands, events);
        page.refreshTransaction.commit(values, groupRevision, reviveRevision);
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
    }

    void bindCard(UICommandBuilder commands, UIEventBuilder events, int index,
                  LinkedNpcEntry entry, boolean append,
                  CommandPanelFeaturePresentation presentation) {
        LinkedNpcPanelCardBinder.bind(commands, events, index, entry, append,
                page.isPendingUnlink(entry.npcUuid()), page.cardBindingConfig,
                page.resolveLanguage(), presentation);
        if (presentation != null && presentation.bonded() != null) return;
        String selector = "#TameworkLinkedPanelList[" + index + "] #GroupSelector";
        boolean available = canAssignGroup(entry, presentation)
                && !page.isPendingUnlink(entry.npcUuid());
        commands.set(selector + ".Visible", available);
        if (!available) return;
        List<DropdownEntryInfo> entries = resolveGroupEntries();
        commands.set(selector + ".Entries", entries.isEmpty()
                ? LinkedNpcPanelGroupAssignOverlayState.fallbackEntries(page.resolveLanguage()) : entries);
        commands.set(selector + ".Value",
                LinkedNpcPanelGroupAssignOverlayState.normalizeDropdownValue(entry.groupId()));
        LinkedNpcPanelGroupTabBinder.bind(commands, selector, entry, false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID,
                        CommandSelectionPageEventBinder.ASSIGN_GROUP_COMMAND_PREFIX + entry.npcUuid())
                        .append(CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ASSIGN_VALUE, selector + ".Value"), false);
    }

    private boolean canAssignGroup(LinkedNpcEntry entry, CommandPanelFeaturePresentation presentation) {
        return entry != null && !page.cardBindingConfig.ownerCommandFamilyRoster()
                && (presentation == null || presentation.bonded() == null && !presentation.managesRosterRow());
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
        return entries == null ? List.of() : entries;
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
        applyLocalFilter();
        page.featureController.refresh();
        if (page.pendingUnlinkNpcUuid != null
                && resolveEntry(page.pendingUnlinkNpcUuid) == null) {
            page.pendingUnlinkNpcUuid = null;
        }
    }

    void applyLocalFilter() {
        page.linkedNpcEntries = LinkedNpcPanelPresentationSupport.filter(
                page.pendingRemovals.filter(page.baseLinkedNpcEntries),
                LinkedNpcPanelPresentationSupport.filterMode(
                        page.panelFilterModeValueSupplier),
                LinkedNpcPanelPresentationSupport.input(
                        page.panelFilterInputValueSupplier));
    }
}
