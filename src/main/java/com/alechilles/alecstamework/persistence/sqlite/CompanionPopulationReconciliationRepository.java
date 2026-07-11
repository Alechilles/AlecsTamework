package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setInteger;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setText;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setUuid;

/**
 * Atomically persists reconciliation evidence and the cursor that proves it was scanned.
 */
public final class CompanionPopulationReconciliationRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CompanionPopulationReconciliationRepository(
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull PersistenceWriteQueue writeQueue
    ) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public ResumePoint resumePoint(@Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor)
            throws Exception {
        Objects.requireNonNull(descriptor, "descriptor");
        try (Connection connection = connectionManager.openConnection()) {
            CoverageRow row = findCoverage(connection, descriptor.coverageKey());
            if (row == null || !descriptor.scanGeneration().equals(row.scanGeneration())) {
                return new ResumePoint(0L, false, false);
            }
            long offset = cursorOffset(row.cursorJson());
            boolean complete = row.state() == CompanionPopulationCoverageRecord.State.READY
                    && offset == descriptor.estimatedTotal();
            return new ResumePoint(offset, complete, row.state() == CompanionPopulationCoverageRecord.State.DEGRADED);
        }
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<StageResult> stageAsync(
            @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
            @Nonnull CompanionPopulationEvidenceSource.Batch batch,
            long expectedOffset
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(batch, "batch");
        if (expectedOffset < 0L || batch.nextOffset() < expectedOffset) {
            throw new IllegalArgumentException("Reconciliation cursor moved backwards.");
        }
        return writeQueue.submitTracked(
                "companion_population_reconciliation_stage",
                connection -> stageInTransaction(connection, descriptor, batch, expectedOffset),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Void> markFailureAsync(
            @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
            long offset,
            @Nonnull String reason
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        String normalizedReason = requireText(reason, "reason");
        return writeQueue.submitTracked(
                "companion_population_reconciliation_failure",
                connection -> {
                    upsertCoverage(
                            connection,
                            descriptor,
                            CompanionPopulationCoverageRecord.State.DEGRADED,
                            offset,
                            0L,
                            normalizedReason
                    );
                    return null;
                },
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Void> upsertCoverageAsync(
            @Nonnull CompanionPopulationCoverageRecord coverage
    ) {
        Objects.requireNonNull(coverage, "coverage");
        return writeQueue.submitTracked(
                "companion_population_reconciliation_coverage",
                connection -> {
                    upsertCoverageRecord(connection, coverage);
                    return null;
                },
                null
        );
    }

    /** Removes stale source rows so a deleted world/provider cannot poison a newer sealed catalog. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Void> pruneInactiveSourcesAsync(
            @Nonnull Set<String> activeCoverageKeys
    ) {
        Set<String> active = Set.copyOf(Objects.requireNonNull(activeCoverageKeys, "activeCoverageKeys"));
        return writeQueue.submitTracked(
                "companion_population_reconciliation_prune",
                connection -> {
                    List<String> stale = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT coverage_key
                            FROM companion_population_reconciliation
                            WHERE coverage_dimension IN (
                                'WORLD_ENTITIES', 'PLAYER_SAVES',
                                'BASE_CONTAINER_BLOCKS', 'CUSTOM_CONTAINERS'
                            )
                            """
                    ); ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            String key = resultSet.getString(1);
                            if (!active.contains(key)) {
                                stale.add(key);
                            }
                        }
                    }
                    for (String key : stale) {
                        deleteEvidence(connection, key);
                        try (PreparedStatement statement = connection.prepareStatement(
                                "DELETE FROM companion_population_reconciliation WHERE coverage_key = ?"
                        )) {
                            statement.setString(1, key);
                            statement.executeUpdate();
                        }
                    }
                    return null;
                },
                null
        );
    }

    @Nonnull
    public List<CompanionPopulationEvidence> loadEvidence(
            @Nonnull List<CompanionPopulationEvidenceSource.Descriptor> descriptors
    ) throws Exception {
        List<CompanionPopulationEvidenceSource.Descriptor> snapshot = List.copyOf(descriptors);
        try (Connection connection = connectionManager.openConnection()) {
            List<CompanionPopulationEvidence> evidence = new ArrayList<>();
            for (CompanionPopulationEvidenceSource.Descriptor descriptor : snapshot) {
                loadEvidence(connection, descriptor, evidence);
            }
            return List.copyOf(evidence);
        }
    }

    private StageResult stageInTransaction(
            @Nonnull Connection connection,
            @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
            @Nonnull CompanionPopulationEvidenceSource.Batch batch,
            long expectedOffset
    ) throws Exception {
        CoverageRow existing = findCoverage(connection, descriptor.coverageKey());
        if (existing != null && !descriptor.scanGeneration().equals(existing.scanGeneration())) {
            deleteEvidence(connection, descriptor.coverageKey());
            existing = null;
        }
        long durableOffset = existing == null ? 0L : cursorOffset(existing.cursorJson());
        if (durableOffset != expectedOffset) {
            return new StageResult(false, durableOffset, "reconciliation-cursor-conflict");
        }
        if (batch.nextOffset() > descriptor.estimatedTotal()) {
            throw new IllegalArgumentException("Reconciliation batch exceeds the source snapshot.");
        }
        for (CompanionPopulationEvidence evidence : batch.evidence()) {
            upsertEvidence(connection, descriptor, evidence);
        }
        CompanionPopulationCoverageRecord.State state = batch.complete()
                ? CompanionPopulationCoverageRecord.State.READY
                : CompanionPopulationCoverageRecord.State.RECONCILING;
        if (batch.complete() && batch.nextOffset() != descriptor.estimatedTotal()) {
            throw new IllegalArgumentException("A complete reconciliation batch must end at estimatedTotal.");
        }
        upsertCoverage(connection, descriptor, state, batch.nextOffset(), expectedOffset, null);
        return new StageResult(true, batch.nextOffset(), null);
    }

    private void upsertEvidence(@Nonnull Connection connection,
                                @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
                                @Nonnull CompanionPopulationEvidence evidence) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_reconciliation_evidence (
                    coverage_key, scan_generation, evidence_key, npc_uuid, owner_uuid,
                    evidence_kind, ownership_world_name, physical_world_name,
                    physical_chunk_x, physical_chunk_z, source, observed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(coverage_key, scan_generation, evidence_key) DO UPDATE SET
                    npc_uuid = excluded.npc_uuid,
                    owner_uuid = excluded.owner_uuid,
                    evidence_kind = excluded.evidence_kind,
                    ownership_world_name = excluded.ownership_world_name,
                    physical_world_name = excluded.physical_world_name,
                    physical_chunk_x = excluded.physical_chunk_x,
                    physical_chunk_z = excluded.physical_chunk_z,
                    source = excluded.source,
                    observed_at_ms = excluded.observed_at_ms
                """
        )) {
            statement.setString(1, descriptor.coverageKey());
            statement.setString(2, descriptor.scanGeneration());
            statement.setString(3, evidence.evidenceKey());
            setUuid(statement, 4, evidence.npcUuid());
            setUuid(statement, 5, evidence.ownerUuid());
            statement.setString(6, evidence.kind().name());
            setText(statement, 7, evidence.ownershipWorldName());
            setText(statement, 8, evidence.physicalWorldName());
            setInteger(statement, 9, evidence.physicalChunkX());
            setInteger(statement, 10, evidence.physicalChunkZ());
            statement.setString(11, evidence.source());
            statement.setLong(12, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void upsertCoverage(@Nonnull Connection connection,
                                @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
                                @Nonnull CompanionPopulationCoverageRecord.State state,
                                long offset,
                                long priorOffset,
                                @Nullable String error) throws Exception {
        long now = System.currentTimeMillis();
        CoverageRow existing = findCoverage(connection, descriptor.coverageKey());
        long startedAt = existing != null
                && descriptor.scanGeneration().equals(existing.scanGeneration())
                ? existing.startedAtMs()
                : now;
        long completedAt = state == CompanionPopulationCoverageRecord.State.READY ? now : 0L;
        upsertCoverageRecord(connection, new CompanionPopulationCoverageRecord(
                descriptor.coverageKey(),
                descriptor.dimension(),
                descriptor.worldOrSaveId(),
                descriptor.scanGeneration(),
                state,
                cursorJson(offset),
                offset,
                descriptor.estimatedTotal(),
                startedAt,
                now,
                completedAt,
                error
        ));
    }

    private void upsertCoverageRecord(@Nonnull Connection connection,
                                      @Nonnull CompanionPopulationCoverageRecord coverage) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_reconciliation (
                    coverage_key, coverage_dimension, world_or_save_id, scan_generation,
                    state, cursor_json, scanned_count, estimated_total,
                    started_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(coverage_key) DO UPDATE SET
                    coverage_dimension = excluded.coverage_dimension,
                    world_or_save_id = excluded.world_or_save_id,
                    scan_generation = excluded.scan_generation,
                    state = excluded.state,
                    cursor_json = excluded.cursor_json,
                    scanned_count = excluded.scanned_count,
                    estimated_total = excluded.estimated_total,
                    started_at_ms = excluded.started_at_ms,
                    updated_at_ms = excluded.updated_at_ms,
                    completed_at_ms = excluded.completed_at_ms,
                    last_error = excluded.last_error
                """
        )) {
            statement.setString(1, coverage.coverageKey());
            statement.setString(2, coverage.dimension().name());
            setText(statement, 3, coverage.worldOrSaveId());
            statement.setString(4, coverage.scanGeneration());
            statement.setString(5, coverage.state().name());
            setText(statement, 6, coverage.cursorJson());
            statement.setLong(7, coverage.scannedCount());
            statement.setLong(8, coverage.estimatedTotal());
            statement.setLong(9, coverage.startedAtMs());
            statement.setLong(10, coverage.updatedAtMs());
            statement.setLong(11, coverage.completedAtMs());
            setText(statement, 12, coverage.lastError());
            statement.executeUpdate();
        }
    }

    @Nullable
    private CoverageRow findCoverage(@Nonnull Connection connection, @Nonnull String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT scan_generation, state, cursor_json, started_at_ms
                FROM companion_population_reconciliation
                WHERE coverage_key = ?
                """
        )) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CoverageRow(
                        resultSet.getString("scan_generation"),
                        CompanionPopulationCoverageRecord.State.valueOf(resultSet.getString("state")),
                        resultSet.getString("cursor_json"),
                        resultSet.getLong("started_at_ms")
                );
            }
        }
    }

    private void loadEvidence(@Nonnull Connection connection,
                              @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
                              @Nonnull List<CompanionPopulationEvidence> target) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT evidence_key, npc_uuid, owner_uuid, evidence_kind,
                       ownership_world_name, physical_world_name,
                       physical_chunk_x, physical_chunk_z, source
                FROM companion_population_reconciliation_evidence
                WHERE coverage_key = ? AND scan_generation = ?
                ORDER BY evidence_key
                """
        )) {
            statement.setString(1, descriptor.coverageKey());
            statement.setString(2, descriptor.scanGeneration());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    target.add(new CompanionPopulationEvidence(
                            resultSet.getString("evidence_key"),
                            java.util.UUID.fromString(resultSet.getString("npc_uuid")),
                            CompanionPopulationSqlSupport.parseUuid(resultSet.getString("owner_uuid")),
                            CompanionPopulationEvidence.Kind.valueOf(resultSet.getString("evidence_kind")),
                            resultSet.getString("ownership_world_name"),
                            resultSet.getString("physical_world_name"),
                            nullableInteger(resultSet, "physical_chunk_x"),
                            nullableInteger(resultSet, "physical_chunk_z"),
                            resultSet.getString("source")
                    ));
                }
            }
        }
    }

    private void deleteEvidence(@Nonnull Connection connection, @Nonnull String coverageKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM companion_population_reconciliation_evidence WHERE coverage_key = ?"
        )) {
            statement.setString(1, coverageKey);
            statement.executeUpdate();
        }
    }

    private static long cursorOffset(@Nullable String cursorJson) {
        if (cursorJson == null || cursorJson.isBlank()) {
            return 0L;
        }
        JsonObject cursor = JsonParser.parseString(cursorJson).getAsJsonObject();
        long offset = cursor.get("offset").getAsLong();
        if (offset < 0L) {
            throw new IllegalStateException("Persisted reconciliation cursor is negative.");
        }
        return offset;
    }

    @Nonnull
    private static String cursorJson(long offset) {
        JsonObject cursor = new JsonObject();
        cursor.addProperty("offset", offset);
        return cursor.toString();
    }

    @Nullable
    private static Integer nullableInteger(@Nonnull ResultSet resultSet, @Nonnull String field)
            throws Exception {
        int value = resultSet.getInt(field);
        return resultSet.wasNull() ? null : value;
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    public record ResumePoint(long offset, boolean complete, boolean degraded) {
    }

    public record StageResult(boolean committed, long durableOffset, @Nullable String reason) {
    }

    private record CoverageRow(@Nonnull String scanGeneration,
                               @Nonnull CompanionPopulationCoverageRecord.State state,
                               @Nullable String cursorJson,
                               long startedAtMs) {
    }
}
