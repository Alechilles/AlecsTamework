package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Completion-aware persistence boundary for replay-safe vanilla-to-managed coop imports.
 *
 * <p>The repository owns the immutable pre-import journal and never treats a queued write as a
 * commit. A source can authorize vanilla neutralization only after its exact durable disposition
 * bindings exist. Final authority publication is guarded by a SQL invariant over every source.</p>
 */
public final class ManagedCoopImportRepository {
    public enum SessionState {
        ACTIVE,
        FINALIZED_MANAGED,
        FINALIZED_CONFLICT
    }

    public enum DispositionKind {
        MATCHED,
        IMPORTED,
        QUARANTINED
    }

    public enum NeutralizationState {
        NOT_AUTHORIZED,
        AUTHORIZED,
        VERIFIED_ABSENT,
        NOT_REQUIRED
    }

    public enum MutationStatus {
        APPLIED,
        IDEMPOTENT,
        NOT_FOUND,
        CONFLICT,
        INVARIANT_BLOCKED
    }

    /** Complete immutable coop-level audit envelope captured before any import mutation. */
    public record SessionEnvelope(@Nonnull String sessionId,
                                  @Nonnull ManagedCoopAuthorityKey authorityKey,
                                  @Nonnull String coopId,
                                  int auditVersion,
                                  @Nonnull String auditFingerprint,
                                  @Nonnull String auditEnvelopeJson,
                                  @Nonnull String auditEnvelopeHash,
                                  @Nonnull String layoutId,
                                  @Nullable String coopAssetId,
                                  @Nonnull String residentListClassName,
                                  @Nonnull String producePayload,
                                  @Nonnull String produceFingerprint,
                                  @Nonnull String beginCommandId,
                                  long createdAtMs) {
        public SessionEnvelope {
            sessionId = ManagedCoopImportValidation.text(sessionId, "sessionId");
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = ManagedCoopImportValidation.text(coopId, "coopId");
            if (auditVersion < 1) {
                throw new IllegalArgumentException("auditVersion must be positive");
            }
            auditFingerprint = ManagedCoopImportValidation.hash(auditFingerprint, "auditFingerprint");
            auditEnvelopeJson = ManagedCoopImportValidation.payload(auditEnvelopeJson, "auditEnvelopeJson");
            auditEnvelopeHash = ManagedCoopImportValidation.contentHash(
                    auditEnvelopeHash, auditEnvelopeJson, "auditEnvelopeHash");
            layoutId = ManagedCoopImportValidation.text(layoutId, "layoutId");
            coopAssetId = ManagedCoopImportValidation.optionalText(coopAssetId);
            residentListClassName = ManagedCoopImportValidation.text(
                    residentListClassName, "residentListClassName");
            producePayload = ManagedCoopImportValidation.payload(producePayload, "producePayload");
            produceFingerprint = ManagedCoopImportValidation.contentHash(
                    produceFingerprint, producePayload, "produceFingerprint");
            beginCommandId = ManagedCoopImportValidation.hash(beginCommandId, "beginCommandId");
            ManagedCoopImportValidation.eventTime(createdAtMs, "createdAtMs");
        }
    }

