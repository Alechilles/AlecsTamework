package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.api.BondedVesselReadinessView;
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
        assertTrue(contains(lines, "bindings=7"));
        assertTrue(contains(lines, "openOperations=3"));
        assertTrue(contains(lines, "classified=21"));
        assertTrue(contains(lines, "oldestCorrelation=population-op-4"));
        assertTrue(contains(lines, "configs=[hydragon:miniwyvern->HyDragon_Miniwyvern]"));
        assertTrue(contains(lines, "Provisioning: readiness=READY"));
        assertTrue(contains(lines, "activeQuarantines=2"));
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
    void vesselLookupIsThreeLinesAndDoesNotExposeRawEvidence() {
        List<String> lines = new TameworkIntegrationDiagnosticsService(new FakeSource())
                .vessel("binding-a");

        assertEquals(3, lines.size());
        assertTrue(contains(lines, "binding=binding-a"));
        assertTrue(contains(lines, "generation=5"));
        assertTrue(contains(lines, "itemEvidence=present"));
        assertTrue(contains(lines, "populationOperation=population-op-4"));
        assertFalse(contains(lines, "raw-inventory-json"));
    }

    @Test
    void provisioningLookupShowsDurablePhasesAndSanitizesFields() {
        FakeSource source = new FakeSource();
        source.provisioning = new TameworkIntegrationDiagnosticsService.ProvisioningDetail(
                "provision-op", "hydragon\nforged", "soul-bond-7", "correlation-7",
                "ACTIVE", "PARTIAL_DORMANT", "RECOVERED_PARTIAL", "provisional-7",
                "profile-7", "HyDragon_Miniwyvern", 900L, 12L,
                List.of("hydragon:miniwyvern", "hydragon:companion"),
                "dormant-pop-7", "active-pop-7", "PARTIAL_DORMANT",
                "projection-runtime-unavailable", 950L, 960L);

        List<String> lines = new TameworkIntegrationDiagnosticsService(source)
                .provisioning("hydragon", "soul-bond-7");

        assertEquals(3, lines.size());
        assertTrue(contains(lines, "origin=hydragon forged/soul-bond-7"));
        assertTrue(contains(lines, "state=PARTIAL_DORMANT"));
        assertTrue(contains(lines, "canonical=profile-7"));
        assertTrue(contains(lines, "classificationRevision=12"));
        assertTrue(contains(lines, "dormantPopulation=dormant-pop-7"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("\n")));
    }

    @Test
    void missingExactLookupsReturnOneBoundedLine() {
        FakeSource source = new FakeSource();
        source.vessel = null;
        source.provisioning = null;
        TameworkIntegrationDiagnosticsService service =
                new TameworkIntegrationDiagnosticsService(source);

        assertEquals(List.of("Bonded vessel not found for binding/profile 'missing'."),
                service.vessel("missing"));
        assertEquals(List.of("Provisioning operation not found for origin 'hydragon/missing'."),
                service.provisioning("hydragon", "missing"));
    }

    private static boolean contains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private static final class FakeSource implements TameworkIntegrationDiagnosticsService.Source {
        private final EnumSet<TameworkApiCapability> capabilities = EnumSet.of(
                TameworkApiCapability.BONDED_VESSELS,
                TameworkApiCapability.POPULATION_GROUPS,
                TameworkApiCapability.COMPANION_PROVISIONING);
        private TameworkIntegrationDiagnosticsService.VesselDetail vessel =
                new TameworkIntegrationDiagnosticsService.VesselDetail(
                        "binding-a", "profile-a", "ACTIVE", 5L, 8L,
                        "hydragon:draconic-stone", 4L, "PRESENT", "HyDragon_Draconic_Stone",
                        true, 105L, false, null, "vessel-op-4", "SUMMON", "APPLYING",
                        "population-op-4", "correlation-4", "RECOVERING");
        private TameworkIntegrationDiagnosticsService.ProvisioningDetail provisioning =
                new TameworkIntegrationDiagnosticsService.ProvisioningDetail(
                        "provision-op", "hydragon", "soul-bond-7", "correlation-7",
                        "ACTIVE", "PARTIAL_DORMANT", "RECOVERED_PARTIAL", "provisional-7",
                        "profile-7", "HyDragon_Miniwyvern", 900L, 12L,
                        List.of("hydragon:miniwyvern"), "dormant-pop-7", "active-pop-7",
                        "PARTIAL_DORMANT", "projection-runtime-unavailable", 950L, 960L);

        @Override public String apiVersion() { return "0.9.0"; }
        @Override public String capabilities() {
            return "[BONDED_VESSELS, COMPANION_PROVISIONING, POPULATION_GROUPS]";
        }
        @Override public boolean captureReady() { return true; }
        @Override public boolean hasCapability(TameworkApiCapability capability) {
            return capabilities.contains(capability);
        }
        @Override public BondedVesselReadinessView vesselReadiness() {
            return new BondedVesselReadinessView(
                    BondedVesselReadinessView.Readiness.READY, "ready", 7L, 3L, 1L, 100L);
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
        @Override public TameworkIntegrationDiagnosticsService.ProvisioningSummary provisioningSummary() {
            return new TameworkIntegrationDiagnosticsService.ProvisioningSummary(true, 4L, 1L, 120L);
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
        @Override public TameworkIntegrationDiagnosticsService.VesselDetail findVessel(String ignored) {
            return vessel;
        }
        @Override public TameworkIntegrationDiagnosticsService.ProvisioningDetail findProvisioning(
                String ignoredNamespace, String ignoredKey) {
            return provisioning;
        }
    }
}
