package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persists immutable managed-coop import evidence and narrowly scoped resolution metadata.
 */
public final class CoopImportConflictRepository {
    private static final String COLUMNS = """
            conflict_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
            conflict_kind, source_fingerprint, source_payload, resolution_state,
            created_at_ms, resolved_at_ms, resolution_note
            """;
    private static final String ORDER_BY = """
            ORDER BY world_name, x, y, z,
                     CASE WHEN resident_slot IS NULL THEN 1 ELSE 0 END,
                     resident_slot, conflict_kind, source_fingerprint, conflict_id
            """;

    public enum ResolutionState {
        UNRESOLVED,
        RESOLVED,
        IGNORED
    }

    public enum MutationStatus {
        INSERTED,
        RESOLVED,
        IDEMPOTENT,
        NOT_FOUND,
        CONFLICT
    }

    public record ConflictEvidence(@Nonnull String conflictId,
                                   @Nonnull String authorityId,
                                   @Nonnull ManagedCoopAuthorityKey authorityKey,
                                   @Nonnull String coopId,
                                   @Nullable Integer residentSlot,
                                   @Nonnull String conflictKind,
                                   @Nonnull String sourceFingerprint,
                                   @Nonnull String sourcePayload,
                                   long createdAtMs) {
        public ConflictEvidence {
            conflictId = requiredText(conflictId, "conflictId");
            authorityId = requiredText(authorityId, "authorityId");
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = requiredText(coopId, "coopId");
            conflictKind = requiredText(conflictKind, "conflictKind");
            if (sourceFingerprint == null || sourceFingerprint.isBlank()) {
                throw new IllegalArgumentException("sourceFingerprint must not be blank");
            }
            Objects.requireNonNull(sourcePayload, "sourcePayload");
            if (residentSlot != null && residentSlot < 0) {
                throw new IllegalArgumentException("residentSlot must not be negative");
            }
            if (!authorityId.equals(authorityKey.authorityId())) {
                throw new IllegalArgumentException("authorityId does not match authorityKey");
            }
        }
    }

    public record ConflictRecord(@Nonnull String conflictId,
                                 @Nonnull String authorityId,
                                 @Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId,
                                 @Nullable Integer residentSlot,
                                 @Nonnull String conflictKind,
                                 @Nonnull String sourceFingerprint,
                                 @Nonnull String sourcePayload,
                                 @Nonnull ResolutionState resolutionState,
                                 long createdAtMs,
                                 long resolvedAtMs,
                                 @Nullable String resolutionNote) {
    }

