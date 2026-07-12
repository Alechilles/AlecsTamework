package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.ProfileOwnerMutation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Forked-process writer that halts immediately after one durable journal boundary commits. */
final class CompanionPopulationCrashBoundaryChild {
    static final int HALT_EXIT_CODE = 73;
    static final String PROFILE_ID = "process-boundary-profile";
    static final UUID NPC_UUID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    static final UUID OLD_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000902");
    static final UUID NEW_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000903");
    static final String WORLD = "alpha";

    private CompanionPopulationCrashBoundaryChild() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected journal state and SQLite path.");
        }
        CompanionPopulationOperationRecord.State boundary =
                CompanionPopulationOperationRecord.State.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        CompanionPopulationOperationRecoveryTestSupport.Harness harness =
                CompanionPopulationOperationRecoveryTestSupport.open(
                        database.getParent(), database.getFileName().toString()
                );
        CompanionPopulationOperationRecoveryTestSupport.insertScenario(
                harness,
                PROFILE_ID,
                NPC_UUID,
                OLD_OWNER,
                NEW_OWNER,
                CompanionLifecycleState.ACTIVE,
                CompanionLifecycleState.ACTIVE,
                WORLD,
                WORLD,
                OwnerPopulationOperation.OWNER_TRANSFER,
                CompanionPopulationOperationRecord.State.PREPARED,
                false
        );
        seedReadyCoverage(harness);
        advanceToBoundary(harness, boundary);
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    private static void advanceToBoundary(
            CompanionPopulationOperationRecoveryTestSupport.Harness harness,
            CompanionPopulationOperationRecord.State boundary
    ) throws Exception {
        if (boundary == CompanionPopulationOperationRecord.State.PREPARED) {
            return;
        }
        requireCommitted(harness.repository().advanceOperationAsync(
                "operation",
                CompanionPopulationOperationRecord.State.PREPARED,
                CompanionPopulationOperationRecord.State.APPLYING,
                null
        ));
        if (boundary == CompanionPopulationOperationRecord.State.APPLYING) {
            return;
        }
        if (boundary == CompanionPopulationOperationRecord.State.COMPENSATING) {
            requireCommitted(harness.repository().advanceOperationAsync(
                    "operation",
                    CompanionPopulationOperationRecord.State.APPLYING,
                    CompanionPopulationOperationRecord.State.COMPENSATING,
                    "process-boundary-compensation"
            ));
            return;
        }
        if (boundary != CompanionPopulationOperationRecord.State.APPLIED) {
            throw new IllegalArgumentException("Unsupported crash boundary: " + boundary);
        }
        CompanionPopulationOperationRecoveryTestSupport.updateTargetContext(
                harness, appliedTargetContext()
        );
        PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> committed =
                harness.repository().commitAsync(new PopulationPersistenceTransition.Commit(
                        "operation",
                        PROFILE_ID,
                        0L,
                        ProfileOwnerMutation.set(NEW_OWNER),
                        NPC_UUID,
                        WORLD,
                        CompanionLifecycleState.ACTIVE.name(),
                        WORLD,
                        0,
                        0,
                        "process-boundary-applied"
                )).completion().get(5L, TimeUnit.SECONDS);
        if (!committed.isCommitted()
                || committed.value() == null
                || committed.value().status()
                != PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING) {
            throw new IllegalStateException("Unable to persist APPLIED journal boundary.");
        }
    }

    private static String appliedTargetContext() {
        JsonObject context = JsonParser.parseString(
                CompanionSpawnSourceFinalizationContext.extensionJson(
                        CompanionSpawnSourceFinalizationContext.Kind.DEATH_RECORD,
                        "process-boundary-source",
                        NPC_UUID,
                        OLD_OWNER,
                        null,
                        "expected",
                        "replacement"
                )
        ).getAsJsonObject();
        context.addProperty("npcUuid", NPC_UUID.toString());
        context.addProperty("world", WORLD);
        context.addProperty("chunkX", 0);
        context.addProperty("chunkZ", 0);
        return context.toString();
    }

    private static void seedReadyCoverage(
            CompanionPopulationOperationRecoveryTestSupport.Harness harness
    ) throws Exception {
        CompanionPopulationCoverageRepository coverage = new CompanionPopulationCoverageRepository(
                harness.connections(), harness.queue()
        );
        EnumSet<CompanionPopulationCoverageRecord.Dimension> dimensions = EnumSet.of(
                CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER,
                CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE,
                CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS,
                CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS
        );
        for (CompanionPopulationCoverageRecord.Dimension dimension : dimensions) {
            long now = System.currentTimeMillis();
            PersistenceWriteQueue.WriteOutcome<Void> outcome = coverage.upsertAsync(
                    new CompanionPopulationCoverageRecord(
                            "process-boundary:" + dimension.name().toLowerCase(Locale.ROOT),
                            dimension,
                            "test",
                            "process-boundary-generation",
                            CompanionPopulationCoverageRecord.State.READY,
                            "{}",
                            1L,
                            1L,
                            now,
                            now,
                            now,
                            null
                    )
            ).completion().get(5L, TimeUnit.SECONDS);
            if (!outcome.isCommitted()) {
                throw new IllegalStateException("Unable to persist coverage for " + dimension);
            }
        }
    }

    private static void requireCommitted(
            PersistenceWriteQueue.WriteSubmission<Boolean> submission
    ) throws Exception {
        PersistenceWriteQueue.WriteOutcome<Boolean> outcome =
                submission.completion().get(5L, TimeUnit.SECONDS);
        if (!outcome.isCommitted() || !Boolean.TRUE.equals(outcome.value())) {
            throw new IllegalStateException("Unable to persist journal boundary transition.");
        }
    }
}
