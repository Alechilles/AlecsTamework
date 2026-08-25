package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiDiagnostics;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Diagnostics, warning-throttle, and composition-boundary behavior. */
class CommandUiDiagnosticsServiceTest {
    private static final CommandUiContributorId CONTRIBUTOR =
            CommandUiContributorId.of("example:diagnostics");

    @Test
    void slowWarningsUseStrictThresholdAndPerContributorMinuteThrottle() {
        AtomicLong clock = new AtomicLong();
        List<CommandUiContributorTimingWarnings.Warning> warnings =
                new ArrayList<>();
        CommandUiContributorTimingWarnings tracker =
                new CommandUiContributorTimingWarnings(clock::get,
                        warnings::add);

        long start = tracker.start();
        clock.set(CommandUiContributorTimingWarnings.SLOW_THRESHOLD_NANOS);
        assertFalse(tracker.finish("example:one", 1L, start).slow());
        assertTrue(warnings.isEmpty());

        clock.set(0L);
        start = tracker.start();
        clock.set(CommandUiContributorTimingWarnings.SLOW_THRESHOLD_NANOS + 1L);
        assertTrue(tracker.finish("example:one", 1L, start).warningEmitted());
        assertEquals(1, warnings.size());

        clock.set(CommandUiContributorTimingWarnings.SLOW_THRESHOLD_NANOS + 1L);
        start = tracker.start();
        clock.addAndGet(CommandUiContributorTimingWarnings.WARNING_INTERVAL_NANOS - 1L);
        assertFalse(tracker.finish("example:one", 1L, start).warningEmitted());
        assertEquals(1, warnings.size());

        clock.addAndGet(1L);
        start = tracker.start();
        clock.addAndGet(CommandUiContributorTimingWarnings.SLOW_THRESHOLD_NANOS + 1L);
        assertTrue(tracker.finish("example:one", 1L, start).warningEmitted());
        assertEquals(2, warnings.size());

        start = tracker.start();
        clock.addAndGet(CommandUiContributorTimingWarnings.SLOW_THRESHOLD_NANOS + 1L);
        assertTrue(tracker.finish("example:two", 1L, start).warningEmitted());
        assertEquals(3, warnings.size());
    }