    /** Complete immutable evidence for one stable source ID within a session. */
    public record SourceEvidence(@Nonnull String sourceId,
                                 @Nonnull String sourceFingerprint,
                                 @Nonnull String sourceEnvelopeJson,
                                 @Nonnull String sourceEnvelopeHash,
                                 @Nonnull String sourcePayload,
                                 @Nonnull String sourcePayloadHash,
                                 @Nonnull String locatorHintsJson,
                                 @Nonnull String locatorHintsHash,
                                 int sourceSlot,
                                 int sourceOrder,
                                 boolean metadataPresent,
                                 boolean persistentRefPresent,
                                 @Nullable UUID persistentUuid,
                                 boolean deployedToWorld,
                                 @Nullable String lastProduced,
                                 @Nullable String profileAtAuditId,
                                 @Nullable String roleId,
                                 @Nullable String displayName,
                                 @Nonnull String managedSnapshotJson,
                                 @Nonnull String managedSnapshotHash,
                                 int managedSnapshotVersion,
                                 @Nonnull String unavailableFieldsJson) {
        public SourceEvidence {
            sourceId = ManagedCoopImportValidation.text(sourceId, "sourceId");
            sourceFingerprint = ManagedCoopImportValidation.hash(
                    sourceFingerprint, "sourceFingerprint");
            sourceEnvelopeJson = ManagedCoopImportValidation.payload(
                    sourceEnvelopeJson, "sourceEnvelopeJson");
            sourceEnvelopeHash = ManagedCoopImportValidation.contentHash(
                    sourceEnvelopeHash, sourceEnvelopeJson, "sourceEnvelopeHash");
            sourcePayload = ManagedCoopImportValidation.payload(sourcePayload, "sourcePayload");
            sourcePayloadHash = ManagedCoopImportValidation.contentHash(
                    sourcePayloadHash, sourcePayload, "sourcePayloadHash");
            locatorHintsJson = ManagedCoopImportValidation.payload(
                    locatorHintsJson, "locatorHintsJson");
            locatorHintsHash = ManagedCoopImportValidation.contentHash(
                    locatorHintsHash, locatorHintsJson, "locatorHintsHash");
            if (sourceSlot < 0 || sourceOrder < 0) {
                throw new IllegalArgumentException("source slot and order must not be negative");
            }
            if (persistentRefPresent != (persistentUuid != null)) {
                throw new IllegalArgumentException("persistent reference evidence is inconsistent");
            }
            lastProduced = ManagedCoopImportValidation.optionalText(lastProduced);
            profileAtAuditId = ManagedCoopImportValidation.optionalText(profileAtAuditId);
            roleId = ManagedCoopImportValidation.optionalText(roleId);
            displayName = ManagedCoopImportValidation.optionalText(displayName);
            managedSnapshotJson = ManagedCoopImportValidation.payload(
                    managedSnapshotJson, "managedSnapshotJson");
            managedSnapshotHash = ManagedCoopImportValidation.contentHash(
                    managedSnapshotHash, managedSnapshotJson, "managedSnapshotHash");
            if (managedSnapshotVersion < 1) {
                throw new IllegalArgumentException("managedSnapshotVersion must be positive");
            }
            unavailableFieldsJson = ManagedCoopImportValidation.payload(
                    unavailableFieldsJson, "unavailableFieldsJson");
        }
    }

    public record BeginSessionRequest(@Nonnull SessionEnvelope envelope,
                                      @Nonnull List<SourceEvidence> sources) {
        public BeginSessionRequest {
            Objects.requireNonNull(envelope, "envelope");
            sources = immutableSources(sources);
        }
    }

    public record SessionRecord(@Nonnull SessionEnvelope envelope,
                                int sourceCount,
                                @Nonnull SessionState state,
                                boolean active,
                                @Nullable String finalCommandId,
                                long updatedAtMs,
                                long finalizedAtMs,
                                @Nullable String lastError) {
    }

    public record SourceRecord(@Nonnull String sessionId,
                               @Nonnull SourceEvidence evidence,
                               @Nullable DispositionKind disposition,
                               @Nullable String dispositionCommandId,
                               @Nullable String operationId,
                               @Nullable String residentId,
                               @Nullable String profileId,
                               @Nullable String conflictId,
                               @Nullable String conflictKind,
                               @Nonnull NeutralizationState neutralizationState,
                               @Nullable String neutralizationCommandId,
                               @Nullable String absenceProofJson,
                               @Nullable String absenceProofHash,
                               int absenceProofVersion,
                               long createdAtMs,
                               long dispositionAtMs,
                               long verifiedAbsentAtMs) {
    }

