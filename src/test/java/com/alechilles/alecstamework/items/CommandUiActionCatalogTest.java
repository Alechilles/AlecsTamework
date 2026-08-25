package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionRequest;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiCommandOption;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable action exposure for provider snapshots. */
class CommandUiActionCatalogTest {
    @Test
    void contributorHandlesAreAttachedToDetachedContributionViews() {
        UUID sessionId = UUID.randomUUID();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:contributor");
        CommandUiContributorActionHandler handler = context ->
                CompletableFuture.completedFuture(CommandUiActionResult.applied());
        CommandUiContributorAction action = new CommandUiContributorAction(
                "toggle", "TOGGLE", "Toggle", CommandUiContributorAction.InputPolicy.NONE,
                false, handler);
        CommandUiActionCatalog catalog = new CommandUiActionCatalog();
        CommandUiActionCatalog.ContributorActionComposition composed =
                catalog.bindContributorActions(contributorId, 7L,
                        CommandUiContribution.withActions(contributorId,
                                Map.of(), Map.of(), Map.of(), Map.of("toggle", action),
                                Map.of(), Map.of()));
        CommandUiSnapshot base = new CommandUiSnapshot(
                sessionId, 1L, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"))
                .withContributions(Map.of(contributorId, composed.contribution()));
        CommandUiActionHandle handle = new CommandUiActionHandle("opaque");

        CommandUiSnapshot attached = CommandUiActionCatalog.attachContributorActions(
                base, List.of(new CommandUiActionCatalog.ContributorActionHandle(
                        composed.bindings().getFirst(), handle)));

        assertEquals(handle, attached.contribution(contributorId)
                .commandActions().get("runeteria:contributor/toggle").handle());
    }

    @Test
    void contributorRegistrationGenerationChangeRequiresFreshHandles() {
        UUID sessionId = UUID.randomUUID();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:contributor");
        CommandUiContributorAction action = new CommandUiContributorAction(
                "toggle", "TOGGLE", "Toggle",
                CommandUiContributorAction.InputPolicy.NONE, false,
                context -> CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()));
        CommandUiActionCatalog oldCatalog = new CommandUiActionCatalog();
        CommandUiActionCatalog.ContributorActionComposition oldComposition =
                oldCatalog.bindContributorActions(contributorId, 7L,
                        CommandUiContribution.withActions(contributorId,
                                Map.of(), Map.of(), Map.of(), Map.of("toggle", action),
                                Map.of(), Map.of()));
        CommandUiActionCatalog newCatalog = new CommandUiActionCatalog();
        CommandUiActionCatalog.ContributorActionComposition newComposition =
                newCatalog.bindContributorActions(contributorId, 8L,
                        CommandUiContribution.withActions(contributorId,
                                Map.of(), Map.of(), Map.of(), Map.of("toggle", action),
                                Map.of(), Map.of()));

        assertFalse(CommandUiActionCatalog.contributorActionsMatch(
                List.of(new CommandUiActionCatalog.ContributorActionHandle(
                        oldComposition.bindings().getFirst(),
                        new CommandUiActionHandle("old"))),
                newComposition.bindings()));
    }

    @Test
    void panelTextActionUsesItsBoundedRequestExecutor() {
        UUID sessionId = UUID.randomUUID();
        CommandUiSnapshot base = new CommandUiSnapshot(
                sessionId, 1L, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
        AtomicReference<String> filter = new AtomicReference<>();
        CommandUiActionCatalog catalog = new CommandUiActionCatalog();
        catalog.addPanel("SET_FILTER_TEXT", "Set filter text",
                new CommandSelectionPageService.GenericUiActionBinding(
                        new CommandUiAction("SET_FILTER_TEXT"), () -> true,
                        () -> CompletableFuture.completedFuture(
                                CommandUiActionResult.unavailable(
                                        "text is required")), false,
                        (ignored, input) -> {
                            filter.set(input);
                            return CompletableFuture.completedFuture(
                                    CommandUiActionResult.applied());
                        }, CommandUiActionGateway.InputPolicy.OPTIONAL_TEXT,
                        40, null));
        CommandUiSessionFactory.CreatedSession created =
                new CommandUiSessionFactory(new CommandUiActionGateway(),
                        new CommandSelectionPageService(
                                null, null, null, null, null))
                        .createGeneric(sessionId, base, 0L,
                                CommandUiWorldDispatcher.direct(), null, null,
                                null, catalog.genericBindings());
        CommandUiSnapshot exposed = catalog.attach(base, created.handles());

        CommandUiActionResult result = created.session().invoke(
                new CommandUiActionRequest(
                        exposed.panelState().action("SET_FILTER_TEXT").handle(),
                        " alpaca "))
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.APPLIED, result.status());
        assertEquals("alpaca", filter.get());
        created.session().close();
    }

