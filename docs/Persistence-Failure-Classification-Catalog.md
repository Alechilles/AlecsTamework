# Persistence Failure Classification Catalog

This catalog is the review surface for persistence failures that may close mutation authority.
`PersistenceFailureReasonCatalog` is the executable source of truth, and
`PersistenceFailureClassificationCatalogTest` requires every literal degradation/reporting reason
in production source to have an exact entry here and in code. Adding a new reason without choosing
its containment and recovery contract fails the test suite.

Player feedback is selected by classification and domain. Internal reason codes, exception class
names, paths, SQL text, and raw scope keys are not player-facing. Incident-backed denials may show
only the eight-character incident reference.

## Global storage authority

These enter read-only because canonical storage cannot be trusted or opened. They require the
storage probe (or an operator-reviewed restore for integrity loss) and are never cleared merely by
time or retry count.

- `legacy_dat_import_failed`
- `persistence_quarantine_durable_write_failed`
- `persistence_quarantine_index_reload_failed`
- `persistence_v7_registry_load_failed`
- `population_bootstrap_failed`
- `reconciliation_startup_failed`
- `sqlite_write_worker_failed`
- `write_commit_outcome_unknown`

`write_commit_outcome_unknown` is classified as `UNKNOWN_TRANSACTION_OUTCOME`; the others above
are `STORAGE_UNAVAILABLE`. The durable fence is global read-only plus read-back evidence for every
affected atomic task. `StorageRecoveryProbe` is the verifier.

## Evidence coverage

These keep storage healthy and deny only operations requiring breeding replay evidence.

- `breeding_replay_journal_load_failed`
- `breeding_replay_preparation_conflict`

They are `COVERAGE_UNAVAILABLE` with `AUTHORITY_NOT_READY`. Recovery requires a new, successfully
published breeding replay coverage generation; a successful generic database read is insufficient.

## Post-commit publication

SQLite is authoritative for these failures. The operation is treated as committed and its exact
profile/operation/domain scopes remain quarantined until canonical read-back and runtime-index
publication both succeed.

- `breeding_identity_durable_mark_failed`
- `coop_release_identity_durable_mark_failed`
- `managed_coop_release_finalized_index_refresh_failed`
- `managed_coop_release_finalized_index_refresh_rejected`
- `public_population_commit_callback_failed`
- `spawn_identity_durable_mark_failed`

They are `POST_COMMIT_PUBLICATION_FAILURE` with `SCOPED_QUARANTINE` and use
`PostCommitPublicationRecoveryVerifier` (or the registered domain-specific equivalent).

## Scoped apply ambiguity

These may have retained a source, reservation, live projection, or canonical effect whose outcome
cannot safely be guessed. They retain the prepared journal and/or a durable v7 quarantine. Recovery
must inspect the named domain's canonical and physical evidence; the operator cannot force-clear it.

### Breeding

- `breeding_live_identity_remap_failed`
- `breeding_population_commit_ambiguous`
- `breeding_population_commit_failed`
- `breeding_population_commit_stage_missing`
- `breeding_population_commit_start_failed`
- `breeding_population_conflict_cancel_failed`
- `breeding_population_conflict_cancel_missing`
- `breeding_population_conflict_cancel_start_failed`
- `breeding_population_handoff_cancel_failed`
- `breeding_population_handoff_cancel_missing`
- `breeding_population_handoff_cancel_start_failed`
- `breeding_population_materialized_state_conflict`
- `breeding_prepared_identity_release_failed`

### Coop release and managed-coop projection

