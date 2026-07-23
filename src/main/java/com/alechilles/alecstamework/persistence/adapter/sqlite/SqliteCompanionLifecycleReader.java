package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async canonical lifecycle reads used by public queries and projection rebuilds. */
public final class SqliteCompanionLifecycleReader {
    private static final PersistenceReadKind ALL =
            new PersistenceReadKind("companion_lifecycle_all");

    private final SqliteReadExecutor reads;

    public SqliteCompanionLifecycleReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Lifecycle read executor is required"
            );
        }
        this.reads = reads;
    }

    /** Lists one consistent canonical snapshot on the diagnostic lane. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<List<CompanionLifecycle>>>
    findAll() {
        return reads.execute(new SqliteReadCommand<>(
                ALL,
                PersistenceReadPriority.DIAGNOSTIC,
                connection -> {
                    List<CompanionLifecycle> lifecycles =
                            new SqliteCompanionLifecycleStore(connection)
                                    .findAll();
                    long revision = lifecycles.stream()
                            .mapToLong(lifecycle ->
                                    lifecycle.revision().value())
                            .max()
                            .orElse(0);
                    return PersistenceReadResult.found(
                            lifecycles,
                            revision
                    );
                }
        ));
    }
}
