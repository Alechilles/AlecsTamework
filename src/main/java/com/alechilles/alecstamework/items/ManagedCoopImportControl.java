package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaImportInspectionService.ImportInspection;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Process-local confirmation gate for destructive vanilla-to-managed coop import progress.
 *
 * <p>Confirmations deliberately do not survive process restart. Each confirmation is bound to one
 * exact physical authority and the deterministic fingerprint from its latest read-only inspection.
 * A changed inspection revokes the old confirmation before another import write can be submitted.</p>
 */
public final class ManagedCoopImportControl {
    public enum ConfirmationStatus {
        CONFIRMED,
        NO_INSPECTION,
        NOT_APPROVABLE,
        FINGERPRINT_MISMATCH
    }

    public enum AuthorizationStatus {
        APPROVED,
        MISSING,
        FINGERPRINT_MISMATCH
    }

    public record ConfirmationResult(@Nonnull ConfirmationStatus status,
                                     @Nullable Approval approval,
                                     @Nullable String detail) {
        public ConfirmationResult {
            Objects.requireNonNull(status, "status");
        }

        public boolean confirmed() {
            return status == ConfirmationStatus.CONFIRMED;
        }
    }

    public record Approval(@Nonnull ManagedCoopAuthorityKey authorityKey,
                           @Nonnull String auditFingerprint,
                           @Nonnull String actor) {
        public Approval {
            Objects.requireNonNull(authorityKey, "authorityKey");
            auditFingerprint = normalizeFingerprint(auditFingerprint);
            actor = requireText(actor, "actor");
        }
    }

    private static final ManagedCoopImportControl SHARED = new ManagedCoopImportControl();

    private final Map<ManagedCoopAuthorityKey, ImportInspection> inspections = new HashMap<>();
    private final Map<ManagedCoopAuthorityKey, Approval> approvals = new HashMap<>();

    /** Returns the process-wide control used by the default managed-coop runtime composition. */
    @Nonnull
    public static ManagedCoopImportControl shared() {
        return SHARED;
    }

    /**
     * Publishes a fresh read-only inspection and revokes any approval that no longer matches it.
     */
    public synchronized void observe(@Nonnull ImportInspection inspection) {
        Objects.requireNonNull(inspection, "inspection");
        ManagedCoopAuthorityKey authorityKey = inspection.authorityKey();
        inspections.put(authorityKey, inspection);
        if (!inspection.approvalRequired() || inspection.auditFingerprint() == null) {
            approvals.remove(authorityKey);
            return;
        }
        Approval approval = approvals.get(authorityKey);
        if (approval != null
                && !approval.auditFingerprint().equals(inspection.auditFingerprint())) {
            approvals.remove(authorityKey);
        }
    }

    /** Confirms only the exact fingerprint exposed by the latest approvable inspection. */
    @Nonnull
    public synchronized ConfirmationResult confirm(
            @Nonnull ManagedCoopAuthorityKey authorityKey,
            @Nonnull String auditFingerprint,
            @Nonnull String actor) {
        Objects.requireNonNull(authorityKey, "authorityKey");
        String normalized = normalizeFingerprint(auditFingerprint);
        String normalizedActor = requireText(actor, "actor");
        ImportInspection inspection = inspections.get(authorityKey);
        if (inspection == null) {
            return new ConfirmationResult(
                    ConfirmationStatus.NO_INSPECTION, null, "read_only_inspection_required");
        }
        if (!inspection.approvalRequired() || inspection.auditFingerprint() == null) {
            return new ConfirmationResult(
                    ConfirmationStatus.NOT_APPROVABLE, null, "inspection_not_approvable");
        }
        if (!inspection.auditFingerprint().equals(normalized)) {
            return new ConfirmationResult(
                    ConfirmationStatus.FINGERPRINT_MISMATCH, null,
                    "inspection_fingerprint_mismatch");
        }
        Approval approval = new Approval(authorityKey, normalized, normalizedActor);
        approvals.put(authorityKey, approval);
        return new ConfirmationResult(ConfirmationStatus.CONFIRMED, approval, null);
    }

    /** Revalidates an approval immediately before an import step is allowed to advance. */
    @Nonnull
    public synchronized AuthorizationStatus authorize(
            @Nonnull ManagedCoopAuthorityKey authorityKey,
            @Nonnull String auditFingerprint) {
        Objects.requireNonNull(authorityKey, "authorityKey");
        String normalized = normalizeFingerprint(auditFingerprint);
        Approval approval = approvals.get(authorityKey);
        if (approval == null) {
            return AuthorizationStatus.MISSING;
        }
        if (!approval.auditFingerprint().equals(normalized)) {
            approvals.remove(authorityKey);
            return AuthorizationStatus.FINGERPRINT_MISMATCH;
        }
        return AuthorizationStatus.APPROVED;
    }

    public synchronized boolean hasApproval(@Nonnull ManagedCoopAuthorityKey authorityKey) {
        return approvals.containsKey(Objects.requireNonNull(authorityKey, "authorityKey"));
    }

    @Nonnull
    public synchronized Optional<Approval> approval(
            @Nonnull ManagedCoopAuthorityKey authorityKey) {
        return Optional.ofNullable(approvals.get(
                Objects.requireNonNull(authorityKey, "authorityKey")));
    }

    @Nonnull
    public synchronized Optional<ImportInspection> latestInspection(
            @Nonnull ManagedCoopAuthorityKey authorityKey) {
        return Optional.ofNullable(inspections.get(
                Objects.requireNonNull(authorityKey, "authorityKey")));
    }

    /** Revokes future progress without attempting to undo a write already submitted. */
    public synchronized boolean cancel(@Nonnull ManagedCoopAuthorityKey authorityKey) {
        return approvals.remove(Objects.requireNonNull(authorityKey, "authorityKey")) != null;
    }

    /** Explicitly removes both cached diagnostics and any approval for a retired authority. */
    public synchronized void remove(@Nonnull ManagedCoopAuthorityKey authorityKey) {
        ManagedCoopAuthorityKey key = Objects.requireNonNull(authorityKey, "authorityKey");
        approvals.remove(key);
        inspections.remove(key);
    }

    /** Shutdown/reload hook: no destructive confirmation may survive a runtime composition. */
    public synchronized void clearAll() {
        approvals.clear();
        inspections.clear();
    }

    private static String normalizeFingerprint(String value) {
        String normalized = requireText(value, "auditFingerprint").toLowerCase(Locale.ROOT);
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("auditFingerprint must be a SHA-256 value");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if ((character < '0' || character > '9')
                    && (character < 'a' || character > 'f')) {
                throw new IllegalArgumentException("auditFingerprint must be a SHA-256 value");
            }
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
