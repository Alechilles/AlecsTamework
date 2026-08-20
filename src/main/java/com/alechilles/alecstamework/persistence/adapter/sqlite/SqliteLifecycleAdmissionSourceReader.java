package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainPort;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Reads the canonical lifecycle, identity role, and committed domain rows together. */
final class SqliteLifecycleAdmissionSourceReader {
    private static final PersistenceReadKind READ_KIND =
            new PersistenceReadKind("lifecycle_admission_source");

    private final SqliteReadExecutor reads;

    SqliteLifecycleAdmissionSourceReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException("Lifecycle source read executor is required");
        }
        this.reads = reads;
    }

    @Nonnull
    CompletionStage<PersistenceReadResult<SourceReadModel>> findByProfile(
            @Nonnull ProfileId profileId
    ) {
        if (profileId == null) {
            throw new IllegalArgumentException("Profile ID is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                READ_KIND,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> read(connection, profileId)
        ));
    }

    private PersistenceReadResult<SourceReadModel> read(
            Connection connection,
            ProfileId profileId
    ) {
        CompanionIdentity identity = new SqliteCompanionIdentityStore(connection)
                .findProfile(profileId)
                .orElse(null);
        CompanionLifecycle lifecycle = new SqliteCompanionLifecycleStore(connection)
                .findByProfile(profileId)
                .orElse(null);
        if (identity == null && lifecycle == null) {
            return PersistenceReadResult.absent();
        }
        if (identity == null) {
            return failed("profile_identity_missing");
        }
        if (lifecycle == null) {
            return failed("profile_lifecycle_missing");
        }
        if (identity.roleId() == null || identity.roleId().isBlank()) {
            return failed("profile_role_missing");
        }
        PopulationDomainPort.ProfileEvidence evidence =
                new SqlitePopulationDomainStore(connection)
                        .profileEvidence(profileId, null);
        if (!evidence.currentOperationPending().isEmpty()
                || !evidence.foreignPending().isEmpty()) {
            return failed("profile_domain_pending");
        }
        return PersistenceReadResult.found(
                new SourceReadModel(
                        lifecycle,
                        identity.roleId(),
                        evidence.committed()
                ),
                lifecycle.revision().value()
        );
    }

    private PersistenceReadResult<SourceReadModel> failed(String code) {
        return PersistenceReadResult.failed(new StorageFailure(
                StorageFailureKind.CORRUPT,
                code,
                READ_KIND.value(),
                false,
                null
        ));
    }

    /** Canonical source facts used to authorize one lifecycle-bound operation. */
    record SourceReadModel(
            @Nonnull CompanionLifecycle lifecycle,
            @Nonnull String canonicalRoleId,
            @Nonnull List<PopulationDomainReservation> committedDomainRows
    ) {
        SourceReadModel {
            if (lifecycle == null || canonicalRoleId == null
                    || canonicalRoleId.isBlank() || committedDomainRows == null) {
                throw new IllegalArgumentException("Complete lifecycle source evidence is required");
            }
            canonicalRoleId = canonicalRoleId.trim();
            committedDomainRows = List.copyOf(committedDomainRows);
        }
    }
}