    @Test
    void commandChoiceCarriesAnInvokableTameworkHandle() {
        UUID sessionId = UUID.randomUUID();
        CommandUiSnapshot base = new CommandUiSnapshot(
                sessionId, 1L, 1L, "stay",
                List.of(new CommandUiCommandOption("follow", "Follow")), List.of(),
                new CommandUiPanelState("linked"));
        AtomicReference<String> selected = new AtomicReference<>();
        AtomicInteger refreshes = new AtomicInteger();
        CommandUiActionCatalog catalog = new CommandUiActionCatalog();
        catalog.addCommand("follow", "Follow",
                new CommandSelectionPageService.GenericUiActionBinding(
                        new CommandUiAction("SELECT_COMMAND", null,
                                "follow", false),
                        () -> true,
                        () -> {
                            selected.set("follow");
                            return CompletableFuture.completedFuture(
                                    CommandUiActionResult.applied());
                        }, false));

        CommandSelectionPageService service = new CommandSelectionPageService(
                null, null, null, null, null);
        CommandUiSessionFactory.CreatedSession created =
                new CommandUiSessionFactory(new CommandUiActionGateway(), service)
                        .createMixed(sessionId, base, 0L,
                                CommandUiWorldDispatcher.direct(),
                                refreshes::incrementAndGet, null,
                                null, catalog.genericBindings(),
                                catalog.bondedBindings());
        CommandUiSnapshot exposed = catalog.attach(base, created.handles());
        created.session().publishInternal(exposed,
                com.alechilles.alecstamework.api.commandui.CommandUiChangeSet.full());

        var action = exposed.commandOptions().getFirst().action();
        assertNotNull(action);
        assertNotNull(action.handle());
        assertEquals(CommandUiActionStatus.APPLIED,
                created.session().invoke(action.handle())
                        .toCompletableFuture().join().status());
        assertEquals("follow", selected.get());
        assertEquals(1, refreshes.get());
        created.session().close();
    }

    @Test
    void presentationRefreshKeepsHandlesAndMarksOnlyTheChangedCard() {
        UUID sessionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CommandUiSnapshot previous = new CommandUiSnapshot(
                sessionId, 1L, 1L, null, List.of(),
                List.of(row(first, "One", "first-handle"),
                        row(second, "Two", "second-handle")),
                new CommandUiPanelState("linked"));
        CommandUiSnapshot fresh = new CommandUiSnapshot(
                sessionId, 2L, 1L, null, List.of(),
                List.of(row(first, "One updated", null),
                        row(second, "Two", null)),
                new CommandUiPanelState("linked"));

        CommandUiSnapshot retained = CommandUiActionCatalog.retainActions(
                fresh, previous);
        var changes = CommandUiSnapshotDiffer.diff(previous, retained);

        assertEquals("first-handle",
                retained.companionRow(first).action("LOCATE").handle().token());
        assertTrue(changes.changedCompanionIds().contains(first));
        assertFalse(changes.changedCompanionIds().contains(second));
    }

    @Test
    void externalRosterAndLifecycleChangesRequireFreshActionHandles() {
        UUID first = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        CommandUiSnapshot previous = new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(),
                List.of(row(first, "One", "first-handle")),
                new CommandUiPanelState("linked"));
        CommandUiActionCatalog same = rowCatalog(first, "LOCATE", "Locate");
        CommandUiActionCatalog changed = rowCatalog(first, "RECALL", "Recall");
        changed.addRow(added, "LOCATE", "Locate", binding("LOCATE"));

        assertTrue(same.matchesActions(previous));
        assertFalse(changed.matchesActions(previous));
    }

    @Test
    void stableProfileRowRebindsWhenLiveAliasChanges() {
        UUID rowId = UUID.randomUUID();
        UUID oldAlias = UUID.randomUUID();
        UUID newAlias = UUID.randomUUID();
        CommandUiSnapshot previous = new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(),
                List.of(row(rowId, oldAlias, "profile-7", "Companion",
                        "old-handle")), new CommandUiPanelState("linked"));
        CommandUiActionCatalog refreshed = new CommandUiActionCatalog();
        refreshed.addRow(rowId, "LOCATE", "Locate",
                new CommandSelectionPageService.GenericUiActionBinding(
                        new CommandUiAction("LOCATE", newAlias), () -> true,
                        () -> CompletableFuture.completedFuture(
                                CommandUiActionResult.accepted()), false));

        assertFalse(refreshed.matchesActions(previous));
    }

    private static CommandUiActionCatalog rowCatalog(
            UUID rowId, String kind, String label) {
        CommandUiActionCatalog catalog = new CommandUiActionCatalog();
        catalog.addRow(rowId, kind, label, binding(kind));
        return catalog;
    }

    private static CommandSelectionPageService.GenericUiActionBinding binding(
            String kind) {
        return new CommandSelectionPageService.GenericUiActionBinding(
                new CommandUiAction(kind, null, null, false), () -> true,
                () -> CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()), false);
    }

    private static CommandUiCompanionRow row(
            UUID id,
            String name,
            String token
    ) {
        return row(id, id, null, name, token);
    }

    private static CommandUiCompanionRow row(
            UUID rowId,
            UUID companionId,
            String profileId,
            String name,
            String token
    ) {
        return new CommandUiCompanionRow(
                rowId, companionId, profileId, name, null, null, null, null,
                true, true, true, true, null, null, null, null,
                token == null ? Map.of() : Map.of("LOCATE",
                        new CommandUiActionView(
                                "LOCATE", "Locate", true, null, false,
                                new CommandUiActionHandle(token))), Map.of());
    }
}
