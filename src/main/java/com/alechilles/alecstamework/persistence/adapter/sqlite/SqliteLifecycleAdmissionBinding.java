package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceLifecycleAdmissionGateway;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One bind-once managed-admission gateway shared by public and recovery sets. */
final class SqliteLifecycleAdmissionBinding {
    private static final PersistenceLifecycleAdmissionGateway UNBOUND =
            PersistenceLifecycleAdmissionGateway.unbound();
    private final AtomicReference<PersistenceLifecycleAdmissionGateway> delegate =
            new AtomicReference<>(UNBOUND);

    void bind(@Nonnull PersistenceLifecycleAdmissionGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        if (gateway == UNBOUND || !delegate.compareAndSet(UNBOUND, gateway)) {
            throw new IllegalStateException(
                    "lifecycle-admission-gateway-already-bound"
            );
        }
    }

    @Nonnull
    CompletionStage<LifecycleAdmissionEvidence> authorize(
            @Nonnull LifecycleAdmissionRequest request
    ) {
        return delegate.get().authorize(request);
    }

    /** Reconstructs durable evidence without consulting the provider. */
    @Nonnull
    LifecycleAdmissionEvidence reconstructDurable(
            @Nonnull PopulationDomainAdmissionOperation.Payload payload,
            @Nullable PopulationAdmissionComposition composition
    ) {
        return delegate.get().reconstructDurable(payload, composition);
    }

    @Nonnull
    PersistenceLifecycleAdmissionGateway gateway() {
        return delegate.get();
    }
}
