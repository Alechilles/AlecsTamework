package com.alechilles.alecstamework.persistence.incidents;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Exact inventory of reasons allowed to close or quarantine persistence mutation authority. */
public final class PersistenceFailureReasonCatalog {
    private static final Map<String, Entry> ENTRIES = build();

    private PersistenceFailureReasonCatalog() {
    }

    @Nonnull
    public static Optional<Entry> find(@Nonnull String reasonCode) {
        return Optional.ofNullable(ENTRIES.get(normalize(reasonCode)));
    }

    @Nonnull
    public static Map<String, Entry> all() {
        return ENTRIES;
    }

    /** Normalizes a caller-supplied reason for durable catalog and incident use. */
    @Nonnull
    public static String normalizeCode(@Nonnull String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reasonCode");
        }
        return reason.trim()
                .replace('-', '_')
                .replace(':', '_')
                .replace(';', '_')
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Entry> build() {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        register(entries, PersistenceFailureClass.STORAGE_UNAVAILABLE,
                PersistenceDisposition.GLOBAL_READ_ONLY, "global storage authority",
                "storage read-only fence", "StorageRecoveryProbe",
                "tamework.ui.notifications.persistence.globalReadOnly",
                "legacy_dat_import_failed",
                "persistence_quarantine_durable_write_failed",
                "persistence_quarantine_index_reload_failed",
                "persistence_v7_registry_load_failed",
                "population_bootstrap_failed",
                "persistence_shutdown_checkpoint_failed",
                "reconciliation_startup_failed",
                "sqlite_write_worker_failed");
        register(entries, PersistenceFailureClass.UNKNOWN_TRANSACTION_OUTCOME,
                PersistenceDisposition.GLOBAL_READ_ONLY, "every task in the atomic batch",
                "write outcome read-back fence", "StorageRecoveryProbe",
                "tamework.ui.notifications.persistence.globalReadOnly",
                "write_commit_outcome_unknown");
        register(entries, PersistenceFailureClass.COVERAGE_UNAVAILABLE,
                PersistenceDisposition.AUTHORITY_NOT_READY, "breeding replay evidence dimension",
                "coverage generation and incident fence", "BreedingReplayCoverageVerifier",
                "tamework.ui.notifications.persistence.authorityNotReady",
                "breeding_replay_journal_load_failed",
                "breeding_replay_preparation_conflict");
        register(entries, PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE,
                PersistenceDisposition.SCOPED_QUARANTINE, "exact operation/profile/domain scopes",
                "committed journal plus v7 quarantine", "PostCommitPublicationRecoveryVerifier",
                "tamework.ui.notifications.persistence.profileProtected",
                "breeding_identity_durable_mark_failed",
                "coop_release_identity_durable_mark_failed",
                "managed_coop_release_finalized_index_refresh_failed",
                "managed_coop_release_finalized_index_refresh_rejected",
                "public_population_commit_callback_failed",
                "spawn_identity_durable_mark_failed");
        register(entries, PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION,
                PersistenceDisposition.SCOPED_QUARANTINE, "exact canonical profile scope",
                "canonical alias plus v7 profile quarantine", "domain canonical evidence verifier",
                "tamework.ui.notifications.persistence.profileProtected",
                "reconciliation_evidence_conflict_conflicting_dormant_lifecycle_evidence",
                "reconciliation_evidence_conflict_conflicting_owner_evidence",
                "reconciliation_evidence_conflict_conflicting_physical_death_evidence",
                "reconciliation_evidence_conflict_duplicate_physical_identity");
        register(entries, PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY,
                PersistenceDisposition.SCOPED_QUARANTINE, "exact operation/profile/domain scopes",
                "prepared journal or v7 quarantine", "domain canonical evidence verifier",
                "tamework.ui.notifications.persistence.profileProtected",
                "breeding_live_identity_remap_failed",
                "breeding_population_commit_ambiguous",
                "breeding_population_commit_failed",
                "breeding_population_commit_stage_missing",
                "breeding_population_commit_start_failed",
                "breeding_population_conflict_cancel_failed",
                "breeding_population_conflict_cancel_missing",
                "breeding_population_conflict_cancel_start_failed",
                "breeding_population_handoff_cancel_failed",
                "breeding_population_handoff_cancel_missing",
                "breeding_population_handoff_cancel_start_failed",
                "breeding_population_materialized_state_conflict",
                "breeding_prepared_identity_release_failed",
                "coop_release_live_identity_remap_failed",
                "coop_release_population_commit_failed",
                "coop_release_population_commit_stage_missing",
                "coop_release_population_commit_start_failed",
                "coop_release_prepared_identity_cancel_failed",
                "coop_release_prepared_identity_cancel_stage_missing",
                "coop_release_prepared_identity_release_failed",
                "managed_coop_persisted_projection_evidence_changed",
                "managed_coop_persisted_projection_population_cancel_failed",
                "managed_coop_persisted_projection_population_cancel_missing",
                "managed_coop_persisted_projection_population_cancel_start_failed",
                "managed_coop_population_cancel_failed",
                "managed_coop_population_cancel_stage_missing",
                "managed_coop_population_cancel_start_failed",
                "managed_coop_population_claim_failed",
                "managed_coop_population_commit_failed",
                "managed_coop_population_commit_identity_mismatch",
                "managed_coop_population_commit_stage_missing",
                "managed_coop_population_commit_start_failed",
                "managed_coop_population_holder_write_failed",
                "managed_coop_population_preparation_completion_ambiguous",
                "managed_coop_population_preparation_failed_ambiguous",
                "managed_coop_population_preparation_future_missing",
                "managed_coop_population_preparation_inconsistent",
                "managed_coop_population_preparation_result_missing",
                "managed_coop_population_preparation_retained_ambiguous",
                "managed_coop_population_preparation_start_ambiguous",
                "managed_coop_population_prepare_ambiguous",
                "managed_coop_population_prepare_failed",
                "managed_coop_population_prepare_handle_missing",
                "managed_coop_population_prepare_identity_mismatch",
                "managed_coop_population_prepare_result_missing",
                "managed_coop_population_prepare_stage_missing",
                "managed_coop_population_prepare_start_failed",
                "managed_coop_release_lifecycle_rollback_failed",
                "managed_coop_release_live_projection_unresolved",
                "managed_coop_release_post_spawn_completion_ambiguous",
                "managed_coop_release_post_spawn_identity_ambiguous",
                "managed_coop_release_post_spawn_probe_failed",
                "managed_coop_release_pre_spawn_identity_ambiguous",
                "managed_coop_release_preparation_rollback_claim_invalid",
                "managed_coop_release_preparation_rollback_failed",
                "managed_coop_release_preparation_rollback_stage_missing",
                "managed_coop_release_preparation_rollback_start_failed",
                "public_population_batch_registration_failed",
                "public_population_cancel_failed",
                "public_population_commit_failed",
                "spawn_canceled_identity_release_failed",
                "spawn_identity_remap_failed",
                "spawn_population_commit_failed",
                "spawn_population_commit_stage_missing",
                "spawn_population_commit_start_failed",
                "spawn_provisional_identity_cancel_stage_missing");
        return Collections.unmodifiableMap(entries);
    }