    public record MutationResult(@Nonnull MutationStatus status,
                                 @Nullable ConflictRecord record,
                                 @Nullable String detail) {
        public boolean succeeded() {
            return status == MutationStatus.INSERTED
                    || status == MutationStatus.RESOLVED
                    || status == MutationStatus.IDEMPOTENT;
        }
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CoopImportConflictRepository(@Nonnull SqliteConnectionManager connectionManager,
                                        @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    /** Inserts one immutable source record or reports an explicit identity/payload conflict. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> insert(
            @Nonnull ConflictEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return writeQueue.submitTracked(
                "coop_import_conflict_insert",
                connection -> insertInTransaction(connection, evidence),
                null
        );
    }

    /**
     * Resolves one conflict by changing only resolution state, timestamp, and note fields.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> resolve(
            @Nonnull String conflictId,
            @Nonnull ResolutionState targetState,
            @Nonnull String resolutionNote,
            long resolvedAtMs) {
        String normalizedId = requiredText(conflictId, "conflictId");
        Objects.requireNonNull(targetState, "targetState");
        String normalizedNote = requiredText(resolutionNote, "resolutionNote");
        if (targetState == ResolutionState.UNRESOLVED) {
            throw new IllegalArgumentException("targetState must resolve the conflict");
        }
        if (resolvedAtMs == 0L) {
            throw new IllegalArgumentException("resolvedAtMs must use a non-zero signed timestamp");
        }
        return writeQueue.submitTracked(
                "coop_import_conflict_resolve",
                connection -> resolveInTransaction(
                        connection, normalizedId, targetState, normalizedNote, resolvedAtMs),
                null
        );
    }

    /** Lists every unresolved record in deterministic physical-coop/source order. */
    @Nonnull
    public ManagedCoopReadResult<List<ConflictRecord>> listUnresolved() {
        try (Connection connection = connectionManager.openConnection()) {
            return ManagedCoopReadResult.loaded(queryUnresolved(connection, null, null));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    /** Lists unresolved records for one exact managed authority and coop asset. */
    @Nonnull
    public ManagedCoopReadResult<List<ConflictRecord>> listUnresolved(
            @Nonnull ManagedCoopAuthorityKey authorityKey,
            @Nonnull String coopId) {
        if (authorityKey == null || coopId == null || coopId.isBlank()) {
            return ManagedCoopReadResult.invalidInput("authority_and_coop_required");
        }
        try (Connection connection = connectionManager.openConnection()) {
            return ManagedCoopReadResult.loaded(
                    queryUnresolved(connection, authorityKey, coopId.trim()));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    @Nonnull
    MutationResult insertInTransaction(@Nonnull Connection connection,
                                       @Nonnull ConflictEvidence evidence) throws SQLException {
        ConflictRecord byId = loadById(connection, evidence.conflictId());
        if (byId != null) {
            return immutableEquals(byId, evidence)
                    ? new MutationResult(MutationStatus.IDEMPOTENT, byId, null)
                    : new MutationResult(
                            MutationStatus.CONFLICT, byId, "conflict_id_identity_or_payload_mismatch");
        }
        ConflictRecord bySource = loadBySourceIdentity(connection, evidence);
        if (bySource != null) {
            return new MutationResult(
                    MutationStatus.CONFLICT, bySource, "source_identity_already_bound");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_import_conflicts (
                    conflict_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    conflict_kind, source_fingerprint, source_payload, resolution_state,
                    created_at_ms, resolved_at_ms, resolution_note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNRESOLVED', ?, 0, NULL)
                """)) {
            bindEvidence(statement, evidence);
            if (statement.executeUpdate() != 1) {
                throw new ManagedCoopIntegrityException("coop_import_conflict_insert_count");
            }
        }
        ConflictRecord inserted = loadById(connection, evidence.conflictId());
        if (inserted == null || !immutableEquals(inserted, evidence)) {
            throw new ManagedCoopIntegrityException("coop_import_conflict_insert_verification_failed");
        }
        return new MutationResult(MutationStatus.INSERTED, inserted, null);
    }

    @Nonnull
    MutationResult resolveInTransaction(@Nonnull Connection connection,
                                        @Nonnull String conflictId,
                                        @Nonnull ResolutionState targetState,
                                        @Nonnull String resolutionNote,
                                        long resolvedAtMs) throws SQLException {
        ConflictRecord existing = loadById(connection, conflictId);
        if (existing == null) {
            return new MutationResult(MutationStatus.NOT_FOUND, null, "conflict_not_found");
        }
        if (existing.resolutionState() == targetState
                && resolutionNote.equals(existing.resolutionNote())) {
            return new MutationResult(MutationStatus.IDEMPOTENT, existing, null);
        }
        if (existing.resolutionState() != ResolutionState.UNRESOLVED) {
            return new MutationResult(
                    MutationStatus.CONFLICT, existing, "conflict_already_resolved_differently");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_import_conflicts
                SET resolution_state = ?, resolved_at_ms = ?, resolution_note = ?
                WHERE conflict_id = ? AND resolution_state = 'UNRESOLVED'
                """)) {
            statement.setString(1, targetState.name());
            statement.setLong(2, resolvedAtMs);
            statement.setString(3, resolutionNote);
            statement.setString(4, conflictId);
            if (statement.executeUpdate() != 1) {
                throw new ManagedCoopIntegrityException("coop_import_conflict_resolution_count");
            }
        }
        ConflictRecord resolved = loadById(connection, conflictId);
        if (resolved == null || !sameImmutableFields(existing, resolved)
                || resolved.resolutionState() != targetState
                || resolved.resolvedAtMs() != resolvedAtMs
                || !resolutionNote.equals(resolved.resolutionNote())) {
            throw new ManagedCoopIntegrityException("coop_import_conflict_resolution_verification_failed");
        }
        return new MutationResult(MutationStatus.RESOLVED, resolved, null);
    }

    @Nonnull
    private List<ConflictRecord> queryUnresolved(
            @Nonnull Connection connection,
            @Nullable ManagedCoopAuthorityKey authorityKey,
            @Nullable String coopId) throws SQLException {
        String where = authorityKey == null
                ? "WHERE resolution_state = 'UNRESOLVED' "
                : "WHERE resolution_state = 'UNRESOLVED' "
                        + "AND world_name = ? AND coop_id = ? "
                        + "AND x = ? AND y = ? AND z = ? ";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM coop_import_conflicts " + where + ORDER_BY)) {
            if (authorityKey != null) {
                statement.setString(1, authorityKey.worldName());
                statement.setString(2, coopId);
                statement.setInt(3, authorityKey.x());
                statement.setInt(4, authorityKey.y());
                statement.setInt(5, authorityKey.z());
            }
            ArrayList<ConflictRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
            }
            return List.copyOf(records);
        }
    }

    @Nullable
    private ConflictRecord loadById(@Nonnull Connection connection, @Nonnull String conflictId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM coop_import_conflicts WHERE conflict_id = ? LIMIT 2")) {
            statement.setString(1, conflictId);
            return loadSingle(statement, "duplicate_coop_import_conflict_id");
        }
    }

    @Nullable
    private ConflictRecord loadBySourceIdentity(@Nonnull Connection connection,
                                                @Nonnull ConflictEvidence evidence)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM coop_import_conflicts "
                        + "WHERE authority_id = ? AND conflict_kind = ? AND source_fingerprint = ? LIMIT 2")) {
            statement.setString(1, evidence.authorityId());
            statement.setString(2, evidence.conflictKind());
            statement.setString(3, evidence.sourceFingerprint());
            return loadSingle(statement, "duplicate_coop_import_source_identity");
        }
    }

