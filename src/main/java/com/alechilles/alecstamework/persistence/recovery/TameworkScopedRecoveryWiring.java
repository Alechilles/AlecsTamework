package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.ownership.OwnerPopulationCanonicalRecoveryService;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopRuntimeServices;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Installs production recovery proofs before startup retries are allowed to run. */
public final class TameworkScopedRecoveryWiring {
    private static final List<PersistenceDomain> OWNER_DOMAINS = List.of(
            PersistenceDomain.TAMING_OWNERSHIP,
            PersistenceDomain.OWNER_MUTATION,
            PersistenceDomain.ADMIN_TAMED_SPAWN,
            PersistenceDomain.TAMED_SPAWN,
            PersistenceDomain.CAPTURE_INTAKE,
            PersistenceDomain.CAPTURE_RELEASE,
            PersistenceDomain.BREEDING_BIRTH,
            PersistenceDomain.DEATH_LOST_RECOVERY,
            PersistenceDomain.RECALL_RELOCATION,
            PersistenceDomain.RECONCILIATION);
    private static final List<PersistenceDomain> MANAGED_COOP_DOMAINS = List.of(
            PersistenceDomain.MANAGED_COOP_INTAKE,
            PersistenceDomain.MANAGED_COOP_RELEASE,
            PersistenceDomain.MANAGED_COOP_AUTOMATION);

    private TameworkScopedRecoveryWiring() {
    }

    /** Registers safe post-commit verifiers, then schedules durable open-incident recovery. */
    public static void installAndStart(@Nonnull TameworkPersistenceRuntime persistence,
                                       @Nonnull OwnerPopulationRuntime population) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(population, "population");
        ScopedPersistenceRecoveryCoordinator coordinator =
                persistence.getScopedRecoveryCoordinator();
        installOwnerVerifiers(coordinator, population.canonicalRecoveryService());
        coordinator.register(new ReconciliationEvidenceRecoveryVerifier(
                population.reconciliationEvidenceRecoveryProofs(),
                population.canonicalRecoveryService()::verifyReadable,
                population.canonicalRecoveryService()::republish
        ));
        installManagedCoopVerifiers(coordinator, persistence.getManagedCoopServices());
        coordinator.scheduleOpenIncidentsAfterStartup();
    }

    private static void installOwnerVerifiers(
            ScopedPersistenceRecoveryCoordinator coordinator,
            OwnerPopulationCanonicalRecoveryService recovery) {
        for (PersistenceDomain domain : OWNER_DOMAINS) {
            coordinator.register(new PostCommitPublicationRecoveryVerifier(
                    domain,
                    "owner-population-canonical-v1",
                    recovery::verifyReadable,
                    recovery::republish));
        }
    }

    private static void installManagedCoopVerifiers(
            ScopedPersistenceRecoveryCoordinator coordinator,
            ManagedCoopRuntimeServices services) {
        for (PersistenceDomain domain : MANAGED_COOP_DOMAINS) {
            coordinator.register(new PostCommitPublicationRecoveryVerifier(
                    domain,
                    "managed-coop-canonical-v1",
                    () -> verifyManagedCoopReadable(services),
                    () -> publishManagedCoopIndexes(services)));
        }
    }

    private static void verifyManagedCoopReadable(ManagedCoopRuntimeServices services) {
        requireLoaded(services.residentRepository().loadAllActiveAuthorities(), "authorities");
        requireLoaded(services.residentRepository().loadAllActiveResidents(), "residents");
        requireLoaded(services.lifecycleRepository().loadAllActiveOperations(), "operations");
    }

    private static void requireLoaded(ManagedCoopReadResult<?> result, String catalog) {
        if (result.status() == ManagedCoopReadResult.Status.LOADED) return;
        String detail = result.failure() == null ? result.status().name() : result.failure().detail();
        throw new IllegalStateException("managed_coop_" + catalog + "_read_failed:" + detail,
                result.failure() == null ? null : result.failure().cause());
    }

    private static void publishManagedCoopIndexes(ManagedCoopRuntimeServices services) {
        var result = services.compositeIndexRefreshService().refresh();
        if (!result.refreshed() || !services.compositeIndexRefreshService().isTrusted()) {
            throw new IllegalStateException("managed_coop_index_republish_failed:" + result.detail());
        }
    }
}
