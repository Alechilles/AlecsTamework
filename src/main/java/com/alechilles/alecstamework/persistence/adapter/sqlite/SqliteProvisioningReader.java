package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async read-lane access to immutable provisioning provenance. */
public final class SqliteProvisioningReader {
    private static final PersistenceReadKind PROVISIONING =
            new PersistenceReadKind("provisioning_records");

    private final SqliteReadExecutor reads;

    public SqliteProvisioningReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Provisioning read executor is required"
            );
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<ProvisioningRecord>>
    findByProfile(@Nonnull ProfileId profileId) {
        if (profileId == null) {
            throw new IllegalArgumentException(
                    "Provisioning profile is required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                PROVISIONING,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteProvisioningStore(connection)
                        .findByProfile(profileId)
                        .<PersistenceReadResult<ProvisioningRecord>>map(
                                record -> PersistenceReadResult.found(
                                        record, 0
                                )
                        )
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<ProvisioningRecord>>
    findByOrigin(@Nonnull ProvisioningOrigin origin) {
        if (origin == null) {
            throw new IllegalArgumentException(
                    "Provisioning origin is required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                PROVISIONING,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteProvisioningStore(connection)
                        .findByOrigin(origin)
                        .<PersistenceReadResult<ProvisioningRecord>>map(
                                record -> PersistenceReadResult.found(
                                        record, 0
                                )
                        )
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<ProvisioningRecord>>>
    findAll() {
        return reads.execute(new SqliteReadCommand<>(
                PROVISIONING,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> PersistenceReadResult.found(
                        new SqliteProvisioningStore(connection).findAll(),
                        0
                )
        ));
    }
}
