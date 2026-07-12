package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Typed facade that binds import sources to exact managed rows inside the import savepoint.
 *
 * <p>Runtime code cannot supply a free-form transaction hook. The focused row stores either
 * create/verify the complete reference graph, or the repository rolls the savepoint back and
 * leaves source neutralization unauthorized.</p>
 */
public final class ManagedCoopImportDispositionWriter {
    public record ManagedRows(@Nonnull DispositionBinding binding,
                              @Nonnull SourceEvidence source,
                              @Nonnull ManagedCoopAuthorityKey authorityKey,
                              @Nonnull String coopId,
                              int residentSlot,
                              @Nonnull UUID residentUuid,
                              @Nonnull String roleId,
                              @Nonnull String snapshotJson,
                              @Nonnull String snapshotHash,
                              int snapshotVersion,
                              @Nonnull ResidentState residentState,
                              long residentGeneration,
                              boolean createResident) {
        public ManagedRows {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = text(coopId, "coopId").toLowerCase(Locale.ROOT);
            Objects.requireNonNull(residentUuid, "residentUuid");
            roleId = text(roleId, "roleId");
            snapshotJson = raw(snapshotJson, "snapshotJson");
            snapshotHash = hash(snapshotHash, "snapshotHash");
            Objects.requireNonNull(residentState, "residentState");
            validateManagedShape(
                    binding,
                    source,
                    residentSlot,
                    snapshotHash,
                    snapshotVersion,
                    residentState,
                    residentGeneration,
                    createResident
            );
        }
    }

    public record QuarantineRows(@Nonnull DispositionBinding binding,
                                 @Nonnull SourceEvidence source,
                                 @Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId) {
        public QuarantineRows {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = text(coopId, "coopId").toLowerCase(Locale.ROOT);
            if (binding.disposition() != DispositionKind.QUARANTINED
                    || binding.conflictId() == null || binding.conflictKind() == null
                    || !binding.sourceId().equals(source.sourceId())
                    || !binding.sourceFingerprint().equals(source.sourceFingerprint())) {
                throw new IllegalArgumentException("quarantine binding identity is incomplete");
            }
        }
    }

    private final ManagedCoopImportRepository repository;
    private final ManagedCoopImportManagedRowStore managedRows =
            new ManagedCoopImportManagedRowStore();
    private final ManagedCoopImportConflictRowStore conflictRows =
            new ManagedCoopImportConflictRowStore();

    public ManagedCoopImportDispositionWriter(@Nonnull ManagedCoopImportRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** Atomically binds an imported or exact-matched source to durable managed rows. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> bindManaged(
            @Nonnull ManagedRows rows) {
        Objects.requireNonNull(rows, "rows");
        return repository.bindDispositionAtomically(
                rows.binding(),
                (connection, binding) -> managedRows.write(connection, rows, binding)
        );
    }

    /** Atomically inserts the unresolved conflict and quarantines its source without deletion. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> bindQuarantined(
            @Nonnull QuarantineRows rows) {
        Objects.requireNonNull(rows, "rows");
        return repository.bindDispositionAtomically(
                rows.binding(),
                (connection, binding) -> conflictRows.write(connection, rows, binding)
        );
    }

    @Nonnull
    public static String operationId(@Nonnull String sessionId,
                                     @Nonnull String sourceId,
                                     @Nonnull String profileId,
                                     int residentSlot) {
        return "managed-coop-import-operation:" + sha256(
                token(sessionId) + token(sourceId) + token(profileId)
                        + token(Integer.toString(residentSlot)));
    }

    @Nonnull
    public static String conflictId(@Nonnull String sessionId,
                                    @Nonnull String sourceId,
                                    @Nonnull String conflictKind) {
        return "managed-coop-import-conflict:" + sha256(
                token(sessionId) + token(sourceId) + token(conflictKind));
    }

    @Nonnull
    public static String commandId(@Nonnull String sessionId,
                                   @Nonnull String sourceId,
                                   @Nonnull String disposition) {
        return sha256(token(sessionId) + token(sourceId) + token(disposition));
    }

    private static void validateManagedShape(DispositionBinding binding,
                                             SourceEvidence source,
                                             int residentSlot,
                                             String snapshotHash,
                                             int snapshotVersion,
                                             ResidentState residentState,
                                             long residentGeneration,
                                             boolean createResident) {
        if (residentSlot < 0 || snapshotVersion < 1 || residentGeneration < 0L) {
            throw new IllegalArgumentException("managed import row numbers are invalid");
        }
        DispositionKind expected = createResident
                ? DispositionKind.IMPORTED : DispositionKind.MATCHED;
        if (binding.disposition() != expected || binding.operationId() == null
                || binding.profileId() == null || binding.residentId() == null
                || !binding.sourceId().equals(source.sourceId())
                || !binding.sourceFingerprint().equals(source.sourceFingerprint())) {
            throw new IllegalArgumentException("managed import binding identity is incomplete");
        }
        if (createResident
                && (!snapshotHash.equals(source.managedSnapshotHash())
                || residentGeneration != 0L)) {
            throw new IllegalArgumentException("new imported resident snapshot/generation is invalid");
        }
        ResidentState expectedState = source.deployedToWorld()
                ? ResidentState.DEPLOYED : ResidentState.HOUSED;
        if (residentState != expectedState) {
            throw new IllegalArgumentException("resident state does not match audited source");
        }
    }

    private static String token(String value) {
        String normalized = text(value, "identity token");
        return normalized.length() + ":" + normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String raw(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String hash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical lowercase SHA-256");
        }
        return value;
    }
}
