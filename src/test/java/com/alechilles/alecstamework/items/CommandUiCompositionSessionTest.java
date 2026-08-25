package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorCreateContext;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session-scoped contributor composition and dirty-sink behavior. */
class CommandUiCompositionSessionTest {
    @Test
    void composesInConfiguredOrderWithoutSharingContributorNamespaces() {
        CommandUiContributorId firstId = CommandUiContributorId.of(
                "example:first");
        CommandUiContributorId secondId = CommandUiContributorId.of(
                "example:second");
        List<String> order = new ArrayList<>();
        AtomicReference<CommandUiContribution> secondPrevious =
                new AtomicReference<>();
        AtomicReference<Boolean> secondSawFirst = new AtomicReference<>();

        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot().withContributions(Map.of(firstId,
                        new CommandUiContribution(firstId))),
                new CommandUiOpenContext(), List.of(
                        new CommandUiCompositionSession.Binding(firstId, 1L,
                                ignored -> orderedContributor(firstId, order,
                                        "first")),
                        new CommandUiCompositionSession.Binding(secondId, 1L,
                                ignored -> new CommandUiSessionContributor() {
                                    @Override
                                    public CommandUiContribution compose(
                                            CommandUiSnapshot base,
                                            CommandUiContribution previous,
                                            CommandUiDirtyScope scope
                                    ) {
                                        order.add("second");
                                        secondPrevious.set(previous);
                                        secondSawFirst.set(base.contribution(firstId)
                                                != null);
                                        return new CommandUiContribution(secondId,
                                                Map.of("source", CommandUiValue.of(
                                                        "second")), Map.of());
                                    }
                                })),
                (snapshot, changes) -> { }, () -> { });

