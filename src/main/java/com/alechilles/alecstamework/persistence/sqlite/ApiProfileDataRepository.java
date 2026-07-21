package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stores profile-scoped extension data and durable revision-fenced mutation outcomes. */
public final class ApiProfileDataRepository {
    private static final String RESERVED_NAMESPACE = "Alechilles:Tamework";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public ApiProfileDataRepository(@Nonnull SqliteConnectionManager connectionManager,
                                    @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nullable
    public String get(@Nullable String profileId, @Nullable String namespace, @Nullable String key) {
        VersionedValue value = getVersioned(profileId, namespace, key);
        return value == null ? null : value.jsonPayload();
    }

    @Nullable
    public VersionedValue getVersioned(@Nullable String profileId,
                                       @Nullable String namespace,
                                       @Nullable String key) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        if (normalizedProfileId == null || normalizedNamespace == null || normalizedKey == null) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection()) {
            return findValue(connection, normalizedProfileId, normalizedNamespace, normalizedKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    public LinkedHashMap<String, String> list(@Nullable String profileId, @Nullable String namespace) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        if (normalizedProfileId == null || normalizedNamespace == null) {
            return values;
        }
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT data_key, json_payload
                     FROM api_profile_data
                     WHERE profile_id = ? AND namespace = ?
                     ORDER BY data_key
                     """
             )) {
            statement.setString(1, normalizedProfileId);
            statement.setString(2, normalizedNamespace);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String key = normalizeKey(rs.getString("data_key"));
                    String payload = rs.getString("json_payload");
                    if (key != null && payload != null && !payload.isBlank()) {
                        values.put(key, payload);
                    }
                }
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return values;
    }

    public boolean putAsync(@Nullable String profileId,
                            @Nullable String namespace,
                            @Nullable String key,
                            @Nullable String jsonPayload) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        String normalizedPayload = canonicalPayload(jsonPayload);
        if (normalizedProfileId == null
                || normalizedNamespace == null
                || normalizedKey == null
                || normalizedPayload == null
                || !profileExists(normalizedProfileId)) {
            return false;
        }
        return writeQueue.submit(
                "api_profile_data_put",
                connection -> putInTransaction(connection, normalizedProfileId, normalizedNamespace,
                        normalizedKey, normalizedPayload)
        );
    }

    public boolean deleteAsync(@Nullable String profileId,
                               @Nullable String namespace,
                               @Nullable String key) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        if (normalizedProfileId == null
                || normalizedNamespace == null
                || normalizedKey == null
                || !profileExists(normalizedProfileId)) {
            return false;
        }
        return writeQueue.submit(
                "api_profile_data_delete",
                connection -> deleteInTransaction(connection, normalizedProfileId,
                        normalizedNamespace, normalizedKey)
        );
    }

    /** Atomically commits or durably denies one exact revision-fenced mutation. */
    @Nonnull
    public CompletionStage<TransactionResult> compareAndSetAsync(
            @Nonnull String profileId,
            @Nonnull String namespace,
            @Nonnull String key,
            long expectedRevision,
            @Nonnull String idempotencyKey,
            @Nonnull String jsonPayload
    ) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeOperationNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        String normalizedIdempotencyKey = normalizeKey(idempotencyKey);
        String normalizedPayload = canonicalPayload(jsonPayload);
        if (normalizedProfileId == null || normalizedNamespace == null || normalizedKey == null
                || normalizedIdempotencyKey == null || normalizedPayload == null
                || expectedRevision < 0L || expectedRevision == Long.MAX_VALUE) {
            return CompletableFuture.completedFuture(TransactionResult.unavailable("invalid-request"));
        }
        String fingerprint = sha256(normalizedPayload);
        PersistenceWriteQueue.WriteSubmission<TransactionResult> submission = writeQueue.submitTracked(
                "api_profile_data_compare_and_set",
                connection -> compareAndSetInTransaction(
                        connection,
                        normalizedProfileId,
                        normalizedNamespace,
                        normalizedKey,
                        expectedRevision,
                        normalizedIdempotencyKey,
                        normalizedPayload,
                        fingerprint
                ),
                null
        );
        return submission.completion().thenApply(outcome -> {
            if (outcome.isCommitted() && outcome.value() != null) {
                return outcome.value();
            }
            String reason = outcome.failureReason();
            return TransactionResult.unavailable(
                    reason == null || reason.isBlank() ? "persistence-write-failed" : reason);
        });
    }

    @Nullable
    public TransactionOperation findOperation(@Nullable String namespace,
                                              @Nullable String idempotencyKey) {
        String normalizedNamespace = normalizeOperationNamespace(namespace);
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedNamespace == null || normalizedKey == null) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection()) {
            return findOperation(connection, normalizedNamespace, normalizedKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    void putInTransaction(@Nonnull Connection connection,
                          @Nonnull String profileId,
                          @Nonnull String namespace,
                          @Nonnull String key,
                          @Nonnull String jsonPayload) throws Exception {
        if (!profileExistsInTransaction(connection, profileId)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO api_profile_data (
                    profile_id, namespace, data_key, json_payload,
                    created_at_ms, updated_at_ms, revision
                ) VALUES (?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(profile_id, namespace, data_key) DO UPDATE SET
                    json_payload = excluded.json_payload,
                    updated_at_ms = excluded.updated_at_ms,
                    revision = api_profile_data.revision + 1
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            statement.setString(3, key);
            statement.setString(4, jsonPayload);
            statement.setLong(5, nowMs);
            statement.setLong(6, nowMs);
            statement.executeUpdate();
        }
    }

    void deleteInTransaction(@Nonnull Connection connection,
                             @Nonnull String profileId,
                             @Nonnull String namespace,
                             @Nonnull String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM api_profile_data WHERE profile_id = ? AND namespace = ? AND data_key = ?"
        )) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }

    private TransactionResult compareAndSetInTransaction(
            Connection connection,
            String profileId,
            String namespace,
            String key,
            long expectedRevision,
            String idempotencyKey,
            String jsonPayload,
            String fingerprint
    ) throws Exception {
        TransactionOperation existing = findOperation(connection, namespace, idempotencyKey);
        if (existing != null) {
            if (!existing.matches(profileId, key, expectedRevision, fingerprint)) {
                TransactionOperation quarantined = quarantineOperation(
                        connection, existing, "idempotency-key-conflict");
                return new TransactionResult(TransactionOutcome.QUARANTINED,
                        quarantined.reason(), quarantined, null);
            }
            if (!existing.terminal()) {
                TransactionOperation quarantined = quarantineOperation(
                        connection, existing, "incomplete-operation-recovered");
                return new TransactionResult(TransactionOutcome.QUARANTINED,
                        quarantined.reason(), quarantined, null);
            }
            return resultFrom(existing);
        }

        long nowMs = System.currentTimeMillis();
        UUID operationId = UUID.randomUUID();
        if (RESERVED_NAMESPACE.equalsIgnoreCase(namespace)) {
            TransactionOperation denied = insertTerminalOperation(connection, operationId, namespace,
                    idempotencyKey, profileId, key, expectedRevision, fingerprint,
                    TransactionStatus.TERMINAL_DENIED, "reserved-namespace", null, null, nowMs);
            return new TransactionResult(TransactionOutcome.TERMINAL_DENIED,
                    denied.reason(), denied, null);
        }
        if (!profileExistsInTransaction(connection, profileId)) {
            TransactionOperation denied = insertTerminalOperation(connection, operationId, namespace,
                    idempotencyKey, profileId, key, expectedRevision, fingerprint,
                    TransactionStatus.TERMINAL_DENIED, "profile-not-found", null, null, nowMs);
            return new TransactionResult(TransactionOutcome.TERMINAL_DENIED,
                    denied.reason(), denied, null);
        }

        VersionedValue current = findValue(connection, profileId, namespace, key);
        long currentRevision = current == null ? 0L : current.revision();
        if (currentRevision != expectedRevision) {
            TransactionOperation denied = insertTerminalOperation(connection, operationId, namespace,
                    idempotencyKey, profileId, key, expectedRevision, fingerprint,
                    TransactionStatus.TERMINAL_DENIED, "revision-mismatch", null, null, nowMs);
            return new TransactionResult(TransactionOutcome.TERMINAL_DENIED,
                    denied.reason(), denied, null);
        }

        long resultingRevision = expectedRevision + 1L;
        upsertExactRevision(connection, profileId, namespace, key, jsonPayload,
                expectedRevision, resultingRevision, nowMs);
        VersionedValue committedValue = new VersionedValue(
                profileId, namespace, key, resultingRevision, jsonPayload, nowMs);
        TransactionOperation committed = insertTerminalOperation(connection, operationId, namespace,
                idempotencyKey, profileId, key, expectedRevision, fingerprint,
                TransactionStatus.COMMITTED, "committed", resultingRevision, committedValue, nowMs);
        return new TransactionResult(TransactionOutcome.COMMITTED,
                committed.reason(), committed, committedValue);
    }

    private void upsertExactRevision(Connection connection,
                                     String profileId,
                                     String namespace,
                                     String key,
                                     String payload,
                                     long expectedRevision,
                                     long resultingRevision,
                                     long nowMs) throws Exception {
        if (expectedRevision == 0L) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO api_profile_data (
                        profile_id, namespace, data_key, json_payload,
                        created_at_ms, updated_at_ms, revision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, profileId);
                statement.setString(2, namespace);
                statement.setString(3, key);
                statement.setString(4, payload);
                statement.setLong(5, nowMs);
                statement.setLong(6, nowMs);
                statement.setLong(7, resultingRevision);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("profile-data-create-did-not-write");
                }
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE api_profile_data
                SET json_payload = ?, updated_at_ms = ?, revision = ?
                WHERE profile_id = ? AND namespace = ? AND data_key = ? AND revision = ?
                """)) {
            statement.setString(1, payload);
            statement.setLong(2, nowMs);
            statement.setLong(3, resultingRevision);
            statement.setString(4, profileId);
            statement.setString(5, namespace);
            statement.setString(6, key);
            statement.setLong(7, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("profile-data-revision-changed");
            }
        }
    }

    private TransactionOperation insertTerminalOperation(
            Connection connection,
            UUID operationId,
            String namespace,
            String idempotencyKey,
            String profileId,
            String key,
            long expectedRevision,
            String fingerprint,
            TransactionStatus status,
            String reason,
            @Nullable Long resultingRevision,
            @Nullable VersionedValue resultValue,
            long nowMs
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO api_profile_data_operations (
                    operation_id, namespace, idempotency_key, profile_id, data_key,
                    expected_revision, resulting_revision, payload_fingerprint,
                    result_json_payload, result_updated_at_ms, status, reason,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, namespace);
            statement.setString(3, idempotencyKey);
            statement.setString(4, profileId);
            statement.setString(5, key);
            statement.setLong(6, expectedRevision);
            if (resultingRevision == null) statement.setNull(7, java.sql.Types.BIGINT);
            else statement.setLong(7, resultingRevision);
            statement.setString(8, fingerprint);
            if (resultValue == null) {
                statement.setNull(9, java.sql.Types.VARCHAR);
                statement.setNull(10, java.sql.Types.BIGINT);
            } else {
                statement.setString(9, resultValue.jsonPayload());
                statement.setLong(10, resultValue.updatedAtMs());
            }
            statement.setString(11, status.name());
            statement.setString(12, reason);
            statement.setLong(13, nowMs);
            statement.setLong(14, nowMs);
            statement.executeUpdate();
        }
        return Objects.requireNonNull(findOperation(connection, namespace, idempotencyKey));
    }

    private TransactionOperation quarantineOperation(Connection connection,
                                                     TransactionOperation operation,
                                                     String reason) throws Exception {
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE api_profile_data_operations
                SET status = 'QUARANTINED', reason = ?, resulting_revision = NULL,
                    result_json_payload = NULL, result_updated_at_ms = NULL, updated_at_ms = ?
                WHERE operation_id = ?
                """)) {
            statement.setString(1, reason);
            statement.setLong(2, nowMs);
            statement.setString(3, operation.operationId().toString());
            statement.executeUpdate();
        }
        return Objects.requireNonNull(findOperation(
                connection, operation.namespace(), operation.idempotencyKey()));
    }

    private TransactionResult resultFrom(TransactionOperation operation) {
        return switch (operation.status()) {
            case COMMITTED -> {
                VersionedValue value = new VersionedValue(
                        operation.profileId(), operation.namespace(), operation.key(),
                        operation.resultingRevision(), operation.resultJsonPayload(),
                        operation.resultUpdatedAtMs());
                yield new TransactionResult(TransactionOutcome.COMMITTED,
                        operation.reason(), operation, value);
            }
            case TERMINAL_DENIED -> new TransactionResult(TransactionOutcome.TERMINAL_DENIED,
                    operation.reason(), operation, null);
            case QUARANTINED -> new TransactionResult(TransactionOutcome.QUARANTINED,
                    operation.reason(), operation, null);
            default -> TransactionResult.unavailable("operation-nonterminal");
        };
    }

    @Nullable
    private VersionedValue findValue(Connection connection,
                                     String profileId,
                                     String namespace,
                                     String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT json_payload, revision, updated_at_ms
                FROM api_profile_data
                WHERE profile_id = ? AND namespace = ? AND data_key = ?
                LIMIT 1
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            statement.setString(3, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new VersionedValue(profileId, namespace, key,
                        rs.getLong("revision"), rs.getString("json_payload"),
                        rs.getLong("updated_at_ms"));
            }
        }
    }

    @Nullable
    private TransactionOperation findOperation(Connection connection,
                                               String namespace,
                                               String idempotencyKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, namespace, idempotency_key, profile_id, data_key,
                       expected_revision, resulting_revision, payload_fingerprint,
                       result_json_payload, result_updated_at_ms, status, reason, updated_at_ms
                FROM api_profile_data_operations
                WHERE namespace = ? AND idempotency_key = ?
                LIMIT 1
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                Long resultingRevision = rs.getObject("resulting_revision") == null
                        ? null : rs.getLong("resulting_revision");
                Long resultUpdatedAtMs = rs.getObject("result_updated_at_ms") == null
                        ? null : rs.getLong("result_updated_at_ms");
                return new TransactionOperation(
                        UUID.fromString(rs.getString("operation_id")),
                        rs.getString("namespace"), rs.getString("idempotency_key"),
                        rs.getString("profile_id"), rs.getString("data_key"),
                        rs.getLong("expected_revision"),
                        resultingRevision == null ? -1L : resultingRevision,
                        rs.getString("payload_fingerprint"),
                        TransactionStatus.valueOf(rs.getString("status")),
                        rs.getString("reason"), rs.getString("result_json_payload"),
                        resultUpdatedAtMs == null ? -1L : resultUpdatedAtMs,
                        rs.getLong("updated_at_ms")
                );
            }
        }
    }

    private boolean profileExists(@Nonnull String profileId) {
        try (Connection connection = connectionManager.openConnection()) {
            return profileExistsInTransaction(connection, profileId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean profileExistsInTransaction(@Nonnull Connection connection,
                                               @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM npc_profiles WHERE profile_id = ? LIMIT 1"
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Nullable
    private String normalizeProfileId(@Nullable String profileId) {
        return normalizeText(profileId);
    }

    @Nullable
    private String normalizeNamespace(@Nullable String namespace) {
        String trimmed = normalizeOperationNamespace(namespace);
        return trimmed == null || RESERVED_NAMESPACE.equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    @Nullable
    private String normalizeOperationNamespace(@Nullable String namespace) {
        return normalizeText(namespace);
    }

    @Nullable
    private String normalizeKey(@Nullable String key) {
        return normalizeText(key);
    }

    @Nullable
    private String normalizeText(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private String canonicalPayload(@Nullable String jsonPayload) {
        if (jsonPayload == null) return null;
        try {
            return JsonParser.parseString(jsonPayload.trim()).toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String sha256(String canonicalPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonicalPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record VersionedValue(@Nonnull String profileId,
                                 @Nonnull String namespace,
                                 @Nonnull String key,
                                 long revision,
                                 @Nonnull String jsonPayload,
                                 long updatedAtMs) {
    }

    public record TransactionOperation(@Nonnull UUID operationId,
                                       @Nonnull String namespace,
                                       @Nonnull String idempotencyKey,
                                       @Nonnull String profileId,
                                       @Nonnull String key,
                                       long expectedRevision,
                                       long resultingRevision,
                                       @Nonnull String payloadFingerprint,
                                       @Nonnull TransactionStatus status,
                                       @Nonnull String reason,
                                       @Nullable String resultJsonPayload,
                                       long resultUpdatedAtMs,
                                       long updatedAtMs) {
        boolean matches(String requestedProfileId, String requestedKey,
                        long requestedRevision, String requestedFingerprint) {
            return profileId.equals(requestedProfileId)
                    && key.equals(requestedKey)
                    && expectedRevision == requestedRevision
                    && payloadFingerprint.equals(requestedFingerprint);
        }

        boolean terminal() {
            return status == TransactionStatus.COMMITTED
                    || status == TransactionStatus.TERMINAL_DENIED
                    || status == TransactionStatus.QUARANTINED;
        }
    }

    public record TransactionResult(@Nonnull TransactionOutcome outcome,
                                    @Nonnull String reason,
                                    @Nullable TransactionOperation operation,
                                    @Nullable VersionedValue value) {
        static TransactionResult unavailable(String reason) {
            return new TransactionResult(TransactionOutcome.UNAVAILABLE, reason, null, null);
        }
    }

    public enum TransactionStatus {
        PREPARED,
        APPLYING,
        COMMITTED,
        TERMINAL_DENIED,
        QUARANTINED
    }

    public enum TransactionOutcome {
        COMMITTED,
        TERMINAL_DENIED,
        QUARANTINED,
        UNAVAILABLE
    }
}
