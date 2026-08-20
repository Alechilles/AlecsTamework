package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Internal gateway for managed lifecycle authoring and durable reconstruction. */
public interface PersistenceLifecycleAdmissionGateway {
    /** Authors provider evidence before the lifecycle operation prepares. */
    @Nonnull
    CompletionStage<LifecycleAdmissionEvidence> authorize(
            @Nonnull LifecycleAdmissionRequest request
    );

    /** Rebuilds a managed evidence carrier from durable participant evidence. */
    @Nonnull
    default LifecycleAdmissionEvidence reconstructDurable(
            @Nonnull PopulationDomainAdmissionOperation.Payload payload,
            @Nullable PopulationAdmissionComposition composition
    ) {
        return LifecycleAdmissionEvidence.managed(payload, composition);
    }

    /** Creates the explicit fail-closed gateway used before provider binding. */
    @Nonnull
    static PersistenceLifecycleAdmissionGateway unbound() {
        return Unbound.INSTANCE;
    }

    /** Default implementation for startup and focused adapter tests. */
    final class Unbound implements PersistenceLifecycleAdmissionGateway {
        private static final Unbound INSTANCE = new Unbound();

        private Unbound() {
        }

        @Override
        @Nonnull
        public CompletionStage<LifecycleAdmissionEvidence> authorize(
                @Nonnull LifecycleAdmissionRequest request
        ) {
            Objects.requireNonNull(request, "request");
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "managed-lifecycle-admission-authority-unavailable"
                    )
            );
        }
    }
}