        assertEquals(List.of("first", "second"), order);
        assertNull(secondPrevious.get());
        assertEquals(Boolean.FALSE, secondSawFirst.get());
        assertEquals(Set.of(firstId, secondId),
                session.snapshot().contributions().keySet());
        assertEquals("first", session.snapshot().contribution(firstId)
                .pageValue("source").stringValue());
        assertEquals("second", session.snapshot().contribution(secondId)
                .pageValue("source").stringValue());
        session.close();
    }

    @Test
    void optionalRefreshFailureRetainsPresentationAndInvalidatesActionsUntilRecovery() {
        CommandUiContributorId id = CommandUiContributorId.of("example:optional");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        List<CommandUiSnapshot> published = new ArrayList<>();
        CommandUiContributorAction action = action("toggle");
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(), List.of(
                        new CommandUiCompositionSession.Binding(id, 1L,
                                context -> {
                                    sink.set(context.dirtySink());
                                    return new CommandUiSessionContributor() {
                                        @Override
                                        public CommandUiContribution compose(
                                                CommandUiSnapshot base,
                                                CommandUiContribution previous,
                                                CommandUiDirtyScope scope
                                        ) {
                                            int call = calls.incrementAndGet();
                                            if (call == 2) {
                                                throw new IllegalStateException(
                                                        "transient");
                                            }
                                            return CommandUiContribution.withActions(
                                                    id,
                                                    Map.of("state", CommandUiValue.of(
                                                            call == 1 ? "initial"
                                                                    : "recovered")),
                                                    Map.of(), Map.of(),
                                                    Map.of("toggle", action),
                                                    Map.of(), Map.of());
                                        }
                                    };
                                })),
                (snapshot, changes) -> published.add(snapshot), () -> { });

        assertEquals(1, session.actionBindings().size());
        sink.get().markPageDirty();
        assertTrue(session.refresh());

        CommandUiContribution failed = session.snapshot().contribution(id);
        assertNotNull(failed);
        assertEquals("initial", failed.pageValue("state").stringValue());
        assertEquals(CommandUiContribution.Status.OPTIONAL_FAILED,
                failed.status());
        assertTrue(failed.commandActions().isEmpty());
        assertTrue(session.actionBindings().isEmpty());

        sink.get().markPageDirty();
        assertTrue(session.refresh());

        CommandUiContribution recovered = session.snapshot().contribution(id);
        assertNotNull(recovered);
        assertEquals("recovered", recovered.pageValue("state").stringValue());
        assertEquals(CommandUiContribution.Status.READY, recovered.status());
        assertEquals(1, session.actionBindings().size());
        assertEquals(2, published.size());
        session.close();
    }

    @Test
    void optionalUnregisterRemovesItsNamespaceAndStaleCloseCannotAffectReplacement() {
        CommandUiContributorId id = CommandUiContributorId.of("example:optional");
        CommandUiContributorRegistry registry = new CommandUiContributorRegistry();
        CommandUiContributorProvider provider = ignored -> new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous,
                    CommandUiDirtyScope scope
            ) {
                return new CommandUiContribution(id,
                        Map.of("state", CommandUiValue.of("ready")), Map.of());
            }
        };
        var firstRegistration = registry.register(id.value(), provider).registration();
        CommandUiCompositionSession first = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(), List.of(
                        new CommandUiCompositionSession.Binding(id,
                                firstRegistration.generation(), provider, false,
                                registry)),
                (snapshot, changes) -> { }, () -> { });

        firstRegistration.close();
        assertTrue(first.refresh());
        assertNull(first.snapshot().contribution(id));
        assertTrue(first.actionBindings().isEmpty());

        var replacementRegistration = registry.register(id.value(), provider)
                .registration();
        CommandUiCompositionSession replacement = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(), List.of(
                        new CommandUiCompositionSession.Binding(id,
                                replacementRegistration.generation(), provider,
                                false, registry)),
                (snapshot, changes) -> { }, () -> { });
        firstRegistration.close();
        assertTrue(replacement.isOpen());
        assertNotNull(replacement.snapshot().contribution(id));

        replacementRegistration.close();
        first.close();
        replacement.close();
        registry.close();
    }

    @Test
    void requiredRefreshFailureClosesTheSessionAndReportsExactlyOnce() {
        CommandUiContributorId id = CommandUiContributorId.of("example:required");
        AtomicReference<CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<CommandUiCompositionSession.RequiredFailure> detail =
                new AtomicReference<>();
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(), List.of(
                        new CommandUiCompositionSession.Binding(id, 1L,
                                context -> {
                                    sink.set(context.dirtySink());
                                    return new CommandUiSessionContributor() {
                                        @Override
                                        public CommandUiContribution compose(
                                                CommandUiSnapshot base,
                                                CommandUiContribution previous,
                                                CommandUiDirtyScope scope
                                        ) {
                                            if (calls.getAndIncrement() > 0) {
                                                throw new IllegalStateException(
                                                        "required failure");
                                            }
                                            return new CommandUiContribution(id);
                                        }
                                    };
                                }, true, () -> true)),
                (snapshot, changes) -> { }, () -> { },
                (failedId, reason) -> {
                    failures.incrementAndGet();
                    detail.set(new CommandUiCompositionSession.RequiredFailure(
                            failedId, reason));
                });

        sink.get().markPageDirty();
        assertFalse(session.refresh());
        assertFalse(session.isOpen());
        assertEquals(1, failures.get());
        assertEquals(id, detail.get().contributorId());
        assertEquals(detail.get(), session.requiredFailure());
        session.close();
        assertEquals(1, failures.get());
    }

    @Test
    void requiredUnregisterClosesTheSessionAndReportsExactlyOnce() {
        CommandUiContributorId id = CommandUiContributorId.of("example:required");
        CommandUiContributorRegistry registry = new CommandUiContributorRegistry();
        CommandUiContributorProvider provider = ignored -> new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous,
                    CommandUiDirtyScope scope
            ) {
                return new CommandUiContribution(id);
            }
        };
        var registration = registry.register(id.value(), provider).registration();
        AtomicInteger failures = new AtomicInteger();
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(), List.of(
                        new CommandUiCompositionSession.Binding(id,
                                registration.generation(), provider, true,
                                registry)),
                (snapshot, changes) -> { }, () -> { },
                (failedId, reason) -> failures.incrementAndGet());

        registration.close();
        assertFalse(session.isOpen());
        assertEquals(1, failures.get());
        session.close();
        assertEquals(1, failures.get());
        registry.close();
    }

    @Test
    void publishesCompatibilityStatusWithoutCreatingAContributor() {
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:unsupported");

        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(), List.of(),
                Map.of(contributorId,
                        CommandUiContribution.Status.UNSUPPORTED_BY_RENDERER),
                (snapshot, changes) -> { }, () -> { });

        CommandUiContribution contribution = session.snapshot()
                .contribution(contributorId);
        assertNotNull(contribution);
        assertEquals(CommandUiContribution.Status.UNSUPPORTED_BY_RENDERER,
                contribution.status());
        assertTrue(contribution.pageData().isEmpty());
        assertTrue(contribution.pageActions().isEmpty());
        session.close();
    }

    @Test
    void rejectsContributorSuppliedDetachedActionHandles() {
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:test");
        CommandUiActionView forged = new CommandUiActionView(
                "FORGED", "Forged", true, null, false,
                new CommandUiActionHandle("contributor-token"));

        assertThrows(CommandUiCompositionSession.InitialCompositionFailure.class,
                () -> CommandUiCompositionSession.create(
                        baseSnapshot(), new CommandUiOpenContext(),
                        List.of(new CommandUiCompositionSession.Binding(
                                contributorId, 42L, context ->
                                new CommandUiSessionContributor() {
                                    @Override
                                    public CommandUiContribution compose(
                                            CommandUiSnapshot base,
                                            CommandUiContribution previous,
                                            CommandUiDirtyScope scope
                                    ) {
                                        return new CommandUiContribution(
                                                contributorId, Map.of(), Map.of(),
                                                Map.of("forged", forged), Map.of(),
                                                Map.of(), Map.of());
                                    }
                                }, true, () -> true)),
                        (snapshot, changes) -> { }, () -> { }));
    }

    @Test
    void convertsContributorDefinitionsToDetachedViewsAndRetainsServerBinding() {
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:test");
        CommandUiContributorActionHandler handler = context ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied());
        CommandUiContributorAction action = new CommandUiContributorAction(
                "toggle", "TOGGLE_READY", "Toggle ready", null,
                true, true, null, CommandUiContributorAction.InputPolicy.NONE,
                false, Map.of(), handler);
        AtomicReference<CommandUiContributorCreateContext> created =
                new AtomicReference<>();

        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(
                        contributorId, 42L, context -> {
                            created.set(context);
                            return new CommandUiSessionContributor() {
                            @Override
                            public CommandUiContribution compose(
                                    CommandUiSnapshot base,
                                    CommandUiContribution previous,
                                    CommandUiDirtyScope scope
                            ) {
                                return CommandUiContribution.withActions(
                                        contributorId, Map.of(), Map.of(),
                                        Map.of(), Map.of("toggle", action),
                                        Map.of(), Map.of());
                            }
                            };
                        })),
                (snapshot, changes) -> { }, () -> { });

        CommandUiContribution contribution = session.snapshot()
                .contribution(contributorId);
        assertNotNull(contribution);
        CommandUiActionView view = contribution.commandActions()
                .get("runeteria:test/toggle");
        assertNotNull(view);
        assertNull(view.handle());
        assertEquals("Toggle ready", view.label());

        CommandUiContributorActionBinding binding = session.actionBindings()
                .getFirst();
        assertSame(handler, binding.handler());
        assertEquals(contributorId, binding.contributorId());
        assertEquals(42L, binding.contributorGeneration());
        assertEquals(42L, created.get().registrationGeneration());
        session.close();
    }

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
                        CommandUiContribution previous,
                        CommandUiDirtyScope scope
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
                                                  CommandUiContribution previous,
                                                  CommandUiDirtyScope scope) {
                return new CommandUiContribution(id);
            }

            @Override
            public void close() {
                closed.add(id.name());
            }
        };
    }

    private static CommandUiSessionContributor orderedContributor(
            CommandUiContributorId id,
            List<String> order,
            String value
    ) {
        return new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous,
                    CommandUiDirtyScope scope
            ) {
                order.add(id.name());
                return new CommandUiContribution(id,
                        Map.of("source", CommandUiValue.of(value)), Map.of());
            }
        };
    }

    private static CommandUiContributorAction action(String localId) {
        CommandUiContributorActionHandler handler = context ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied());
        return new CommandUiContributorAction(
                localId, localId.toUpperCase(), localId, null, true, true, null,
                CommandUiContributorAction.InputPolicy.NONE, false, Map.of(),
                handler);
    }

    @Test
    void requiredFactoryFailureAbortsAndClosesEarlierContributors() {
        List<String> closed = new java.util.ArrayList<>();
        CommandUiContributorId firstId = CommandUiContributorId.of("example:first");
        CommandUiContributorId requiredId = CommandUiContributorId.of("example:required");

        assertThrows(RuntimeException.class, () -> CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(
                        new CommandUiCompositionSession.Binding(firstId, 1L,
                                context -> contributor(firstId, closed, null)),
                        new CommandUiCompositionSession.Binding(requiredId, 1L,
                                context -> { throw new IllegalStateException("factory"); },
                                true, () -> true)),
                (snapshot, changes) -> { }, () -> { }));

        assertEquals(List.of("first"), closed);
    }

    @Test
    void requiredFirstComposeFailureAbortsAndClosesCreatedContributors() {
        List<String> closed = new java.util.ArrayList<>();
        CommandUiContributorId firstId = CommandUiContributorId.of("example:first");
        CommandUiContributorId requiredId = CommandUiContributorId.of("example:required");

        assertThrows(RuntimeException.class, () -> CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(
                        new CommandUiCompositionSession.Binding(firstId, 1L,
                                context -> contributor(firstId, closed, null)),
                        new CommandUiCompositionSession.Binding(requiredId, 1L,
                                context -> throwingContributor(requiredId, closed),
                                true, () -> true)),
                (snapshot, changes) -> { }, () -> { }));

        assertEquals(List.of("required", "first"), closed);
    }

    @Test
    void optionalInitialComposeFailureOmitsItsNamespace() {
        CommandUiContributorId optionalId = CommandUiContributorId.of("example:optional");

        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(optionalId, 1L,
                        context -> throwingContributor(optionalId,
                                new java.util.ArrayList<>()))),
                (snapshot, changes) -> { }, () -> { });

        assertNull(session.snapshot().contribution(optionalId));
        assertTrue(session.snapshot().contributions().isEmpty());
        session.close();
    }

    @Test
    void generationLossBeforeInitialComposeAbortsRequiredComposition() {
        List<String> closed = new java.util.ArrayList<>();
        CommandUiContributorId firstId = CommandUiContributorId.of("example:first");
        CommandUiContributorId requiredId = CommandUiContributorId.of("example:required");

        assertThrows(RuntimeException.class, () -> CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(
                        new CommandUiCompositionSession.Binding(firstId, 1L,
                                context -> contributor(firstId, closed, null)),
                        new CommandUiCompositionSession.Binding(requiredId, 1L,
                                context -> contributor(requiredId, closed, null),
                                true, () -> false)),
                (snapshot, changes) -> { }, () -> { }));

        assertEquals(List.of("required", "first"), closed);
    }

    @Test
    void contributorReceivesOnlyTheDirtyRowScope() {
        UUID rowId = UUID.randomUUID();
        CommandUiContributorId id = CommandUiContributorId.of("example:rows");
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        AtomicReference<CommandUiDirtyScope> delivered = new AtomicReference<>();
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(id, 1L,
                        context -> {
                            sink.set(context.dirtySink());
                            return new CommandUiSessionContributor() {
                                @Override
                                public CommandUiContribution compose(
                                        CommandUiSnapshot base,
                                        CommandUiContribution previous,
                                        CommandUiDirtyScope scope) {
                                    if (previous != null) delivered.set(scope);
                                    return new CommandUiContribution(id);
                                }
                            };
                        })),
                (snapshot, changes) -> { }, () -> { });

        sink.get().markRowsDirty(Set.of(rowId));
        session.refresh();

        assertEquals(Set.of(rowId), delivered.get().rowIds());
        assertFalse(delivered.get().all());
        assertFalse(delivered.get().page());
        session.close();
    }

    @Test
    void dirtyScopeOverflowCollapsesToAllWithoutRetainingInputEntries() {
        CommandUiContributorId id = CommandUiContributorId.of("example:overflow");
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        AtomicReference<CommandUiDirtyScope> delivered = new AtomicReference<>();
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(id, 1L,
                        context -> {
                            sink.set(context.dirtySink());
                            return new CommandUiSessionContributor() {
                                @Override
                                public CommandUiContribution compose(
                                        CommandUiSnapshot base,
                                        CommandUiContribution previous,
                                        CommandUiDirtyScope scope) {
                                    if (previous != null) delivered.set(scope);
                                    return new CommandUiContribution(id);
                                }
                            };
                        })),
                (snapshot, changes) -> { }, () -> { });
        Set<String> paths = new java.util.HashSet<>();
        for (int index = 0; index < 257; index++) paths.add("path." + index);

        sink.get().markPathsDirty(paths);
        session.refresh();

        assertTrue(delivered.get().all());
        assertTrue(delivered.get().page());
        assertTrue(delivered.get().paths().isEmpty());
        assertTrue(delivered.get().rowIds().isEmpty());
        session.close();
    }

    @Test
    void failedRefreshKeepsPriorValidContributionForRetry() {
        CommandUiContributorId id = CommandUiContributorId.of("example:retry");
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink> sink =
                new AtomicReference<>();
        AtomicReference<CommandUiContribution> retryPrevious = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CommandUiContribution initialContribution = new CommandUiContribution(id);
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(), new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(id, 1L,
                        context -> {
                            sink.set(context.dirtySink());
                            return new CommandUiSessionContributor() {
                                @Override
                                public CommandUiContribution compose(
                                        CommandUiSnapshot base,
                                        CommandUiContribution previous,
                                        CommandUiDirtyScope scope) {
                                    int call = calls.incrementAndGet();
                                    if (call == 1) return initialContribution;
                                    if (call == 2) return null;
                                    retryPrevious.set(previous);
                                    return new CommandUiContribution(id);
                                }
                            };
                        })),
                (snapshot, changes) -> { }, () -> { });

        sink.get().markPageDirty();
        session.refresh();
        sink.get().markPageDirty();
        session.refresh();

        assertSame(initialContribution, retryPrevious.get());
        session.close();
    }

    private static CommandUiSnapshot baseSnapshot() {
        return new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(), List.of(),
                new com.alechilles.alecstamework.api.commandui.CommandUiPanelState(
                        "linked"));
    }

    private static CommandUiSessionContributor throwingContributor(
            CommandUiContributorId id,
            List<String> closed
    ) {
        return new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous,
                    CommandUiDirtyScope scope
            ) {
                throw new IllegalStateException("compose");
            }

            @Override
            public void close() {
                closed.add(id.name());
            }
        };
    }
}
