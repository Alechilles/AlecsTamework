package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkRequest;
import com.alechilles.alecstamework.api.Vector3View;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable intent consumed inside the profile/population commit transaction. */
public final class CompanionProvisioningCommandLinkRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CompanionProvisioningCommandLinkRepository(
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<PrepareResult> prepareAsync(
            @Nonnull UUID operationId,
            @Nonnull CompanionProvisioningLinkRequest request,
            long expectedRosterRevision) {
        if (expectedRosterRevision < 0L) throw new IllegalArgumentException("expectedRosterRevision");
        return writeQueue.submitTracked("companion_provisioning_command_link_prepare",
                connection -> prepareInTransaction(connection, operationId, request,
                        expectedRosterRevision), null);
    }

    @Nullable
    public LinkRecord find(@Nonnull UUID operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return find(connection, operationId.toString());
        }
    }

    /** Executes roster mutation and marks the link committed in the caller's profile transaction. */
    @Nonnull
    public CommitResult commitInTransaction(
            @Nonnull Connection connection,
            @Nonnull UUID operationId,
            @Nonnull String profileId,
            @Nonnull CommandFamilyRosterRepository rosterRepository,
            @Nonnull CommandFamilyRosterRepository.ProfilePolicyFence policyFence) throws Exception {
        LinkRecord intent = find(connection, operationId.toString());
        if (intent == null) return CommitResult.noIntent();
        if (intent.state() == State.COMMITTED) {
            if (!profileId.equals(intent.profileId())) {
                return CommitResult.denied("provisioning-link-profile-changed");
            }
            return CommitResult.committed(intent, true);
        }
        if (intent.state() != State.PREPARED) {
            return CommitResult.denied(reason(intent.reason(), "provisioning-link-quarantined"));
        }
        long profileRevision = loadProfileRevision(connection, profileId);
        CommandFamilyRosterMutationRequest mutation = new CommandFamilyRosterMutationRequest(
                intent.callerNamespace(), intent.idempotencyKey() + ":command-family-link",
                operationId, intent.ownerUuid(), intent.commandFamilyId(), profileId,
                intent.requiredCommandConfigId(), intent.accessItemId(),
                CommandFamilyRosterMemberState.ROSTER_STORED, intent.groupId(),
                intent.activeForBulkCommands(), intent.homePosition(),
                intent.expectedRosterRevision(), profileRevision);
        CommandFamilyRosterRepository.MutationOutcome roster = rosterRepository.mutateInTransaction(
                connection, CommandFamilyRosterRepository.MutationKind.UPSERT, mutation, policyFence);
        if (roster.status() != CommandFamilyRosterMutationStatus.APPLIED
                && roster.status() != CommandFamilyRosterMutationStatus.IDEMPOTENT) {
            return CommitResult.denied(reason(roster.reason(), "provisioning-link-roster-denied"));
        }
        long resultingRevision = roster.roster() == null
                ? intent.expectedRosterRevision() : roster.roster().revision();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_provisioning_command_links
                SET state = 'COMMITTED', profile_id = ?, resulting_roster_revision = ?,
                    reason = NULL, updated_at_ms = ?
                WHERE operation_id = ? AND state = 'PREPARED'
                """)) {
            statement.setString(1, profileId);
            statement.setLong(2, resultingRevision);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Provisioning command-link intent changed during commit.");
            }
        }
        return CommitResult.committed(find(connection, operationId.toString()), false);
    }

    private PrepareResult prepareInTransaction(Connection connection, UUID operationId,
                                               CompanionProvisioningLinkRequest request,
                                               long expectedRosterRevision) throws Exception {
        LinkRecord existing = findByCallerKey(connection,
                request.provisioning().callerNamespace(), request.provisioning().idempotencyKey());
        if (existing != null) {
            return sameIntent(existing, operationId, request)
                    ? new PrepareResult(Status.IDEMPOTENT, existing, "provisioning-link-exists")
                    : new PrepareResult(Status.CONFLICT, existing, "provisioning-link-key-in-use");
        }
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_provisioning_command_links (
                    operation_id, caller_namespace, idempotency_key, owner_uuid,
                    command_family_id, required_command_config_id, access_item_id, group_id,
                    active_for_bulk_commands, home_x, home_y, home_z,
                    expected_roster_revision, profile_id, resulting_roster_revision,
                    state, reason, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL,
                    'PREPARED', NULL, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, request.provisioning().callerNamespace());
            statement.setString(3, request.provisioning().idempotencyKey());
            statement.setString(4, request.provisioning().ownerUuid().toString());
            statement.setString(5, request.commandFamilyId());
            statement.setString(6, request.requiredCommandConfigId());
            setText(statement, 7, request.accessItemId());
            setText(statement, 8, request.groupId());
            statement.setInt(9, request.activeForBulkCommands() ? 1 : 0);
            setHome(statement, 10, request.provisioning().homePosition());
            statement.setLong(13, expectedRosterRevision);
            statement.setLong(14, nowMs);
            statement.setLong(15, nowMs);
            statement.executeUpdate();
        }
        return new PrepareResult(Status.CREATED, find(connection, operationId.toString()), null);
    }

    private long loadProfileRevision(Connection connection, String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM companion_population_state WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Provisioned profile revision is missing.");
                return result.getLong("revision");
            }
        }
    }

    private LinkRecord findByCallerKey(Connection connection, String namespace, String key)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private LinkRecord find(Connection connection, String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE operation_id = ? LIMIT 1")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private LinkRecord read(ResultSet result) throws Exception {
        return new LinkRecord(UUID.fromString(result.getString("operation_id")),
                result.getString("caller_namespace"), result.getString("idempotency_key"),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("command_family_id"),
                result.getString("required_command_config_id"), result.getString("access_item_id"),
                result.getString("group_id"), result.getInt("active_for_bulk_commands") != 0,
                readHome(result), result.getLong("expected_roster_revision"),
                result.getString("profile_id"), nullableLong(result, "resulting_roster_revision"),
                State.valueOf(result.getString("state")), result.getString("reason"));
    }

    private boolean sameIntent(LinkRecord existing, UUID operationId,
                               CompanionProvisioningLinkRequest request) {
        return existing.operationId().equals(operationId)
                && existing.ownerUuid().equals(request.provisioning().ownerUuid())
                && existing.commandFamilyId().equals(request.commandFamilyId())
                && existing.requiredCommandConfigId().equals(request.requiredCommandConfigId())
                && Objects.equals(existing.accessItemId(), request.accessItemId())
                && Objects.equals(existing.groupId(), request.groupId())
                && existing.activeForBulkCommands() == request.activeForBulkCommands()
                && Objects.equals(existing.homePosition(), request.provisioning().homePosition());
    }

    private static Vector3View readHome(ResultSet result) throws Exception {
        double x = result.getDouble("home_x");
        if (result.wasNull()) return null;
        return new Vector3View(x, result.getDouble("home_y"), result.getDouble("home_z"));
    }

    private static void setHome(PreparedStatement statement, int index, Vector3View home)
            throws Exception {
        if (home == null) {
            statement.setNull(index, Types.REAL);
            statement.setNull(index + 1, Types.REAL);
            statement.setNull(index + 2, Types.REAL);
        } else {
            statement.setDouble(index, home.x());
            statement.setDouble(index + 1, home.y());
            statement.setDouble(index + 2, home.z());
        }
    }

    private static void setText(PreparedStatement statement, int index, String value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String reason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final String SELECT = """
            SELECT operation_id, caller_namespace, idempotency_key, owner_uuid,
                   command_family_id, required_command_config_id, access_item_id, group_id,
                   active_for_bulk_commands, home_x, home_y, home_z,
                   expected_roster_revision, profile_id, resulting_roster_revision, state, reason
            FROM companion_provisioning_command_links
            """;

    public enum State { PREPARED, COMMITTED, QUARANTINED }
    public enum Status { CREATED, IDEMPOTENT, CONFLICT }

    public record LinkRecord(UUID operationId, String callerNamespace, String idempotencyKey,
                             UUID ownerUuid, String commandFamilyId, String requiredCommandConfigId,
                             String accessItemId, String groupId, boolean activeForBulkCommands,
                             Vector3View homePosition, long expectedRosterRevision,
                             String profileId, Long resultingRosterRevision, State state, String reason) { }

    public record PrepareResult(Status status, LinkRecord record, String reason) { }

    public record CommitResult(boolean present, boolean committed, boolean idempotent,
                               LinkRecord record, String reason) {
        static CommitResult noIntent() { return new CommitResult(false, true, true, null, null); }
        static CommitResult committed(LinkRecord record, boolean replay) {
            return new CommitResult(true, true, replay, record, null);
        }
        static CommitResult denied(String reason) {
            return new CommitResult(true, false, false, null, reason);
        }
    }
}
