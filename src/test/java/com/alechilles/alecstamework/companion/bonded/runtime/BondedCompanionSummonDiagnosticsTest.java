package com.alechilles.alecstamework.companion.bonded.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.BondedCompanionProjectionSpawnBoundary;
import com.alechilles.alecstamework.items.RecentRespawnTraceService;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protects the support evidence emitted for bonded summon failures. */
class BondedCompanionSummonDiagnosticsTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");

    @Test
    void recordsPlannedHealthAndCorrelatesConfirmedProjection() {
        RecordingOutput output = new RecordingOutput();
        BondedCompanionSummonDiagnostics diagnostics =
                new BondedCompanionSummonDiagnostics(output);
        BondedCompanionProjectionService.SpawnPlan plan = plan();

        BondedCompanionSummonDiagnostics.Session session =
                diagnostics.begin(plan);
        diagnostics.projection(
                session, null, TARGET,
                BondedCompanionProjectionSpawnBoundary.Outcome.CONFIRMED);

        assertEquals("bonded_summon", output.branch);
        assertEquals(OWNER, output.owner);
        assertEquals("Dragon", output.role);
        assertEquals("bonded:hydragon:roster", output.tool);
        assertTrue(output.message.contains("profile=profile-a"));
        assertTrue(output.message.contains("target=" + TARGET));
        assertEquals("bonded_summon_planned", output.snapshotStage);
        assertEquals(400.0D, output.snapshot.currentHealth());
        assertSame(session.trace(), output.projectionTrace);
        assertEquals("bonded_summon", output.projectionStage);
        assertEquals("CONFIRMED", output.projectionResult);
        assertTrue(output.confirmed);
    }

    @Test
    void recordsExceptionTypeWithoutRawMessage() {
        RecordingOutput output = new RecordingOutput();
        BondedCompanionSummonDiagnostics diagnostics =
                new BondedCompanionSummonDiagnostics(output);
        BondedCompanionSummonDiagnostics.Session session =
                diagnostics.begin(plan());

        diagnostics.exception(
                session, "world_gateway",
                new IllegalStateException("broken\nprojection"));

        assertTrue(output.warning.contains("stage=world_gateway"));
        assertTrue(output.warning.contains("type=IllegalStateException"));
        assertFalse(output.warning.contains("broken projection"));
    }

    @Test
    void diagnosticFailuresCannotEscapeIntoSummonBehavior() {
        BondedCompanionSummonDiagnostics diagnostics =
                new BondedCompanionSummonDiagnostics(new FailingOutput());

        BondedCompanionSummonDiagnostics.Session session = assertDoesNotThrow(
                () -> diagnostics.begin(plan()));
        assertFalse(session.enabled());

        RecentRespawnTraceService.Trace trace = new RecentRespawnTraceService.Trace(
                "trace-failure", "bonded_summon", SOURCE, TARGET, OWNER,
                "Dragon", "bonded:hydragon:roster", 1L, 1L, null);
        session = new BondedCompanionSummonDiagnostics.Session(trace);
        BondedCompanionSummonDiagnostics.Session activeSession = session;
        assertDoesNotThrow(() -> diagnostics.warn(
                activeSession, "world_lookup", "world_unavailable"));
        assertDoesNotThrow(() -> diagnostics.exception(
                activeSession, "world_gateway", new LinkageError("broken")));
        assertDoesNotThrow(() -> diagnostics.projection(
                activeSession, null, TARGET,
                BondedCompanionProjectionSpawnBoundary.Outcome.CONFIRMED));
    }

    private BondedCompanionProjectionService.SpawnPlan plan() {
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                OWNER, "hydragon:roster", "profile-a", "lease-a", TARGET,
                "world-a", 1L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.PENDING);
        var state = new CoopResidentStateSnapshotService
                .CoopResidentStateSnapshot(
                SOURCE, null, -1, "Dragon", null, null, null, null,
                null, null, null, null, null, null, null, null,
                400.0D, 400.0D, 100.0D, 1L);
        return new BondedCompanionProjectionService.SpawnPlan(
                lease, "Dragon", BondedCompanionSnapshot.of(state, Map.of()),
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-a", "lease-a"),
                new CompanionSpawnPlacement(
                        "world-a", 1.0D, 2.0D, 3.0D, 0.0F, 0.0F, 0.0F),
                null);
    }

    private static final class RecordingOutput
            implements BondedCompanionSummonDiagnostics.Output {
        private String branch;
        private UUID owner;
        private String role;
        private String tool;
        private String message;
        private String warning;
        private String snapshotStage;
        private CoopResidentStateSnapshotService.CoopResidentStateSnapshot
                snapshot;
        private RecentRespawnTraceService.Trace projectionTrace;
        private String projectionStage;
        private String projectionResult;
        private boolean confirmed;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public RecentRespawnTraceService.Trace start(
                String branch, UUID original, UUID owner,
                String role, String tool) {
            this.branch = branch;
            this.owner = owner;
            this.role = role;
            this.tool = tool;
            return new RecentRespawnTraceService.Trace(
                    "trace-a", branch, original, null, owner, role, tool,
                    1L, 0L, null);
        }

        @Override
        public void log(RecentRespawnTraceService.Trace trace,
                        String message) {
            this.message = message;
        }

        @Override
        public void warn(RecentRespawnTraceService.Trace trace,
                         String message) {
            this.warning = message;
        }

        @Override
        public void snapshot(
                RecentRespawnTraceService.Trace trace,
                String stage,
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot
                        snapshot) {
            this.snapshotStage = stage;
            this.snapshot = snapshot;
        }

        @Override
        public void projection(
                World world, UUID npcUuid,
                RecentRespawnTraceService.Trace trace,
                String stage, String result, boolean confirmed) {
            this.projectionTrace = trace;
            this.projectionStage = stage;
            this.projectionResult = result;
            this.confirmed = confirmed;
        }
    }

    private static final class FailingOutput
            implements BondedCompanionSummonDiagnostics.Output {
        @Override
        public boolean enabled() {
            throw new LinkageError("enabled failed");
        }

        @Override
        public RecentRespawnTraceService.Trace start(
                String branch, UUID original, UUID owner,
                String role, String tool) {
            throw new AssertionError("start must not run");
        }

        @Override
        public void log(RecentRespawnTraceService.Trace trace,
                        String message) {
            throw new LinkageError("log failed");
        }

        @Override
        public void warn(RecentRespawnTraceService.Trace trace,
                         String message) {
            throw new LinkageError("warn failed");
        }

        @Override
        public void snapshot(
                RecentRespawnTraceService.Trace trace,
                String stage,
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot
                        snapshot) {
            throw new LinkageError("snapshot failed");
        }

        @Override
        public void projection(
                World world, UUID npcUuid,
                RecentRespawnTraceService.Trace trace,
                String stage, String result, boolean confirmed) {
            throw new LinkageError("projection failed");
        }
    }
}
