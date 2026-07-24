package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for bounded API 0.9 operator diagnostics. */
class TameworkIntegrationDiagnosticsServiceTest {
    @Test
    void overviewReportsEachAuthorityAndDurableRecoveryEvidence() {
        FakeSource source = new FakeSource();
        List<String> lines = new TameworkIntegrationDiagnosticsService(source).overview();

        assertTrue(lines.size() <= TameworkIntegrationDiagnosticsService.MAX_LINES);
        assertTrue(contains(lines, "API=0.9.0"));
        assertTrue(contains(lines, "capturePolicy=true"));
        assertTrue(contains(lines, "Capture attempts: prepared=2"));
        assertTrue(contains(lines, "duplicateCallbacksSinceBoot=4"));
        assertTrue(contains(lines, "commandFamilyRosters=true"));
        assertTrue(contains(lines, "timedSummoning=true"));
        assertTrue(contains(lines, "openOperations=3"));
        assertTrue(contains(lines, "classified=21"));
        assertTrue(contains(lines, "oldestCorrelation=population-op-4"));
        assertTrue(contains(lines, "configs=[hydragon:miniwyvern->HyDragon_Miniwyvern]"));
        assertTrue(contains(lines, "API events: dispatched=12"));
        assertTrue(contains(lines, "listenerFailuresSinceBoot=2"));
        assertTrue(contains(lines, "lastFailedEventType=CommandTimedSummoningChangedEvent"));
        assertTrue(contains(lines, "activeQuarantines=2"));
    }

    @Test
    void captureAttemptLookupIsBoundedCorrelatedAndRedactsIdentityAndEntropy() {
        List<String> lines = new TameworkIntegrationDiagnosticsService(new FakeSource())
                .captureAttempt("attempt-7");

        assertEquals(5, lines.size());
        assertTrue(lines.size() <= TameworkIntegrationDiagnosticsService.MAX_LINES);
        assertTrue(contains(lines, "id=attempt-7"));
        assertTrue(contains(lines, "state=QUARANTINED"));
        assertTrue(contains(lines, "spawner=hydragon:stone@12"));
        assertTrue(contains(lines, "target=hydragon:dragon-policy@8"));
        assertTrue(contains(lines, "effectiveChance=0.65"));
        assertTrue(contains(lines, "entropy=<redacted>"));
        assertTrue(contains(lines, "populationJournalOperation=population-journal-7"));
        assertTrue(contains(lines, "correlation=population-correlation-7"));
        assertTrue(contains(lines, "cooldown=active-until-5000"));
        assertTrue(contains(lines, "incident=incident-7"));
        assertFalse(contains(lines, "11111111-1111-1111-1111-111111111111"));
        assertFalse(contains(lines, "0.617283"));
    }

    @Test
    void populationReportIncludesCountsReservationsAndReconciliationEvidence() {
        List<String> lines = new TameworkIntegrationDiagnosticsService(new FakeSource()).population();

        assertEquals(4, lines.size());
        assertTrue(contains(lines, "configRevision=12"));
        assertTrue(contains(lines, "tracked=30"));
        assertTrue(contains(lines, "pendingOwnerSlots=2"));
        assertTrue(contains(lines, "owner(created=9, committed=7"));
        assertTrue(contains(lines, "coverage=[PROFILE=READY,POPULATION=READY]"));
    }

    @Test
    void missingExactLookupsReturnOneBoundedLine() {
        FakeSource source = new FakeSource();
        source.captureAttempt = null;
        TameworkIntegrationDiagnosticsService service =
                new TameworkIntegrationDiagnosticsService(source);

        assertEquals(List.of("Capture attempt not found for id 'missing'."),
                service.captureAttempt("missing"));
    }