    /** Exact IDs that must exist by the end of the same SQL transaction. */
    public record DispositionBinding(@Nonnull String sessionId,
                                     @Nonnull String sourceId,
                                     @Nonnull String auditFingerprint,
                                     @Nonnull String sourceFingerprint,
                                     @Nonnull String commandId,
                                     @Nonnull DispositionKind disposition,
                                     @Nullable String operationId,
                                      @Nullable String residentId,
                                      @Nullable String profileId,
                                      @Nullable String conflictId,
                                      @Nullable String conflictKind,
                                      long boundAtMs) {
        public DispositionBinding {
            sessionId = ManagedCoopImportValidation.text(sessionId, "sessionId");
            sourceId = ManagedCoopImportValidation.text(sourceId, "sourceId");
            auditFingerprint = ManagedCoopImportValidation.hash(
                    auditFingerprint, "auditFingerprint");
            sourceFingerprint = ManagedCoopImportValidation.hash(
                    sourceFingerprint, "sourceFingerprint");
            commandId = ManagedCoopImportValidation.hash(commandId, "commandId");
            Objects.requireNonNull(disposition, "disposition");
            operationId = ManagedCoopImportValidation.optionalText(operationId);
            residentId = ManagedCoopImportValidation.optionalText(residentId);
            profileId = ManagedCoopImportValidation.optionalText(profileId);
            conflictId = ManagedCoopImportValidation.optionalText(conflictId);
            conflictKind = ManagedCoopImportValidation.optionalText(conflictKind);
            ManagedCoopImportValidation.dispositionShape(
                    disposition, operationId, residentId, profileId, conflictId, conflictKind);
            ManagedCoopImportValidation.eventTime(boundAtMs, "boundAtMs");
        }
    }

    /** Exact proof that the previously bound vanilla source entry is absent. */
    public record NeutralizationProof(@Nonnull String sessionId,
                                      @Nonnull String sourceId,
                                      @Nonnull String auditFingerprint,
                                      @Nonnull String sourceFingerprint,
                                      @Nonnull String sourcePayloadHash,
                                      int sourceSlot,
                                      int sourceOrder,
                                      @Nullable UUID persistentUuid,
                                      @Nonnull String commandId,
                                      @Nonnull String absenceProofJson,
                                      @Nonnull String absenceProofHash,
                                      int absenceProofVersion,
                                      long verifiedAtMs) {
        public NeutralizationProof {
            sessionId = ManagedCoopImportValidation.text(sessionId, "sessionId");
            sourceId = ManagedCoopImportValidation.text(sourceId, "sourceId");
            auditFingerprint = ManagedCoopImportValidation.hash(
                    auditFingerprint, "auditFingerprint");
            sourceFingerprint = ManagedCoopImportValidation.hash(
                    sourceFingerprint, "sourceFingerprint");
            sourcePayloadHash = ManagedCoopImportValidation.hash(
                    sourcePayloadHash, "sourcePayloadHash");
            if (sourceSlot < 0 || sourceOrder < 0) {
                throw new IllegalArgumentException("source slot and order must not be negative");
            }
            commandId = ManagedCoopImportValidation.hash(commandId, "commandId");
            absenceProofJson = ManagedCoopImportValidation.payload(
                    absenceProofJson, "absenceProofJson");
            absenceProofHash = ManagedCoopImportValidation.contentHash(
                    absenceProofHash, absenceProofJson, "absenceProofHash");
            if (absenceProofVersion < 1) {
                throw new IllegalArgumentException("absenceProofVersion must be positive");
            }
            ManagedCoopImportValidation.eventTime(verifiedAtMs, "verifiedAtMs");
        }
    }

    public record FinalizationRequest(@Nonnull String sessionId,
                                      @Nonnull ManagedCoopAuthorityKey authorityKey,
                                      @Nonnull String coopId,
                                      @Nonnull String auditFingerprint,
                                      @Nonnull String commandId,
                                      @Nonnull ManagedCoopResidentRepository.AuthorityState targetState,
                                      long finalizedAtMs) {
        public FinalizationRequest {
            sessionId = ManagedCoopImportValidation.text(sessionId, "sessionId");
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = ManagedCoopImportValidation.text(coopId, "coopId");
            auditFingerprint = ManagedCoopImportValidation.hash(
                    auditFingerprint, "auditFingerprint");
            commandId = ManagedCoopImportValidation.hash(commandId, "commandId");
            if (targetState != ManagedCoopResidentRepository.AuthorityState.TWORK_MANAGED
                    && targetState != ManagedCoopResidentRepository.AuthorityState.CONFLICT) {
                throw new IllegalArgumentException("import target must be managed or conflict");
            }
            ManagedCoopImportValidation.eventTime(finalizedAtMs, "finalizedAtMs");
        }
    }

