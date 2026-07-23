package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Synchronizes durable control evidence with the one feature registry. */
final class SqlitePublicControlGateway {
    private static final OperationId SYNCHRONIZE_OPERATION =
            OperationId.parse(
                    "00000000-0000-0000-0000-000000000057"
            );
    private static final OperationKind SYNCHRONIZE_KIND =
            new OperationKind("public_control_synchronize");
    private static final PersistenceReadKind SYNCHRONIZE_READBACK =
            new PersistenceReadKind("public_control_synchronize_readback");

    private final PersistenceFeatureRegistry registry;
    private final SqliteUnitOfWorkRunner units;
    private final LongSupplier clock;
    private final Set<PersistenceFeatureId> featureIds;

    SqlitePublicControlGateway(
            PersistenceFeatureRegistry registry,
            SqliteUnitOfWorkRunner units,
            LongSupplier clock
    ) {
        if (registry == null || units == null || clock == null) {
            throw new IllegalArgumentException(
                    "Public control gateway dependencies are required"
            );
        }
        this.registry = registry;
        this.units = units;
        this.clock = clock;
        featureIds = registry.descriptors().stream()
                .map(PersistenceFeatureDescriptor::featureId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    CompletionStage<com.alechilles.alecstamework.persistence.kernel
            .PersistenceTransactionResult<SqlitePublicControlSnapshot>>
    synchronize() {
        long synchronizedAtMs = clock.getAsLong();
        SqliteTransactionCommand<SqlitePublicControlSnapshot> command =
                new SqliteTransactionCommand<>(
                        SYNCHRONIZE_OPERATION,
                        SYNCHRONIZE_KIND,
                        TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                        connection -> new SqlitePublicControlSnapshot(
                                new SqliteFeatureCircuitStore(connection)
                                        .synchronize(
                                                featureIds,
                                                synchronizedAtMs
                                        )
                        )
                );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                SYNCHRONIZE_READBACK,
                connection -> {
                    SqlitePublicControlSnapshot snapshot =
                            new SqlitePublicControlSnapshot(
                                    new SqliteFeatureCircuitStore(connection)
                                            .requireExact(featureIds)
                            );
                    return PersistenceReadResult.found(
                            snapshot,
                            registry.descriptors().size()
                    );
                }
        )).completion();
    }
}
