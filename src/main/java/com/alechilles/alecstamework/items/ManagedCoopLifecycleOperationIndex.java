package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable runtime projection of active managed-coop lifecycle operations.
 *
 * <p>Callers explicitly refresh this index after durable lifecycle writes or at startup. Runtime
 * paths then use the immutable snapshot instead of issuing SQLite reads. A rejected refresh keeps
 * the last-known-good operation evidence visible while atomically marking that evidence untrusted.
 * Consumers must fail closed when {@link #isTrusted()} is false.</p>
 */
public final class ManagedCoopLifecycleOperationIndex {
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Comparator<OperationRecord> OPERATION_ORDER = Comparator
            .comparing((OperationRecord operation) -> operation.authorityKey().worldName())
            .thenComparingInt(operation -> operation.authorityKey().x())
            .thenComparingInt(operation -> operation.authorityKey().y())
            .thenComparingInt(operation -> operation.authorityKey().z())
            .thenComparingInt(OperationRecord::residentSlot)
            .thenComparing(OperationRecord::profileId)
            .thenComparing(OperationRecord::operationId);

    public enum RebuildStatus {
        REBUILT,
        REJECTED
    }

    public record RebuildResult(@Nonnull RebuildStatus status, @Nullable String detail) {
        public boolean rebuilt() {
            return status == RebuildStatus.REBUILT;
        }
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

    /**
     * Replaces the complete visible projection only when the typed read and every operation
     * invariant validate. Rejections retain the previous mappings and revision.
     */
    @Nonnull
    public synchronized RebuildResult rebuild(
            @Nullable ManagedCoopReadResult<List<OperationRecord>> operationsResult) {
        if (operationsResult == null) {
            return reject("missing_coop_lifecycle_operation_read_result");
        }
        if (operationsResult.status() != ManagedCoopReadResult.Status.LOADED
                || operationsResult.value() == null) {
            return reject("coop_lifecycle_operation_snapshot_not_loaded");
        }
        try {
            long nextRevision = Math.addExact(current.get().revision(), 1L);
            Snapshot replacement = Snapshot.build(nextRevision, operationsResult.value());
            current.set(replacement);
            return new RebuildResult(RebuildStatus.REBUILT, null);
        } catch (RuntimeException exception) {
            return reject(detail(exception));
        }
    }

    /** Returns whether the latest complete operation read validated successfully. */
    public boolean isTrusted() {
        return current.get().trusted();
    }

    /** Revokes admission trust without discarding the last immutable diagnostic evidence. */
    public synchronized void revokeTrust() {
        Snapshot previous = current.get();
        if (previous.trusted()) {
            current.set(previous.withTrusted(false));
        }
    }

    /** Returns the current immutable point-in-time projection. */
    @Nonnull
    public Snapshot snapshot() {
        return current.get();
    }

    @Nullable
    public OperationRecord operationById(@Nonnull String operationId) {
        return current.get().operationById(operationId);
    }

    @Nullable
    public OperationRecord operationByProfile(@Nonnull String profileId) {
        return current.get().operationByProfile(profileId);
    }

    @Nullable
    public OperationRecord operationAt(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                       int residentSlot) {
        return current.get().operationAt(authorityKey, residentSlot);
    }

    @Nullable
    public OperationRecord operationByUuid(@Nonnull UUID npcUuid) {
        return current.get().operationByUuid(npcUuid);
    }

    @Nonnull
    private RebuildResult reject(@Nullable String detail) {
        revokeTrust();
        return new RebuildResult(RebuildStatus.REJECTED,
                detail == null || detail.isBlank()
                        ? "coop_lifecycle_operation_index_rebuild_rejected"
                        : detail);
    }

    @Nonnull
    private static String detail(@Nonnull RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "coop_lifecycle_operation_index_rebuild_rejected"
                : message;
    }

    /** Immutable operation view safe to retain for one runtime decision. */
    public static final class Snapshot {
        private final long revision;
        private final boolean trusted;
        private final List<OperationRecord> operations;
        private final Map<String, OperationRecord> operationsById;
        private final Map<String, OperationRecord> operationsByProfile;
        private final Map<String, OperationRecord> operationsBySlot;
        private final Map<UUID, OperationRecord> operationsByUuid;

        private Snapshot(long revision,
                         boolean trusted,
                         List<OperationRecord> operations,
                         Map<String, OperationRecord> operationsById,
                         Map<String, OperationRecord> operationsByProfile,
                         Map<String, OperationRecord> operationsBySlot,
                         Map<UUID, OperationRecord> operationsByUuid) {
            this.revision = revision;
            this.trusted = trusted;
            this.operations = List.copyOf(operations);
            this.operationsById = Map.copyOf(operationsById);
            this.operationsByProfile = Map.copyOf(operationsByProfile);
            this.operationsBySlot = Map.copyOf(operationsBySlot);
            this.operationsByUuid = Map.copyOf(operationsByUuid);
        }

        @Nonnull
        private static Snapshot empty() {
            return new Snapshot(0L, false, List.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        @Nonnull
        private static Snapshot build(long revision, List<OperationRecord> sourceOperations) {
            ArrayList<OperationRecord> operations = new ArrayList<>(sourceOperations);
            operations.sort(OPERATION_ORDER);

            LinkedHashMap<String, OperationRecord> operationsById = new LinkedHashMap<>();
            LinkedHashMap<String, OperationRecord> operationsByProfile = new LinkedHashMap<>();
            LinkedHashMap<String, OperationRecord> operationsBySlot = new LinkedHashMap<>();
            LinkedHashMap<UUID, OperationRecord> operationsByUuid = new LinkedHashMap<>();
            for (OperationRecord operation : operations) {
                validateActiveOperation(operation);
                putUnique(operationsById, operation.operationId(), operation, "operation_id");
                putUnique(operationsByProfile, operation.profileId(), operation, "operation_profile");
                putUnique(operationsBySlot,
                        operation.authorityKey().slotKey(operation.residentSlot()),
                        operation,
                        "operation_slot");
                putUuidAliases(operationsByUuid, operation);
            }
            return new Snapshot(revision, true, operations, operationsById, operationsByProfile,
                    operationsBySlot, operationsByUuid);
        }

        @Nonnull
        private Snapshot withTrusted(boolean replacementTrust) {
            if (trusted == replacementTrust) {
                return this;
            }
            return new Snapshot(revision, replacementTrust, operations, operationsById,
                    operationsByProfile, operationsBySlot, operationsByUuid);
        }

        public long revision() {
            return revision;
        }

        public boolean trusted() {
            return trusted;
        }

        @Nonnull
        public List<OperationRecord> operations() {
            return operations;
        }

        @Nullable
        public OperationRecord operationById(@Nonnull String operationId) {
            return operationsById.get(operationId);
        }

        @Nullable
        public OperationRecord operationByProfile(@Nonnull String profileId) {
            return operationsByProfile.get(profileId);
        }

        @Nullable
        public OperationRecord operationAt(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                           int residentSlot) {
            return residentSlot < 0 ? null : operationsBySlot.get(authorityKey.slotKey(residentSlot));
        }

        @Nullable
        public OperationRecord operationByUuid(@Nonnull UUID npcUuid) {
            return operationsByUuid.get(npcUuid);
        }

        private static void validateActiveOperation(@Nullable OperationRecord operation) {
            if (operation == null) {
                throw invalid("null_active_coop_lifecycle_operation");
            }
            requireCanonicalText(operation.operationId(), "operation_id");
            requireCanonicalText(operation.profileId(), "profile_id");
            requireCanonicalCoopId(operation.coopId());
            if (operation.kind() == null || operation.state() == null
                    || operation.authorityKey() == null || operation.residentSlot() < 0
                    || operation.expectedResidentGeneration() < 0L || operation.generation() < 0L
                    || operation.retryCount() < 0) {
                throw invalid("invalid_active_coop_lifecycle_operation:" + operation.operationId());
            }
            if (!operation.active() || operation.completedAtMs() != 0L) {
                throw invalid("terminal_or_inactive_coop_lifecycle_operation:" + operation.operationId());
            }
            requireCanonicalSnapshotHash(operation);
            validateStateAndGeneration(operation);
            validateUuidShape(operation);
        }

        private static void validateStateAndGeneration(OperationRecord operation) {
            long expectedOperationGeneration;
            if (operation.kind() == OperationKind.CAPTURE) {
                if (operation.expectedResidentGeneration() == Long.MAX_VALUE) {
                    throw invalid("invalid_capture_resident_generation:" + operation.operationId());
                }
                expectedOperationGeneration = switch (operation.state()) {
                    case PREPARED -> 0L;
                    case SLOT_COMMITTED -> 1L;
                    case SOURCE_RETIRE_REQUESTED -> 2L;
                    default -> -1L;
                };
            } else if (operation.kind() == OperationKind.RELEASE) {
                if (operation.expectedResidentGeneration() > Long.MAX_VALUE - 2L) {
                    throw invalid("invalid_release_resident_generation:" + operation.operationId());
                }
                expectedOperationGeneration = switch (operation.state()) {
                    case PREPARED -> 0L;
                    case SPAWN_CLAIMED -> 1L;
                    case PROJECTION_CREATED -> 2L;
                    default -> -1L;
                };
            } else {
                expectedOperationGeneration = -1L;
            }
            if (expectedOperationGeneration < 0L
                    || operation.generation() != expectedOperationGeneration) {
                throw invalid("invalid_coop_lifecycle_state_generation:" + operation.operationId());
            }
        }

        private static void validateUuidShape(OperationRecord operation) {
            if (operation.kind() == OperationKind.CAPTURE) {
                requireUuid(operation.sourceNpcUuid(), operation.operationId(), "source");
                if (operation.plannedTargetUuid() != null || operation.actualTargetUuid() != null) {
                    throw invalid("invalid_capture_uuid_shape:" + operation.operationId());
                }
                return;
            }
            if (operation.sourceNpcUuid() != null) {
                throw invalid("invalid_release_uuid_shape:" + operation.operationId());
            }
            requireUuid(operation.plannedTargetUuid(), operation.operationId(), "planned_target");
            if (operation.state() == OperationState.PROJECTION_CREATED) {
                requireUuid(operation.actualTargetUuid(), operation.operationId(), "actual_target");
                if (!operation.plannedTargetUuid().equals(operation.actualTargetUuid())) {
                    throw invalid("release_projection_uuid_mismatch:" + operation.operationId());
                }
            } else if (operation.actualTargetUuid() != null) {
                throw invalid("invalid_release_uuid_shape:" + operation.operationId());
            }
        }

        private static void requireCanonicalSnapshotHash(OperationRecord operation) {
            String snapshotHash = operation.snapshotHash();
            if (snapshotHash == null || !snapshotHash.matches("[0-9a-f]{64}")) {
                throw invalid("invalid_coop_lifecycle_snapshot_hash:" + operation.operationId());
            }
        }

        private static void requireUuid(@Nullable UUID uuid, String operationId, String field) {
            if (uuid == null || NIL_UUID.equals(uuid)) {
                throw invalid("invalid_" + field + "_uuid:" + operationId);
            }
        }

        private static void putUuidAliases(Map<UUID, OperationRecord> target,
                                           OperationRecord operation) {
            HashMap<UUID, Boolean> aliases = new HashMap<>();
            if (operation.sourceNpcUuid() != null) {
                aliases.put(operation.sourceNpcUuid(), Boolean.TRUE);
            }
            if (operation.plannedTargetUuid() != null) {
                aliases.put(operation.plannedTargetUuid(), Boolean.TRUE);
            }
            if (operation.actualTargetUuid() != null) {
                aliases.put(operation.actualTargetUuid(), Boolean.TRUE);
            }
            for (UUID alias : aliases.keySet()) {
                OperationRecord previous = target.putIfAbsent(alias, operation);
                if (previous != null && !previous.operationId().equals(operation.operationId())) {
                    throw invalid("duplicate_coop_lifecycle_uuid:" + alias);
                }
            }
        }

        private static <K> void putUnique(Map<K, OperationRecord> target,
                                          K key,
                                          OperationRecord operation,
                                          String assignment) {
            OperationRecord previous = target.putIfAbsent(key, operation);
            if (previous != null) {
                throw invalid("duplicate_" + assignment + ":" + key);
            }
        }

        private static void requireCanonicalText(@Nullable String value, String field) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw invalid("invalid_coop_lifecycle_" + field);
            }
        }

        private static void requireCanonicalCoopId(@Nullable String coopId) {
            if (coopId == null || coopId.isBlank()
                    || !coopId.equals(coopId.trim().toLowerCase(Locale.ROOT))) {
                throw invalid("invalid_coop_lifecycle_coop_id");
            }
        }

        private static IllegalArgumentException invalid(@Nonnull String detail) {
            return new IllegalArgumentException(Objects.requireNonNull(detail));
        }
    }
}
