package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redacted HUD diagnostics and slow-callback throttle behavior. */
class CommandHudDiagnosticsServiceTest {
    @Test
    void diagnosticsExcludeTargetIdentityAndContributionContent() {
        AtomicLong clock = new AtomicLong();
        CommandHudDiagnosticsService service = new CommandHudDiagnosticsService(clock::get);
        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CommandHudContributorId contributorId = CommandHudContributorId.of("example:badge");
        service.openSession(sessionId, CommandHudSurface.TARGET, "example:renderer", 2L,
                "command-item", "private-config", List.of(
                        new CommandHudDiagnostics.ContributorRegistration(
                                contributorId.value(), 3L)));
        service.recordSessionFailure(sessionId,
                "secret-value=" + targetId);

        CommandHudDiagnostics diagnostics = service.snapshot();

        assertEquals("callback_failed", diagnostics.latestFailureReason());
        assertEquals(1, diagnostics.activeSessionCount());
        assertFalse(diagnostics.toString().contains(targetId.toString()));
        assertFalse(diagnostics.toString().contains("secret-value"));
        assertFalse(diagnostics.toString().contains("private-value"));
        service.close();
    }

    @Test
    void callbacksAboveTenMillisecondsCountAndWarningsThrottlePerContributor() {
        AtomicLong clock = new AtomicLong();
        List<CommandHudTimingWarnings.Warning> warnings = new ArrayList<>();
        CommandHudDiagnosticsService service = new CommandHudDiagnosticsService(
                clock::get, warnings::add);
        UUID sessionId = UUID.randomUUID();
        service.openSession(sessionId, CommandHudSurface.HOTSWAP, "example:renderer", 2L,
                null, null, List.of(new CommandHudDiagnostics.ContributorRegistration(
                        "example:badge", 3L)));

        long start = service.compositionStarted();
        clock.set(CommandHudTimingWarnings.SLOW_THRESHOLD_NANOS);
        service.compositionFinished(sessionId, "example:badge", 3L, start,
                "AVAILABLE", null);
        assertTrue(warnings.isEmpty());

        clock.set(0L);
        start = service.compositionStarted();
        clock.set(CommandHudTimingWarnings.SLOW_THRESHOLD_NANOS + 1L);
        service.compositionFinished(sessionId, "example:badge", 3L, start,
                "AVAILABLE", null);
        assertEquals(1, warnings.size());

        start = service.compositionStarted();
        clock.addAndGet(CommandHudTimingWarnings.SLOW_THRESHOLD_NANOS + 1L);
        service.compositionFinished(sessionId, "example:badge", 3L, start,
                "AVAILABLE", null);
        assertEquals(1, warnings.size());
        CommandHudDiagnostics diagnostics = service.snapshot();
        assertEquals(2L, diagnostics.slowCompositionCount());
        assertEquals(1L, diagnostics.slowWarningCount());
        service.close();
    }
}