    @Nullable
    private ConflictRecord loadSingle(@Nonnull PreparedStatement statement,
                                      @Nonnull String duplicateReason) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            ConflictRecord record = map(resultSet);
            if (resultSet.next()) {
                throw new ManagedCoopIntegrityException(duplicateReason);
            }
            return record;
        }
    }

    @Nonnull
    private ConflictRecord map(@Nonnull ResultSet resultSet) throws SQLException {
        String conflictId = ManagedCoopReadValidation.requireText(
                resultSet.getString("conflict_id"), "conflict_id");
        String authorityId = ManagedCoopReadValidation.requireText(
                resultSet.getString("authority_id"), "authority_id");
        String worldName = ManagedCoopReadValidation.requireText(
                resultSet.getString("world_name"), "world_name");
        String coopId = ManagedCoopReadValidation.requireText(
                resultSet.getString("coop_id"), "coop_id");
        ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                worldName, resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"));
        if (!authorityId.equals(key.authorityId())) {
            throw new ManagedCoopIntegrityException("coop_import_conflict_authority_mismatch:" + conflictId);
        }
        Integer residentSlot = nullableSlot(resultSet, conflictId);
        String conflictKind = ManagedCoopReadValidation.requireText(
                resultSet.getString("conflict_kind"), "conflict_kind");
        String fingerprint = requireRaw(resultSet.getString("source_fingerprint"),
                "source_fingerprint");
        String payload = resultSet.getString("source_payload");
        if (payload == null) {
            throw new ManagedCoopIntegrityException("missing_managed_coop_field:source_payload");
        }
        ResolutionState state = parseState(resultSet.getString("resolution_state"));
        long resolvedAtMs = resultSet.getLong("resolved_at_ms");
        String note = resultSet.getString("resolution_note");
        validateResolution(conflictId, state, resolvedAtMs, note);
        return new ConflictRecord(
                conflictId, authorityId, key, coopId, residentSlot, conflictKind,
                fingerprint, payload, state, resultSet.getLong("created_at_ms"),
                resolvedAtMs, note);
    }

    @Nullable
    private Integer nullableSlot(@Nonnull ResultSet resultSet, @Nonnull String conflictId)
            throws SQLException {
        int slot = resultSet.getInt("resident_slot");
        if (resultSet.wasNull()) {
            return null;
        }
        if (slot < 0) {
            throw new ManagedCoopIntegrityException("negative_coop_import_slot:" + conflictId);
        }
        return slot;
    }

    @Nonnull
    private ResolutionState parseState(@Nullable String value)
            throws ManagedCoopIntegrityException {
        try {
            return ResolutionState.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new ManagedCoopIntegrityException("unknown_coop_import_resolution_state:" + value, exception);
        }
    }

    private void validateResolution(String conflictId,
                                    ResolutionState state,
                                    long resolvedAtMs,
                                    @Nullable String note) throws ManagedCoopIntegrityException {
        if (state == ResolutionState.UNRESOLVED && (resolvedAtMs != 0L || note != null)) {
            throw new ManagedCoopIntegrityException("invalid_unresolved_coop_import_state:" + conflictId);
        }
        if (state != ResolutionState.UNRESOLVED
                && (resolvedAtMs == 0L || note == null || note.isBlank())) {
            throw new ManagedCoopIntegrityException("invalid_resolved_coop_import_state:" + conflictId);
        }
    }

    private void bindEvidence(PreparedStatement statement, ConflictEvidence evidence)
            throws SQLException {
        statement.setString(1, evidence.conflictId());
        statement.setString(2, evidence.authorityId());
        statement.setString(3, evidence.authorityKey().worldName());
        statement.setString(4, evidence.coopId());
        statement.setInt(5, evidence.authorityKey().x());
        statement.setInt(6, evidence.authorityKey().y());
        statement.setInt(7, evidence.authorityKey().z());
        if (evidence.residentSlot() == null) {
            statement.setNull(8, java.sql.Types.INTEGER);
        } else {
            statement.setInt(8, evidence.residentSlot());
        }
        statement.setString(9, evidence.conflictKind());
        statement.setString(10, evidence.sourceFingerprint());
        statement.setString(11, evidence.sourcePayload());
        statement.setLong(12, evidence.createdAtMs());
    }

    private boolean immutableEquals(ConflictRecord record, ConflictEvidence evidence) {
        return record.conflictId().equals(evidence.conflictId())
                && record.authorityId().equals(evidence.authorityId())
                && record.authorityKey().equals(evidence.authorityKey())
                && record.coopId().equals(evidence.coopId())
                && Objects.equals(record.residentSlot(), evidence.residentSlot())
                && record.conflictKind().equals(evidence.conflictKind())
                && record.sourceFingerprint().equals(evidence.sourceFingerprint())
                && record.sourcePayload().equals(evidence.sourcePayload());
    }

    private boolean sameImmutableFields(ConflictRecord left, ConflictRecord right) {
        return left.conflictId().equals(right.conflictId())
                && left.authorityId().equals(right.authorityId())
                && left.authorityKey().equals(right.authorityKey())
                && left.coopId().equals(right.coopId())
                && Objects.equals(left.residentSlot(), right.residentSlot())
                && left.conflictKind().equals(right.conflictKind())
                && left.sourceFingerprint().equals(right.sourceFingerprint())
                && left.sourcePayload().equals(right.sourcePayload())
                && left.createdAtMs() == right.createdAtMs();
    }

    @Nonnull
    private String requireRaw(@Nullable String value, @Nonnull String field)
            throws ManagedCoopIntegrityException {
        if (value == null || value.isBlank()) {
            throw new ManagedCoopIntegrityException("missing_managed_coop_field:" + field);
        }
        return value;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
