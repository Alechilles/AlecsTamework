package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.api.Vector3View;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** SQLite authority for canonical owner/command-family/profile roster membership. */
public final class CommandFamilyRosterRepository {
    public enum MutationKind { UPSERT, REMOVE }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CommandFamilyRosterRepository(@Nonnull SqliteConnectionManager connectionManager,
                                         @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nullable
    public CommandFamilyRosterView find(@Nonnull UUID ownerUuid, @Nonnull String familyId)
            throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return find(connection, ownerUuid, requireText(familyId, "familyId"));
        }
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationOutcome> mutateAsync(
            @Nonnull MutationKind kind, @Nonnull CommandFamilyRosterMutationRequest request,
            @Nonnull ProfilePolicyFence policyFence) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(request, "request");
        return writeQueue.submitTracked(
                "command_family_roster_" + kind.name().toLowerCase(),
                connection -> mutateInTransaction(connection, kind, request, policyFence),
                null);
    }

    /** Transaction seam for profile provisioning/capture coordinators using the same connection. */
    @Nonnull
    public MutationOutcome mutateInTransaction(@Nonnull Connection connection,
                                               @Nonnull MutationKind kind,
                                               @Nonnull CommandFamilyRosterMutationRequest request,
                                               @Nonnull ProfilePolicyFence policyFence)
            throws Exception {
        Objects.requireNonNull(connection, "connection");
        String fingerprint = fingerprint(kind, request);
        OperationReceipt receipt = findReceipt(
                connection, request.callerNamespace(), request.idempotencyKey());
        if (receipt != null) {
            if (!receipt.payloadFingerprint().equals(fingerprint)) {
                return outcome(connection, request, CommandFamilyRosterMutationStatus.CONFLICT,
                        "idempotency-key-in-use", true, null, null, receipt.operationId());
            }
            CommandFamilyRosterMutationStatus replayStatus =
                    receipt.status() == CommandFamilyRosterMutationStatus.APPLIED
                            ? CommandFamilyRosterMutationStatus.IDEMPOTENT : receipt.status();
            return outcome(connection, request, replayStatus, receipt.reason(), true,
                    null, null, receipt.operationId());
        }

        UUID operationId = UUID.nameUUIDFromBytes(("roster\u0000" + request.callerNamespace()
                + "\u0000" + request.idempotencyKey()).getBytes(StandardCharsets.UTF_8));
        CommandFamilyRosterView before = find(
                connection, request.ownerUuid(), request.commandFamilyId());
        CommandFamilyRosterMembershipView previous = membership(before, request.profileId());
        long currentRevision = before == null ? 0L : before.revision();

        ProfileAuthority profile = kind == MutationKind.UPSERT
                ? findProfileAuthority(connection, request.profileId()) : null;
        if (kind == MutationKind.UPSERT && profile == null) {
            insertReceipt(connection, operationId, kind, request, fingerprint,
                    CommandFamilyRosterMutationStatus.NOT_FOUND, "profile-not-found", currentRevision);
            return outcome(connection, request, CommandFamilyRosterMutationStatus.NOT_FOUND,
                    "profile-not-found", false, previous, previous, operationId);
        }
        if (kind == MutationKind.UPSERT && !request.ownerUuid().equals(profile.ownerUuid())) {
            insertReceipt(connection, operationId, kind, request, fingerprint,
                    CommandFamilyRosterMutationStatus.CONFLICT, "profile-owner-changed",
                    currentRevision, profile.roleId());
            return outcome(connection, request, CommandFamilyRosterMutationStatus.CONFLICT,
                    "profile-owner-changed", false, previous, previous, operationId);
        }
        if (kind == MutationKind.UPSERT && request.expectedProfileRevision() != profile.revision()) {
            insertReceipt(connection, operationId, kind, request, fingerprint,
                    CommandFamilyRosterMutationStatus.CONFLICT, "profile-revision-changed",
                    currentRevision, profile.roleId());
            return outcome(connection, request, CommandFamilyRosterMutationStatus.CONFLICT,
                    "profile-revision-changed", false, previous, previous, operationId);
        }
        if (kind == MutationKind.UPSERT) {
            String policyReason = policyFence.denialReason(profile.roleId(), request);
            if (policyReason != null) {
                insertReceipt(connection, operationId, kind, request, fingerprint,
                        CommandFamilyRosterMutationStatus.CONFLICT, policyReason,
                        currentRevision, profile.roleId());
                return outcome(connection, request, CommandFamilyRosterMutationStatus.CONFLICT,
                        policyReason, false, previous, previous, operationId);
            }
        }

        if (kind == MutationKind.UPSERT && sameMembership(previous, request)
                && previous.profileRevision() == profile.revision()
                && previous.roleId().equals(profile.roleId())) {
            insertReceipt(connection, operationId, kind, request, fingerprint,
                    CommandFamilyRosterMutationStatus.IDEMPOTENT, "membership-unchanged", currentRevision);
            return outcome(connection, request, CommandFamilyRosterMutationStatus.IDEMPOTENT,
                    "membership-unchanged", false, previous, previous, operationId);
        }
        if (kind == MutationKind.REMOVE && previous == null) {
            insertReceipt(connection, operationId, kind, request, fingerprint,
                    CommandFamilyRosterMutationStatus.NOT_FOUND, "membership-not-found", currentRevision);
            return outcome(connection, request, CommandFamilyRosterMutationStatus.NOT_FOUND,
                    "membership-not-found", false, null, null, operationId);
        }
        if (request.expectedRevision() != currentRevision) {
            insertReceipt(connection, operationId, kind, request, fingerprint,
                    CommandFamilyRosterMutationStatus.CONFLICT, "roster-revision-changed", currentRevision);
            return outcome(connection, request, CommandFamilyRosterMutationStatus.CONFLICT,
                    "roster-revision-changed", false, previous, previous, operationId);
        }
        long nowMs = System.currentTimeMillis();
        ensureRoster(connection, request.ownerUuid(), request.commandFamilyId(), nowMs);
        if (kind == MutationKind.UPSERT) {
            upsertMembership(connection, request, profile, nowMs);
        } else {
            deleteMembership(connection, request);
        }
        long nextRevision = currentRevision + 1L;
        updateRosterRevision(connection, request.ownerUuid(), request.commandFamilyId(), nextRevision, nowMs);
        CommandFamilyRosterView after = find(
                connection, request.ownerUuid(), request.commandFamilyId());
        CommandFamilyRosterMembershipView current = membership(after, request.profileId());
        insertReceipt(connection, operationId, kind, request, fingerprint,
                CommandFamilyRosterMutationStatus.APPLIED, null, nextRevision,
                profile == null ? null : profile.roleId());
        return new MutationOutcome(CommandFamilyRosterMutationStatus.APPLIED, null, after,
                previous, current, false, operationId);
    }

    private MutationOutcome outcome(Connection connection,
                                    CommandFamilyRosterMutationRequest request,
                                    CommandFamilyRosterMutationStatus status,
                                    @Nullable String reason,
                                    boolean replay,
                                    @Nullable CommandFamilyRosterMembershipView previous,
                                    @Nullable CommandFamilyRosterMembershipView current,
                                    UUID operationId) throws Exception {
        CommandFamilyRosterView roster = find(connection, request.ownerUuid(), request.commandFamilyId());
        CommandFamilyRosterMembershipView persisted = membership(roster, request.profileId());
        return new MutationOutcome(status, reason, roster,
                previous == null ? persisted : previous,
                current == null && status != CommandFamilyRosterMutationStatus.APPLIED ? persisted : current,
                replay, operationId);
    }

    @Nullable
    private CommandFamilyRosterView find(Connection connection, UUID ownerUuid, String familyId)
            throws Exception {
        long revision;
        long updatedAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT row_revision, updated_at_ms FROM command_family_rosters
                WHERE owner_uuid = ? AND command_family_id = ?
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, familyId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                revision = result.getLong("row_revision");
                updatedAt = result.getLong("updated_at_ms");
            }
        }
        List<CommandFamilyRosterMembershipView> memberships = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, role_id, profile_revision, command_state, group_id,
                    active_for_bulk_commands,
                    home_x, home_y, home_z, updated_at_ms
                FROM command_family_roster_memberships
                WHERE owner_uuid = ? AND command_family_id = ? ORDER BY profile_id
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, familyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    memberships.add(new CommandFamilyRosterMembershipView(
                            ownerUuid, familyId, result.getString("profile_id"),
                            result.getString("role_id"), result.getLong("profile_revision"),
                            CommandFamilyRosterMemberState.valueOf(result.getString("command_state")),
                            result.getString("group_id"),
                            result.getInt("active_for_bulk_commands") != 0,
                            readHome(result), result.getLong("updated_at_ms")));
                }
            }
        }
        return new CommandFamilyRosterView(ownerUuid, familyId, revision, memberships, updatedAt);
    }

    private void ensureRoster(Connection connection, UUID ownerUuid, String familyId, long nowMs)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO command_family_rosters (
                    owner_uuid, command_family_id, row_revision, created_at_ms, updated_at_ms)
                VALUES (?, ?, 0, ?, ?)
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, familyId);
            statement.setLong(3, nowMs);
            statement.setLong(4, nowMs);
            statement.executeUpdate();
        }
    }

    private void upsertMembership(Connection connection,
                                  CommandFamilyRosterMutationRequest request,
                                  ProfileAuthority profile,
                                  long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_family_roster_memberships (
                    owner_uuid, command_family_id, profile_id, role_id, profile_revision,
                    command_state, group_id,
                    active_for_bulk_commands,
                    home_x, home_y, home_z, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_uuid, command_family_id, profile_id) DO UPDATE SET
                    role_id = excluded.role_id, profile_revision = excluded.profile_revision,
                    command_state = excluded.command_state, group_id = excluded.group_id,
                    active_for_bulk_commands = excluded.active_for_bulk_commands,
                    home_x = excluded.home_x, home_y = excluded.home_y, home_z = excluded.home_z,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, request.ownerUuid().toString());
            statement.setString(2, request.commandFamilyId());
            statement.setString(3, request.profileId());
            statement.setString(4, profile.roleId());
            statement.setLong(5, profile.revision());
            statement.setString(6, request.state().name());
            setText(statement, 7, request.groupId());
            statement.setInt(8, request.activeForBulkCommands() ? 1 : 0);
            setHome(statement, 9, request.homePosition());
            statement.setLong(12, nowMs);
            statement.setLong(13, nowMs);
            statement.executeUpdate();
        }
    }

    private void deleteMembership(Connection connection, CommandFamilyRosterMutationRequest request)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM command_family_roster_memberships
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                """)) {
            statement.setString(1, request.ownerUuid().toString());
            statement.setString(2, request.commandFamilyId());
            statement.setString(3, request.profileId());
            statement.executeUpdate();
        }
    }

    private void updateRosterRevision(Connection connection, UUID ownerUuid, String familyId,
                                      long revision, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_family_rosters SET row_revision = ?, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ?
                """)) {
            statement.setLong(1, revision);
            statement.setLong(2, nowMs);
            statement.setString(3, ownerUuid.toString());
            statement.setString(4, familyId);
            statement.executeUpdate();
        }
    }

    private ProfileAuthority findProfileAuthority(Connection connection, String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.owner_uuid, p.role_id, s.revision
                FROM npc_profiles p
                JOIN companion_population_state s ON s.profile_id = p.profile_id
                WHERE p.profile_id = ? LIMIT 1
                """)) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String owner = result.getString("owner_uuid");
                String role = result.getString("role_id");
                if (owner == null || role == null || role.isBlank()) return null;
                return new ProfileAuthority(UUID.fromString(owner), role.trim(), result.getLong("revision"));
            }
        }
    }

    private OperationReceipt findReceipt(Connection connection, String namespace, String key)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, payload_fingerprint, status, reason
                FROM command_family_roster_operations
                WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new OperationReceipt(UUID.fromString(result.getString("operation_id")),
                        result.getString("payload_fingerprint"),
                        CommandFamilyRosterMutationStatus.valueOf(result.getString("status")),
                        result.getString("reason"));
            }
        }
    }

    private void insertReceipt(Connection connection, UUID operationId, MutationKind kind,
                               CommandFamilyRosterMutationRequest request, String fingerprint,
                               CommandFamilyRosterMutationStatus status, @Nullable String reason,
                               long resultingRevision) throws Exception {
        insertReceipt(connection, operationId, kind, request, fingerprint, status, reason,
                resultingRevision, null);
    }

    private void insertReceipt(Connection connection, UUID operationId, MutationKind kind,
                               CommandFamilyRosterMutationRequest request, String fingerprint,
                               CommandFamilyRosterMutationStatus status, @Nullable String reason,
                               long resultingRevision, @Nullable String profileRoleId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_family_roster_operations (
                    operation_id, caller_namespace, idempotency_key, correlation_id,
                    owner_uuid, command_family_id, profile_id, operation_kind,
                    expected_revision, expected_profile_revision, required_command_config_id,
                    access_item_id, profile_role_id, resulting_revision, payload_fingerprint,
                    status, reason, created_at_ms, completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            long nowMs = System.currentTimeMillis();
            statement.setString(1, operationId.toString());
            statement.setString(2, request.callerNamespace());
            statement.setString(3, request.idempotencyKey());
            setText(statement, 4, request.correlationId() == null ? null : request.correlationId().toString());
            statement.setString(5, request.ownerUuid().toString());
            statement.setString(6, request.commandFamilyId());
            statement.setString(7, request.profileId());
            statement.setString(8, kind.name());
            statement.setLong(9, request.expectedRevision());
            statement.setLong(10, request.expectedProfileRevision());
            setText(statement, 11, request.requiredCommandConfigId());
            setText(statement, 12, request.accessItemId());
            setText(statement, 13, profileRoleId);
            statement.setLong(14, resultingRevision);
            statement.setString(15, fingerprint);
            statement.setString(16, status.name());
            setText(statement, 17, reason);
            statement.setLong(18, nowMs);
            statement.setLong(19, nowMs);
            statement.executeUpdate();
        }
    }

    private static CommandFamilyRosterMembershipView membership(
            @Nullable CommandFamilyRosterView roster, String profileId) {
        if (roster == null) return null;
        return roster.memberships().stream()
                .filter(value -> value.profileId().equals(profileId)).findFirst().orElse(null);
    }

    private static boolean sameMembership(@Nullable CommandFamilyRosterMembershipView existing,
                                          CommandFamilyRosterMutationRequest request) {
        return existing != null && existing.state() == request.state()
                && Objects.equals(existing.groupId(), request.groupId())
                && existing.activeForBulkCommands() == request.activeForBulkCommands()
                && Objects.equals(existing.homePosition(), request.homePosition());
    }

    private static String fingerprint(MutationKind kind, CommandFamilyRosterMutationRequest request) {
        Vector3View home = request.homePosition();
        return String.join("\u001f", kind.name(), request.ownerUuid().toString(),
                request.commandFamilyId(), request.profileId(),
                String.valueOf(request.requiredCommandConfigId()), String.valueOf(request.accessItemId()),
                request.state().name(), String.valueOf(request.groupId()),
                Boolean.toString(request.activeForBulkCommands()),
                home == null ? "null" : home.x() + "," + home.y() + "," + home.z(),
                Long.toString(request.expectedRevision()),
                Long.toString(request.expectedProfileRevision()));
    }

    private static Vector3View readHome(ResultSet result) throws Exception {
        double x = result.getDouble("home_x");
        if (result.wasNull()) return null;
        return new Vector3View(x, result.getDouble("home_y"), result.getDouble("home_z"));
    }

    private static void setHome(PreparedStatement statement, int index, @Nullable Vector3View home)
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

    private static void setText(PreparedStatement statement, int index, @Nullable String value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    private record OperationReceipt(UUID operationId, String payloadFingerprint,
                                    CommandFamilyRosterMutationStatus status, String reason) { }

    private record ProfileAuthority(UUID ownerUuid, String roleId, long revision) { }

    @FunctionalInterface
    public interface ProfilePolicyFence {
        /** Returns a stable denial reason, or null when this role/config/family combination is allowed. */
        @Nullable String denialReason(@Nonnull String profileRoleId,
                                      @Nonnull CommandFamilyRosterMutationRequest request);
    }

    public record MutationOutcome(@Nonnull CommandFamilyRosterMutationStatus status,
                                  @Nullable String reason,
                                  @Nullable CommandFamilyRosterView roster,
                                  @Nullable CommandFamilyRosterMembershipView previousMembership,
                                  @Nullable CommandFamilyRosterMembershipView currentMembership,
                                  boolean idempotentReplay,
                                  @Nonnull UUID operationId) { }
}