    private static boolean contains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private static final class FakeSource implements TameworkIntegrationDiagnosticsService.Source {
        private final EnumSet<TameworkApiCapability> capabilities = EnumSet.of(
                TameworkApiCapability.COMMAND_FAMILY_ROSTERS,
                TameworkApiCapability.COMMAND_TIMED_SUMMONING,
                TameworkApiCapability.POPULATION_GROUPS);
        private TameworkIntegrationDiagnosticsService.CaptureAttemptDetail captureAttempt =
                new TameworkIntegrationDiagnosticsService.CaptureAttemptDetail(
                        "attempt-7", "QUARANTINED", "CAPTURED", "capture-apply-quarantined",
                        "RECOVERED_QUARANTINED", 1_200L,
                        "hydragon:stone", 12L, "hydragon:dragon-policy", 8L,
                        false, "HyDragon_Draconic_Stone", "Dragon_Fire", true,
                        4.0D, 2.0D, 20.0D, 100.0D, 0.8D, 0.25D, 0.65D,
                        "capture-operation-7", "population-operation-7",
                        "population-journal-7", "population-correlation-7", "profile-7",
                        "active-until-5000", true, true, true,
                        "incident-7", "OPEN", "capture-projection-failed");

        @Override public String apiVersion() { return "0.9.0"; }
        @Override public String capabilities() {
            return "[COMMAND_FAMILY_ROSTERS, COMMAND_TIMED_SUMMONING, "
                    + "POPULATION_GROUPS]";
        }
        @Override public boolean captureReady() { return true; }
        @Override public boolean hasCapability(TameworkApiCapability capability) {
            return capabilities.contains(capability);
        }
        @Override public PopulationGroupReconciliationView groupReadiness() {
            return new PopulationGroupReconciliationView(
                    PopulationGroupReconciliationView.Readiness.READY, "ready", 12L,
                    21L, 2L, 1L, 110L);
        }
        @Override public PopulationDiagnosticsView populationDiagnostics() {
            PopulationDiagnosticsView unavailable = PopulationDiagnosticsView.unavailable();
            return new PopulationDiagnosticsView(
                    new PopulationDiagnosticsView.ReadinessView("READY", "READY", "READY"),
                    new PopulationDiagnosticsView.CountView(30L, 20L, 2L, 14L, 1L, 1L, 0L),
                    new PopulationDiagnosticsView.ReservationMetricsView(9L, 7L, 1L, 1L, 0L),
                    new PopulationDiagnosticsView.ReservationMetricsView(8L, 6L, 1L, 1L, 0L),
                    unavailable.claimLookups(), PopulationDiagnosticsView.ReconciliationView.unknown());
        }
        @Override public TameworkIntegrationDiagnosticsService.GroupOperationSummary groupOperationSummary() {
            return new TameworkIntegrationDiagnosticsService.GroupOperationSummary(
                    3L, 1L, "population-op-4");
        }
        @Override public TameworkIntegrationDiagnosticsService.GroupConfigSummary groupConfigSummary() {
            return new TameworkIntegrationDiagnosticsService.GroupConfigSummary(
                    "[hydragon:miniwyvern->HyDragon_Miniwyvern]", 0L);
        }
        @Override public TameworkIntegrationDiagnosticsService.PersistenceSummary persistenceSummary() {
            return new TameworkIntegrationDiagnosticsService.PersistenceSummary(
                    "HEALTHY", null, 1, 2, "[PROFILE=READY,POPULATION=READY]");
        }
        @Override public TameworkIntegrationDiagnosticsService.CaptureSummary captureSummary() {
            return new TameworkIntegrationDiagnosticsService.CaptureSummary(2L, 3L, 1L, 1L, 2L, 4L);
        }
        @Override public TameworkIntegrationDiagnosticsService.EventDeliverySummary eventDeliverySummary() {
            return new TameworkIntegrationDiagnosticsService.EventDeliverySummary(
                    12L, 9L, 7L, 2L, "CommandTimedSummoningChangedEvent");
        }
        @Override public TameworkIntegrationDiagnosticsService.CaptureAttemptDetail findCaptureAttempt(
                String ignoredAttemptId) {
            return captureAttempt;
        }
    }
}