- `coop_release_live_identity_remap_failed`
- `coop_release_population_commit_failed`
- `coop_release_population_commit_stage_missing`
- `coop_release_population_commit_start_failed`
- `coop_release_prepared_identity_cancel_failed`
- `coop_release_prepared_identity_cancel_stage_missing`
- `coop_release_prepared_identity_release_failed`
- `managed_coop_persisted_projection_evidence_changed`
- `managed_coop_persisted_projection_population_cancel_failed`
- `managed_coop_persisted_projection_population_cancel_missing`
- `managed_coop_persisted_projection_population_cancel_start_failed`
- `managed_coop_population_cancel_failed`
- `managed_coop_population_cancel_stage_missing`
- `managed_coop_population_cancel_start_failed`
- `managed_coop_population_claim_failed`
- `managed_coop_population_commit_failed`
- `managed_coop_population_commit_identity_mismatch`
- `managed_coop_population_commit_stage_missing`
- `managed_coop_population_commit_start_failed`
- `managed_coop_population_holder_write_failed`
- `managed_coop_population_preparation_completion_ambiguous`
- `managed_coop_population_preparation_failed_ambiguous`
- `managed_coop_population_preparation_future_missing`
- `managed_coop_population_preparation_inconsistent`
- `managed_coop_population_preparation_result_missing`
- `managed_coop_population_preparation_retained_ambiguous`
- `managed_coop_population_preparation_start_ambiguous`
- `managed_coop_population_prepare_ambiguous`
- `managed_coop_population_prepare_failed`
- `managed_coop_population_prepare_handle_missing`
- `managed_coop_population_prepare_identity_mismatch`
- `managed_coop_population_prepare_result_missing`
- `managed_coop_population_prepare_stage_missing`
- `managed_coop_population_prepare_start_failed`
- `managed_coop_release_lifecycle_rollback_failed`
- `managed_coop_release_live_projection_unresolved`
- `managed_coop_release_post_spawn_completion_ambiguous`
- `managed_coop_release_post_spawn_identity_ambiguous`
- `managed_coop_release_post_spawn_probe_failed`
- `managed_coop_release_pre_spawn_identity_ambiguous`
- `managed_coop_release_preparation_rollback_claim_invalid`
- `managed_coop_release_preparation_rollback_failed`
- `managed_coop_release_preparation_rollback_stage_missing`
- `managed_coop_release_preparation_rollback_start_failed`

### Owner/public population and spawn

- `public_population_batch_registration_failed`
- `public_population_cancel_failed`
- `public_population_commit_failed`
- `spawn_canceled_identity_release_failed`
- `spawn_identity_remap_failed`
- `spawn_population_commit_failed`
- `spawn_population_commit_stage_missing`
- `spawn_population_commit_start_failed`
- `spawn_provisional_identity_cancel_stage_missing`

All entries in this section are `SCOPED_APPLY_AMBIGUITY` with `SCOPED_QUARANTINE`. When only a
feature domain can be bounded, that feature is fenced without degrading the shared claim or owner
indexes. When exact profile/operation evidence is available, those narrower scopes are required.

## Direct global-degradation ownership

`PersistenceDegradationArchitectureTest` permits direct global read-only transitions only in:

- storage health, writer, batch executor, and persistence composition;
- the structured incident reporter and v7 registry bootstrap;
- owner/claim canonical bootstrap and startup reconciliation;
- the explicitly named legacy compatibility bridge.

Domain code reports `PersistenceFailureContext`. Three legacy constructors remain supported for
tests/binary compatibility through `LegacyGlobalPersistenceFailureBridge`; production composition
provides the v7 reporter and does not reach that bridge.

## Migration and Hytale backup boundary

Tamework initialization invokes `SqliteMigrationBackupService.backupBeforeVersion` before schema
v7 migration. That method performs SQLite `VACUUM INTO`, verifies `PRAGMA integrity_check`, hashes
the snapshot, and writes a manifest whose scope is `tamework_sqlite_only`.

Tamework does not walk, copy, archive, or retain the Hytale save and does not automatically invoke
Hytale's universe backup operation. Hytale and the server operator own complete-save backups.
`SqliteMigrationBackupBoundaryArchitectureTest` and `SqliteMigrationBackupServiceTest` enforce this
boundary.
