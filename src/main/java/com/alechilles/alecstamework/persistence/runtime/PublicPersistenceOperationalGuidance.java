package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import java.util.ArrayList;
import java.util.List;

/** Produces conservative operator actions without exposing persisted payloads. */
final class PublicPersistenceOperationalGuidance {
    private PublicPersistenceOperationalGuidance() {
    }

    static List<String> forStatus(
            PersistenceStartupReport startup,
            PublicPersistenceTarget target,
            boolean shutdownStarted,
            PublicPersistenceShutdownReport shutdown
    ) {
        ArrayList<String> guidance = new ArrayList<>();
        guidance.add("stop_server_before_persistence_file_changes");
        guidance.add(
                "backup_data_directory_with_database_wal_shm_and_engine_manifest"
        );
        if (target != null && target.origin()
                == PublicPersistenceTarget.Origin.IMPORTED_PUBLIC) {
            guidance.add(
                    "public_source_is_copy_imported_and_must_remain_untouched"
            );
        }
        if (startup.deferredNode()
                == PersistenceStartupNode.WAIT_WORLD_EVIDENCE
                || startup.deferredNode()
                == PersistenceStartupNode.RECONCILE_WORLD) {
            guidance.add(
                    "wait_for_required_worlds_then_resume_startup"
            );
        }
        if (startup.failedNode() != null
                || startup.readiness()
                == PersistenceReadinessLevel.GLOBAL_READ_ONLY) {
            guidance.add(
                    "preserve_current_files_and_collect_sanitized_diagnostics"
            );
            guidance.add(
                    "resolve_reported_failure_before_restarting_mutations"
            );
        }
        if (shutdownStarted && (shutdown == null || !shutdown.terminal())) {
            guidance.add(
                    "do_not_restart_until_ordered_shutdown_reaches_terminal_state"
            );
        }
        if (shutdown != null
                && shutdown.status()
                == PublicPersistenceShutdownReport.Status.COMPLETE_UNCLEAN) {
            guidance.add(
                    "verify_database_integrity_and_checkpoint_before_reopening"
            );
        }
        guidance.add(
                "legacy_rollback_requires_complete_pre_cutover_backup_restore"
        );
        return List.copyOf(guidance);
    }
}
