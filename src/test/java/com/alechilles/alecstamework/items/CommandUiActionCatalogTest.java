package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiCommandOption;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
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
        return new CommandUiCompanionRow(
                id, id, null, name, null, null, null, null,
                true, true, true, true, null, null, null, null,
                token == null ? Map.of() : Map.of("LOCATE",
                        new CommandUiActionView(
                                "LOCATE", "Locate", true, null, false,
                                new CommandUiActionHandle(token))), Map.of());
    }
}