    private static void register(Map<String, Entry> entries,
                                 PersistenceFailureClass failureClass,
                                 PersistenceDisposition disposition,
                                 String requiredScopes,
                                 String durableFence,
                                 String recoveryVerifier,
                                 String playerFeedbackKey,
                                 String... reasons) {
        for (String reason : reasons) {
            String normalized = normalize(reason);
            Entry entry = new Entry(failureClass, disposition, domain(normalized), requiredScopes,
                    durableFence, recoveryVerifier, playerFeedbackKey);
            if (entries.putIfAbsent(normalized, entry) != null) {
                throw new IllegalStateException("Duplicate persistence failure reason: " + normalized);
            }
        }
    }

    private static PersistenceDomain domain(String reason) {
        if (reason.startsWith("breeding_")) return PersistenceDomain.BREEDING;
        if (reason.startsWith("managed_coop_") || reason.startsWith("coop_")) {
            return PersistenceDomain.MANAGED_COOP_RELEASE;
        }
        if (reason.startsWith("spawn_")) return PersistenceDomain.TAMED_SPAWN;
        if (reason.startsWith("public_population_")) return PersistenceDomain.OWNER_MUTATION;
        return PersistenceDomain.STORAGE;
    }

    private static String normalize(String reason) {
        return normalizeCode(reason);
    }

    /** Reviewable containment and recovery contract for one stable reason code. */
    public record Entry(@Nonnull PersistenceFailureClass failureClass,
                        @Nonnull PersistenceDisposition disposition,
                        @Nonnull PersistenceDomain domain,
                        @Nonnull String requiredScopes,
                        @Nonnull String durableFence,
                        @Nonnull String recoveryVerifier,
                        @Nonnull String playerFeedbackKey) {
    }
}
