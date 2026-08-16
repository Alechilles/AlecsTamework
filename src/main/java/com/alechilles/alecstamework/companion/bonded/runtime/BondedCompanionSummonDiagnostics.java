package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionService;
import com.alechilles.alecstamework.items.BondedCompanionProjectionSpawnBoundary;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.RecentRespawnTraceService;
import com.alechilles.alecstamework.items.RespawnTraceLogSupport;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/** Emits one correlated health trace for a bonded companion summon. */
final class BondedCompanionSummonDiagnostics {
    private final Output output;

    BondedCompanionSummonDiagnostics() {
        this(new RespawnTraceOutput());
    }

    BondedCompanionSummonDiagnostics(Output output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    Session begin(BondedCompanionProjectionService.SpawnPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try {
            if (!output.enabled()) return new Session(null);
            var lease = plan.lease();
            var state = plan.snapshot().fullState();
            RecentRespawnTraceService.Trace trace = output.start(
                    "bonded_summon", state.npcUuid(), lease.ownerUuid(),
                    plan.roleId(), "bonded:" + lease.rosterId());
            output.log(trace, "start profile=" + lease.profileId()
                    + " roster=" + lease.rosterId()
                    + " leasePhase=" + lease.phase()
                    + " leaseStartedAtMs=" + lease.startedAtMs()
                    + " leaseExpiresAtMs=" + lease.expiresAtMs()
                    + " target=" + lease.liveNpcUuid()
                    + " world=" + lease.worldKey()
                    + " placement=" + placement(plan));
            output.snapshot(trace, "bonded_summon_planned", state);
            return new Session(trace);
        } catch (RuntimeException | LinkageError ignored) {
            return new Session(null);
        }
    }

    void warn(Session session, String stage, String reason) {
        try {
            if (!session.enabled()) return;
            output.warn(session.trace(), "failed stage=" + clean(stage)
                    + " reason=" + clean(reason));
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never change the summon result.
        }
    }

    void exception(Session session, String stage, Throwable failure) {
        try {
            if (!session.enabled()) return;
            Objects.requireNonNull(failure, "failure");
            output.warn(session.trace(), "failed stage=" + clean(stage)
                    + " reason=exception type="
                    + failure.getClass().getSimpleName());
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never change the summon result.
        }
    }

    void projection(
            Session session,
            @Nullable World world,
            UUID npcUuid,
            BondedCompanionProjectionSpawnBoundary.Outcome outcome
    ) {
        try {
            if (!session.enabled()) return;
            output.projection(
                    world, npcUuid, session.trace(), "bonded_summon",
                    outcome.name(),
                    outcome == BondedCompanionProjectionSpawnBoundary.Outcome
                            .CONFIRMED);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never change the summon result.
        }
    }

    private static String placement(
            BondedCompanionProjectionService.SpawnPlan plan) {
        var value = plan.placement();
        return value == null ? "<none>"
                : value.worldKey() + ":" + value.x() + "," + value.y()
                + "," + value.z();
    }

    private static String clean(String value) {
        return value == null || value.isBlank()
                ? "<unknown>"
                : value.replaceAll("\\s+", " ").trim();
    }

    record Session(@Nullable RecentRespawnTraceService.Trace trace) {
        boolean enabled() {
            return trace != null;
        }
    }

    interface Output {
        boolean enabled();

        RecentRespawnTraceService.Trace start(
                String branch, UUID original, UUID owner,
                String role, String tool);

        void log(RecentRespawnTraceService.Trace trace, String message);

        void warn(RecentRespawnTraceService.Trace trace, String message);

        void snapshot(
                RecentRespawnTraceService.Trace trace,
                String stage,
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot
                        snapshot);

        void projection(
                @Nullable World world, UUID npcUuid,
                RecentRespawnTraceService.Trace trace,
                String stage, String result, boolean confirmed);
    }

    private static final class RespawnTraceOutput implements Output {
        @Override
        public boolean enabled() {
            return RespawnTraceLogSupport.isEnabled();
        }

        @Override
        public RecentRespawnTraceService.Trace start(
                String branch, UUID original, UUID owner,
                String role, String tool) {
            return RespawnTraceLogSupport.startTrace(
                    branch, original, owner, role, tool);
        }

        @Override
        public void log(RecentRespawnTraceService.Trace trace,
                        String message) {
            RespawnTraceLogSupport.log(trace, message);
        }

        @Override
        public void warn(RecentRespawnTraceService.Trace trace,
                         String message) {
            RespawnTraceLogSupport.warn(trace, message);
        }

        @Override
        public void snapshot(
                RecentRespawnTraceService.Trace trace,
                String stage,
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot
                        snapshot) {
            RespawnTraceLogSupport.logSnapshot(trace, stage, snapshot);
        }

        @Override
        public void projection(
                World world, UUID npcUuid,
                RecentRespawnTraceService.Trace trace,
                String stage, String result, boolean confirmed) {
            RespawnTraceLogSupport.logProjectionResult(
                    world, npcUuid, trace, stage, result, confirmed);
        }
    }
}
