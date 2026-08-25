package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorCreateContext;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session-scoped contributor composition and dirty-sink behavior. */
class CommandUiCompositionSessionTest {
    @Test
    void composesContributorsAndPublishesOnlyChangedContributionRows() {
        UUID sessionId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:test");
        AtomicInteger composeCount = new AtomicInteger();
        AtomicReference<CommandUiSnapshot> published = new AtomicReference<>();
        AtomicReference<CommandUiChangeSet> changes = new AtomicReference<>();
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        CommandUiContributorProvider provider = context -> {
            sink.set(context.dirtySink());
            return new CommandUiSessionContributor() {
                @Override
                public CommandUiContribution compose(
                        CommandUiSnapshot base,
                        CommandUiContribution previous
                ) {
                    boolean state = composeCount.incrementAndGet() > 1;
                    return new CommandUiContribution(
                            contributorId,
                            Map.of("ready", CommandUiValue.of(state)),
                            Map.of(rowId, Map.of("ready", CommandUiValue.of(state))));
                }
            };
        };
        CommandUiSnapshot base = new CommandUiSnapshot(
                sessionId, 1L, 1L, null, List.of(), List.of(),
                new com.alechilles.alecstamework.api.commandui.CommandUiPanelState(
                        "linked"));

        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                base, new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(
                        contributorId, 1L, provider)),
                (snapshot, update) -> {
                    published.set(snapshot);
                    changes.set(update);
                }, () -> { });

        CommandUiSnapshot initial = session.snapshot();
        assertEquals(false, initial.contribution(contributorId)
                .pageValue("ready").booleanValue());
        assertEquals(1, composeCount.get());

        sink.get().markRowsDirty(Set.of(rowId));
        session.refresh();

        assertEquals(true, published.get().contribution(contributorId)
                .rowValue(rowId, "ready").booleanValue());
        assertTrue(!changes.get().fullRefresh());
        assertEquals(Set.of(rowId), changes.get().changedContributorRowIds()
                .get(contributorId));
        session.close();
    }

    @Test
    void closedSessionMakesDirtySignalsNoOpsAndClosesContributorsInReverseOrder() {
        CommandUiContributorId firstId = CommandUiContributorId.of("example:first");
        CommandUiContributorId secondId = CommandUiContributorId.of("example:second");
        List<String> closed = new java.util.ArrayList<>();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        CommandUiSessionContributor first = contributor(firstId, closed, null);
        CommandUiSessionContributor second = contributor(secondId, closed,
                context -> sink.set(context.dirtySink()));
        CommandUiSnapshot base = new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(), List.of(),
                new com.alechilles.alecstamework.api.commandui.CommandUiPanelState(
                        "linked"));

        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                base, new CommandUiOpenContext(),
                List.of(
                        new CommandUiCompositionSession.Binding(firstId, 1L,
                                context -> first),
                        new CommandUiCompositionSession.Binding(secondId, 1L,
                                context -> {
                                    sink.set(context.dirtySink());
                                    return second;
                                })),
                (snapshot, update) -> { }, refreshes::incrementAndGet);
        session.close();
        sink.get().markAllDirty();
        session.refresh();

        assertEquals(List.of("second", "first"), closed);
        assertEquals(0, refreshes.get());
    }

    private static CommandUiSessionContributor contributor(
            CommandUiContributorId id,
            List<String> closed,
            java.util.function.Consumer<CommandUiContributorCreateContext> created
    ) {
        return new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(CommandUiSnapshot base,
                                                  CommandUiContribution previous) {
                return new CommandUiContribution(id);
            }

            @Override
            public void close() {
                closed.add(id.name());
            }
        };
    }
}