    public record MutationResult(@Nonnull MutationStatus status,
                                 @Nullable SessionRecord session,
                                 @Nullable SourceRecord source,
                                 @Nullable String detail) {
        public boolean succeeded() {
            return status == MutationStatus.APPLIED || status == MutationStatus.IDEMPOTENT;
        }
    }

    /**
     * Next-slice hook for creating profile/resident/IMPORT-operation rows in the same transaction
     * as the source binding. The repository validates every supplied ID after this hook returns.
     */
    @FunctionalInterface
    public interface DispositionTransactionHook {
        void write(@Nonnull Connection connection, @Nonnull DispositionBinding binding)
                throws Exception;
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final ManagedCoopImportTransactions transactions = new ManagedCoopImportTransactions();
    private final ManagedCoopImportReader reader = new ManagedCoopImportReader();

    public ManagedCoopImportRepository(@Nonnull SqliteConnectionManager connectionManager,
                                       @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> beginSession(
            @Nonnull BeginSessionRequest request) {
        Objects.requireNonNull(request, "request");
        return writeQueue.submitTracked(
                "managed_coop_import_begin",
                connection -> transactions.begin(connection, request),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> bindDisposition(
            @Nonnull DispositionBinding binding) {
        return bindDispositionAtomically(binding, (connection, ignored) -> { });
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> bindDispositionAtomically(
            @Nonnull DispositionBinding binding,
            @Nonnull DispositionTransactionHook hook) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(hook, "hook");
        return writeQueue.submitTracked(
                "managed_coop_import_bind_disposition",
                connection -> transactions.bindDispositionAtomically(connection, binding, hook),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> recordVerifiedNeutralization(
            @Nonnull NeutralizationProof proof) {
        Objects.requireNonNull(proof, "proof");
        return writeQueue.submitTracked(
                "managed_coop_import_neutralization_verified",
                connection -> transactions.recordNeutralization(connection, proof),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> finalizeAuthority(
            @Nonnull FinalizationRequest request) {
        Objects.requireNonNull(request, "request");
        return writeQueue.submitTracked(
                "managed_coop_import_finalize",
                connection -> transactions.finalizeAuthority(connection, request),
                null
        );
    }

    @Nonnull
    public ManagedCoopReadResult<SessionRecord> loadActiveSession(
            @Nonnull ManagedCoopAuthorityKey authorityKey,
            @Nonnull String coopId) {
        if (authorityKey == null || coopId == null || coopId.isBlank()) {
            return ManagedCoopReadResult.invalidInput("authority_and_coop_required");
        }
        try (Connection connection = connectionManager.openConnection()) {
            SessionRecord record = reader.loadActive(connection, authorityKey, coopId);
            return record == null ? ManagedCoopReadResult.notFound()
                    : ManagedCoopReadResult.loaded(record);
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    @Nonnull
    public ManagedCoopReadResult<List<SourceRecord>> loadSources(@Nonnull String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ManagedCoopReadResult.invalidInput("session_id_required");
        }
        try (Connection connection = connectionManager.openConnection()) {
            SessionRecord session = reader.loadById(connection, sessionId.trim());
            if (session == null) {
                return ManagedCoopReadResult.notFound();
            }
            return ManagedCoopReadResult.loaded(reader.loadSources(connection, sessionId.trim()));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    private static List<SourceEvidence> immutableSources(List<SourceEvidence> sources) {
        Objects.requireNonNull(sources, "sources");
        Set<String> ids = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (SourceEvidence source : sources) {
            Objects.requireNonNull(source, "source");
            if (!ids.add(source.sourceId()) || !fingerprints.add(source.sourceFingerprint())
                    || !slots.add(source.sourceSlot()) || !orders.add(source.sourceOrder())) {
                throw new IllegalArgumentException("import sources must have stable unique identities");
            }
        }
        return sources.stream()
                .sorted(java.util.Comparator.comparingInt(SourceEvidence::sourceOrder)
                        .thenComparing(SourceEvidence::sourceId))
                .toList();
    }
}