    @Test
    void snapshotIsImmutableAndReasonsAreRedacted() {
        CommandUiDiagnosticsService service = new CommandUiDiagnosticsService();
        UUID sessionId = UUID.randomUUID();
        service.registerRenderer("example:renderer", 2L);
        service.registerContributor(CONTRIBUTOR.value(), 3L);
        service.openSession(sessionId, "example:renderer", 2L,
                "command-item", "private-config", List.of(
                        new CommandUiDiagnostics.ContributorRegistration(
                                CONTRIBUTOR.value(), 3L)));
        service.recordSessionFailure(sessionId, "private-value-token");
        CommandUiDiagnostics diagnostics = service.snapshot();

        assertEquals(1, diagnostics.renderers().size());
        assertEquals(1, diagnostics.contributors().size());
        assertEquals(1, diagnostics.activeSessionCount());
        assertEquals("callback_failed", diagnostics.latestFailureReason());
        assertEquals("private-config",
                diagnostics.sessions().getFirst().configId());
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostics.renderers().add(
                        new CommandUiDiagnostics.RendererRegistration(
                                "example:other", 1L)));
        assertFalse(diagnostics.toString().contains("private-value-token"));
        assertFalse(diagnostics.toString().contains("action-token"));
    }

    @Test
    void compositionValidatesContributionAndRecordsSlowFailure() {
        AtomicLong clock = new AtomicLong();
        List<CommandUiContributorTimingWarnings.Warning> warnings =
                new ArrayList<>();
        CommandUiDiagnosticsService service = new CommandUiDiagnosticsService(
                clock::get, warnings::add);
        UUID sessionId = UUID.randomUUID();
        CommandUiSnapshot base = baseSnapshot(sessionId);
        CommandUiCompositionSession session =
                CommandUiCompositionSession.create(
                        base,
                        new CommandUiOpenContext(),
                        List.of(new CommandUiCompositionSession.Binding(
                                CONTRIBUTOR, 3L,
                                ignored -> new CommandUiSessionContributor() {
                                    @Override
                                    public CommandUiContribution compose(
                                            CommandUiSnapshot ignoredBase,
                                            CommandUiContribution previous,
                                            com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope scope
                                    ) {
                                        clock.set(CommandUiContributorTimingWarnings
                                                .SLOW_THRESHOLD_NANOS + 1L);
                                        return CommandUiContribution.ready(
                                                CONTRIBUTOR,
                                                Map.of("private", CommandUiValue.string(
                                                        "x".repeat(
                                                                CommandUiValueBounds.MAX_CONTRIBUTION_TOTAL_CHARACTERS))),
                                                Map.of());
                                    }
                                })),
                        (snapshot, changes) -> { },
                        () -> { },
                        service,
                        4L);

        assertTrue(session.isOpen());
        assertTrue(session.snapshot().contributions().isEmpty());
        CommandUiDiagnostics diagnostics = service.snapshot();
        assertEquals(1, diagnostics.activeSessionCount());
        assertEquals("OPTIONAL_FAILED",
                diagnostics.sessions().getFirst().contributors().getFirst().status());
        assertEquals(1L, diagnostics.slowCompositionCount());
        assertEquals(1L, diagnostics.slowWarningCount());
        assertEquals(1, warnings.size());
        assertFalse(diagnostics.toString().contains("private"));
        session.close();
        assertEquals(0, service.snapshot().activeSessionCount());
    }

    @Test
    void requiredOverLimitContributionStillFailsInitialComposition() {
        CommandUiDiagnosticsService service = new CommandUiDiagnosticsService();
        UUID sessionId = UUID.randomUUID();
        CommandUiSnapshot base = baseSnapshot(sessionId);

        assertThrows(CommandUiCompositionSession.InitialCompositionFailure.class,
                () -> CommandUiCompositionSession.create(
                        base,
                        new CommandUiOpenContext(),
                        List.of(new CommandUiCompositionSession.Binding(
                                CONTRIBUTOR, 3L,
                                ignored -> new CommandUiSessionContributor() {
                                    @Override
                                    public CommandUiContribution compose(
                                            CommandUiSnapshot ignoredBase,
                                            CommandUiContribution previous,
                                            com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope scope
                                    ) {
                                        return CommandUiContribution.ready(
                                                CONTRIBUTOR,
                                                Map.of("private", CommandUiValue.string(
                                                        "x".repeat(
                                                                CommandUiValueBounds.MAX_CONTRIBUTION_TOTAL_CHARACTERS))),
                                                Map.of());
                                    }
                                },
                                true,
                                () -> true)),
                        (snapshot, changes) -> { },
                        () -> { },
                        service,
                        4L));
        assertEquals(0, service.snapshot().activeSessionCount());
        assertEquals("initial_composition_failed",
                service.snapshot().latestFailureReason());
    }

    @Test
    void optionalContributorRemovalUpdatesTheActiveSessionStatus() {
        CommandUiDiagnosticsService service = new CommandUiDiagnosticsService();
        CommandUiContributorRegistry registry =
                new CommandUiContributorRegistry();
        var registration = registry.register(CONTRIBUTOR.value(),
                ignored -> new CommandUiSessionContributor() {
                    @Override
                    public CommandUiContribution compose(
                            CommandUiSnapshot base,
                            CommandUiContribution previous,
                            com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope scope
                    ) {
                        return new CommandUiContribution(CONTRIBUTOR);
                    }
                }).registration();
        CommandUiCompositionSession session = CommandUiCompositionSession.create(
                baseSnapshot(UUID.randomUUID()), new CommandUiOpenContext(),
                List.of(new CommandUiCompositionSession.Binding(CONTRIBUTOR,
                        registration.generation(),
                        ignored -> new CommandUiSessionContributor() {
                            @Override
                            public CommandUiContribution compose(
                                    CommandUiSnapshot base,
                                    CommandUiContribution previous,
                                    com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope scope
                            ) {
                                return new CommandUiContribution(CONTRIBUTOR);
                            }
                        }, false, registry)),
                (snapshot, changes) -> { }, () -> { }, service, 4L);

        registration.close();

        CommandUiDiagnostics.ContributorView contributor = service.snapshot()
                .activeSessions().getFirst().contributors().getFirst();
        assertEquals("OPTIONAL_REMOVED", contributor.status());
        assertEquals("optional_contributor_removed",
                contributor.failureReason());
        session.close();
        registry.close();
    }

    private static CommandUiSnapshot baseSnapshot(UUID sessionId) {
        return new CommandUiSnapshot(sessionId, 1L, 1L, null,
                List.of(), List.of(), new CommandUiPanelState("linked"));
    }
}
