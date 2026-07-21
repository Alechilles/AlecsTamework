package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3d;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class LostRepository {
    private static final String SNAPSHOT_TYPE = "lost";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profileRepository;
    private final LostRecoveryEnvelopeCodec envelopeCodec;
    private final RecoveredProjectionSnapshotStore recoveredProjectionSnapshots;

    public LostRepository(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, new NpcProfileRepository(connectionManager, writeQueue));
    }

    public LostRepository(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceWriteQueue writeQueue,
                          @Nonnull NpcProfileRepository profileRepository) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.envelopeCodec = new LostRecoveryEnvelopeCodec();
        this.recoveredProjectionSnapshots = new RecoveredProjectionSnapshotStore(connectionManager);
    }

    @Nonnull
    public List<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT s.profile_id, s.payload_json, p.current_npc_uuid
                     FROM npc_snapshots s
                     INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                     WHERE s.snapshot_type = ? AND s.is_active = 1
                     """
             )) {
            statement.setString(1, SNAPSHOT_TYPE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    JsonObject payload = parseJsonObject(rs.getString("payload_json"));
                    if (payload == null) {
                        continue;
                    }
                    UUID currentNpcUuid = SqliteValueCodec.parseUuid(rs.getString("current_npc_uuid"));
                    UUID sourceNpcUuid = parseUuid(payload, "sourceNpcUuid");
                    UUID npcUuid = sourceNpcUuid != null ? sourceNpcUuid : currentNpcUuid;
                    if (npcUuid == null) {
                        continue;
                    }
                    rows.add(new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                            npcUuid,
                            readVector(payload, "lastKnownPosition"),
                            readVector(payload, "homePosition"),
                            getLong(payload, "lastRelocationQueuedAtMs", 0L),
                            getLong(payload, "lostAtMs", 0L),
                            getInt(payload, "relocationRetryAttempts", 0),
                            parseUuid(payload, "replacementNpcUuid"),
                            getLong(payload, "recoveredAtMs", 0L)
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    /**
     * Loads restart-safe stale-projection mappings from finalized recovery operations.
     *
     * <p>This deliberately excludes ordinary historical aliases because one of those can be the
     * sole live entity eligible for identity repair. A finalized recovery source, by contrast,
     * must always yield to the profile's current projection.</p>
     */
    @Nonnull
    public Map<UUID, UUID> loadRecoveredSourceReplacements() {
        LinkedHashMap<UUID, UUID> replacements = new LinkedHashMap<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT o.source_npc_uuid, p.current_npc_uuid
                     FROM npc_recovery_operations o
                     INNER JOIN npc_profiles p ON p.profile_id = o.profile_id
                     WHERE o.state = 'FINALIZED' AND o.active = 0
                       AND o.source_npc_uuid IS NOT NULL AND p.current_npc_uuid IS NOT NULL
                       AND o.source_npc_uuid <> p.current_npc_uuid
                     ORDER BY o.completed_at_ms, o.operation_id
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID historicalUuid = SqliteValueCodec.parseUuid(resultSet.getString(1));
                    UUID currentUuid = SqliteValueCodec.parseUuid(resultSet.getString(2));
                    if (historicalUuid != null && currentUuid != null) {
                        replacements.put(historicalUuid, currentUuid);
                    }
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.copyOf(replacements);
    }

    /**
     * Loads restart-safe full state for the current projection of a finalized recovery.
     * Durable identity, lifecycle, alias, and operation evidence must all agree.
     */
    @Nonnull
    public RecoveredProjectionSnapshotLoadResult loadRecoveredProjectionSnapshot(
            @Nullable UUID currentNpcUuid) {
        return recoveredProjectionSnapshots.load(currentNpcUuid);
    }

    public boolean upsertAsync(@Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot) {
        return upsertTracked(snapshot).accepted();
    }

    /** Backward-compatible boolean wrapper for a complete lost recovery envelope. */
    public boolean upsertAsync(
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot) {
        return upsertTracked(snapshot, fullSnapshot).accepted();
    }

    /** Returns a completion that resolves only after the lost-envelope transaction commits or fails. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> upsertTracked(
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot) {
        return upsertTracked(snapshot, null);
    }

    /** Stores optional full restorable state behind a strict versioned and hashed envelope. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> upsertTracked(
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshot.npcUuid(), "snapshot.npcUuid");
        LostRecoveryEnvelopeCodec.EncodedPayload encoded = envelopeCodec.encode(snapshot, fullSnapshot);
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submitTracked(
                "lost_upsert",
                connection -> {
                    beforeRef.set(profileRepository.loadProfileByNpcUuidInTransaction(connection, snapshot.npcUuid()));
                    LostRecoveryWriteResult result = upsertEncodedInTransaction(
                            connection, snapshot, encoded);
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, snapshot.npcUuid());
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                    return result;
                },
                ignored -> {
                    profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get());
                    if (snapshot.isAwaitingRecovery()) {
                        profileRepository.notifyLostRecorded(snapshot, afterRef.get());
                    }
                }
        );
    }

    /** Loads one active lost envelope by stable profile identity. */
    @Nonnull
    public LostRecoveryLoadResult loadAwaitingByProfile(@Nullable String profileId) {
        String normalizedProfileId = normalizeText(profileId);
        if (normalizedProfileId == null) {
            return LostRecoveryLoadResult.failed(
                    LostRecoveryLoadResult.Failure.INVALID_INPUT, null, "profile_id_required", null);
        }
        try (Connection connection = connectionManager.openConnection()) {
            return loadAwaitingByProfile(connection, normalizedProfileId);
        } catch (LostReadFailure failure) {
            return LostRecoveryLoadResult.failed(
                    failure.failure, null, failure.getMessage(), failure);
        } catch (Exception exception) {
            return LostRecoveryLoadResult.failed(
                    LostRecoveryLoadResult.Failure.SQL_ERROR, null, exception.getMessage(), exception);
        }
    }

    /** Resolves a source alias to its profile before reading the active lost envelope. */
    @Nonnull
    public LostRecoveryLoadResult loadAwaitingBySourceUuid(@Nullable UUID sourceNpcUuid) {
        if (sourceNpcUuid == null) {
            return LostRecoveryLoadResult.failed(
                    LostRecoveryLoadResult.Failure.INVALID_INPUT, null, "source_uuid_required", null);
        }
        try (Connection connection = connectionManager.openConnection()) {
            LinkedHashSet<String> profileIds = resolveProfileIds(connection, sourceNpcUuid);
            if (profileIds.isEmpty()) {
                return LostRecoveryLoadResult.notFound();
            }
            if (profileIds.size() != 1) {
                return LostRecoveryLoadResult.failed(
                        LostRecoveryLoadResult.Failure.PROFILE_LOOKUP_CONFLICT,
                        null,
                        "source_maps_to_multiple_profiles:" + sourceNpcUuid,
                        null);
            }
            LostRecoveryLoadResult result = loadAwaitingByProfile(
                    connection, profileIds.iterator().next());
            LostRecoveryEnvelope envelope = result.envelope();
            if (envelope != null && envelope.sourceNpcUuid() != null
                    && !sourceNpcUuid.equals(envelope.sourceNpcUuid())) {
                return LostRecoveryLoadResult.failed(
                        LostRecoveryLoadResult.Failure.SOURCE_MISMATCH,
                        envelope,
                        sourceNpcUuid + "!=" + envelope.sourceNpcUuid(),
                        null);
            }
            return result;
        } catch (LostReadFailure failure) {
            return LostRecoveryLoadResult.failed(
                    failure.failure, null, failure.getMessage(), failure);
        } catch (Exception exception) {
            return LostRecoveryLoadResult.failed(
                    LostRecoveryLoadResult.Failure.SQL_ERROR, null, exception.getMessage(), exception);
        }
    }

    public boolean deleteAsync(@Nonnull UUID npcUuid) {
        return deleteTracked(npcUuid).accepted();
    }

    /** Returns a completion only after ordered lost-state deactivation commits or fails. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Void> deleteTracked(@Nonnull UUID npcUuid) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submitTracked(
                "lost_delete",
                connection -> {
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
                    beforeRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                    deleteInTransaction(connection, npcUuid);
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                    return null;
                },
                ignored -> profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get())
        );
    }

    void upsertInTransaction(@Nonnull Connection connection,
                             @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot) throws Exception {
        upsertEnvelopeInTransaction(connection, snapshot, null);
    }

    @Nonnull
    private LostRecoveryWriteResult upsertEnvelopeInTransaction(
            @Nonnull Connection connection,
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot) throws Exception {
        Objects.requireNonNull(snapshot.npcUuid(), "snapshot.npcUuid");
        LostRecoveryEnvelopeCodec.EncodedPayload encoded = envelopeCodec.encode(snapshot, fullSnapshot);
        return upsertEncodedInTransaction(connection, snapshot, encoded);
    }

    @Nonnull
    private LostRecoveryWriteResult upsertEncodedInTransaction(
            @Nonnull Connection connection,
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
            @Nonnull LostRecoveryEnvelopeCodec.EncodedPayload encoded) throws Exception {
        profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ), ProfileOwnerMutation.unchanged());
        String profileId = profileRepository.resolveOrCreateProfileIdInTransaction(connection, snapshot.npcUuid());
        profileRepository.setActiveSnapshotInTransaction(
                connection,
                profileId,
                SNAPSHOT_TYPE,
                encoded.payloadJson(),
                snapshot.lostAtMs()
        );
        profileRepository.setProfileStateInTransaction(connection, profileId, null, null, true, null, null);
        return new LostRecoveryWriteResult(
                profileId,
                snapshot.npcUuid(),
                encoded.formatVersion(),
                encoded.fullSnapshotStored(),
                encoded.fullSnapshotSha256()
        );
    }

    void deleteInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        profileRepository.deactivateSnapshotTypeInTransaction(connection, profileId, SNAPSHOT_TYPE);
        profileRepository.setProfileStateInTransaction(connection, profileId, null, null, false, null, null);
    }

    @Nonnull
    private LostRecoveryLoadResult loadAwaitingByProfile(@Nonnull Connection connection,
                                                         @Nonnull String profileId) throws Exception {
        ActiveLostRow row = readActiveLostRow(connection, profileId);
        return row == null
                ? LostRecoveryLoadResult.notFound()
                : decodeActiveLostRow(profileId, row);
    }

    @Nullable
    private ActiveLostRow readActiveLostRow(@Nonnull Connection connection,
                                            @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.payload_json, p.profile_id AS existing_profile_id, p.current_npc_uuid
                FROM npc_snapshots s
                LEFT JOIN npc_profiles p ON p.profile_id = s.profile_id
                WHERE s.profile_id = ? AND s.snapshot_type = ? AND s.is_active = 1
                ORDER BY s.created_at_ms DESC, s.snapshot_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, SNAPSHOT_TYPE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String payloadJson = resultSet.getString("payload_json");
                String existingProfileId = normalizeText(resultSet.getString("existing_profile_id"));
                String rawCurrentUuid = normalizeText(resultSet.getString("current_npc_uuid"));
                if (resultSet.next()) {
                    throw new LostReadFailure(
                            LostRecoveryLoadResult.Failure.DUPLICATE_ACTIVE_ROWS,
                            "multiple_active_lost_rows:" + profileId);
                }
                if (existingProfileId == null || !profileId.equals(existingProfileId)) {
                    throw new LostReadFailure(
                            LostRecoveryLoadResult.Failure.PROFILE_NOT_FOUND,
                            "profile_missing:" + profileId);
                }
                UUID currentNpcUuid = parseRequiredUuid(rawCurrentUuid);
                if (currentNpcUuid == null) {
                    throw new LostReadFailure(
                            LostRecoveryLoadResult.Failure.PROFILE_CURRENT_MISMATCH,
                            "invalid_or_missing_current_uuid:" + profileId);
                }
                return new ActiveLostRow(payloadJson, currentNpcUuid);
            }
        }
    }

    @Nonnull
    private LostRecoveryLoadResult decodeActiveLostRow(@Nonnull String profileId,
                                                       @Nonnull ActiveLostRow row) {
        LostRecoveryEnvelopeCodec.DecodeResult decoded = envelopeCodec.decode(row.payloadJson());
        if (decoded.status() == LostRecoveryEnvelopeCodec.Status.FAILED
                || decoded.payload() == null) {
            return LostRecoveryLoadResult.failed(
                    decoded.failure() != null
                            ? decoded.failure()
                            : LostRecoveryLoadResult.Failure.INVALID_JSON,
                    null,
                    decoded.detail(),
                    null);
        }
        LostRecoveryEnvelopeCodec.DecodedPayload payload = decoded.payload();
        LostRecoveryEnvelope envelope = new LostRecoveryEnvelope(
                payload.formatVersion(), profileId, row.currentNpcUuid(), payload.sourceNpcUuid(),
                payload.metadata(), payload.fullSnapshot(), payload.fullSnapshotSha256());
        return classifyDecodedEnvelope(decoded, envelope);
    }

    @Nonnull
    private LostRecoveryLoadResult classifyDecodedEnvelope(
            @Nonnull LostRecoveryEnvelopeCodec.DecodeResult decoded,
            @Nonnull LostRecoveryEnvelope envelope) {
        if (!envelope.isAwaitingRecovery()) {
            return LostRecoveryLoadResult.notAwaiting(envelope);
        }
        if (envelope.sourceNpcUuid() != null
                && !envelope.currentNpcUuid().equals(envelope.sourceNpcUuid())) {
            return LostRecoveryLoadResult.failed(
                    LostRecoveryLoadResult.Failure.PROFILE_CURRENT_MISMATCH,
                    envelope,
                    envelope.currentNpcUuid() + "!=" + envelope.sourceNpcUuid(),
                    null);
        }
        return decoded.status() == LostRecoveryEnvelopeCodec.Status.LEGACY_UNVERIFIED
                ? LostRecoveryLoadResult.legacy(envelope, decoded.failure())
                : LostRecoveryLoadResult.found(envelope);
    }

    @Nonnull
    private LinkedHashSet<String> resolveProfileIds(@Nonnull Connection connection,
                                                    @Nonnull UUID sourceNpcUuid) throws Exception {
        LinkedHashSet<String> profileIds = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                UNION ALL
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                """)) {
            statement.setString(1, sourceNpcUuid.toString());
            statement.setString(2, sourceNpcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String profileId = normalizeText(resultSet.getString("profile_id"));
                    if (profileId == null) {
                        throw new LostReadFailure(
                                LostRecoveryLoadResult.Failure.PROFILE_LOOKUP_CONFLICT,
                                "blank_profile_for_source:" + sourceNpcUuid);
                    }
                    profileIds.add(profileId);
                }
            }
        }
        return profileIds;
    }

    @Nullable
    private UUID parseRequiredUuid(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Nullable
    private String normalizeText(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Nullable
    private JsonObject parseJsonObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private Vector3d readVector(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || !source.get(key).isJsonObject()) {
            return null;
        }
        JsonObject vector = source.getAsJsonObject(key);
        try {
            return new Vector3d(
                    vector.get("x").getAsDouble(),
                    vector.get("y").getAsDouble(),
                    vector.get("z").getAsDouble()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getLong(@Nonnull JsonObject source, @Nonnull String key, long fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int getInt(@Nonnull JsonObject source, @Nonnull String key, int fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private UUID parseUuid(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            return UUID.fromString(source.get(key).getAsString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ActiveLostRow(@Nonnull String payloadJson, @Nonnull UUID currentNpcUuid) {
    }

    private static final class LostReadFailure extends Exception {
        private final LostRecoveryLoadResult.Failure failure;

        private LostReadFailure(@Nonnull LostRecoveryLoadResult.Failure failure,
                                @Nonnull String detail) {
            super(detail);
            this.failure = failure;
        }
    }
}
