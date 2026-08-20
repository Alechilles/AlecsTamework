package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CompanionProvisioningApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.AdmissionProviderApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.RequiredContentProfileApi;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.persistence.facade.ReplacementCommandFamilyRosterApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementCommandTimedSummoningApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementNpcProfilesApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPaidCommandRevivalApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPersistenceDiagnosticsApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPopulationGroupApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPopulationAdmissionApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementProfileDataApi;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Final public-API composition over the replacement domain facade bundle. */
public final class ReplacementTameworkApiFactory {
    private ReplacementTameworkApiFactory() {
    }

    @Nonnull
    public static TameworkApiImpl create(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Duration readTimeout,
            @Nonnull LongSupplier clock,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy
    ) {
        if (persistence == null || readTimeout == null || clock == null) {
            throw new IllegalArgumentException(
                    "Complete replacement API composition is required"
            );
        }
        return base(
                persistence,
                readTimeout,
                clock,
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy,
                new ReplacementPersistenceDiagnosticsApi(persistence)
        );
    }

    /**
     * Composes readiness-gated restored features without extending the legacy
     * implementation or exposing concrete-type lifecycle checks.
     */
    @Nonnull
    public static Composition compose(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Duration readTimeout,
            @Nonnull LongSupplier clock,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy,
            @Nonnull ReplacementFeatureApiDependencies dependencies
    ) {
        return compose(
                persistence, readTimeout, clock, eventBus, snapshots,
                interactionExtensions, traitEffects, damagePolicy,
                dependencies, dependencies.bondedCompanions()
        );
    }

    /** Composes bonded authority independently of generic feature readiness. */
    @Nonnull
    public static Composition compose(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Duration readTimeout,
            @Nonnull LongSupplier clock,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy,
            @Nonnull ReplacementFeatureApiDependencies dependencies,
            @Nullable BondedCompanionApi bondedCompanions
    ) {
        Objects.requireNonNull(dependencies, "dependencies");
        PersistenceDomainFacades facades = persistence.facades();
        DiagnosticsApi diagnostics = dependencies.availability() != null
                && dependencies.incidents() != null
                ? new ReplacementPersistenceDiagnosticsApi(
                persistence,
                readTimeout,
                dependencies.availability(),
                dependencies.incidents()
        )
                : new ReplacementPersistenceDiagnosticsApi(persistence);
        TameworkApiImpl base = base(
                persistence,
                readTimeout,
                clock,
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy,
                diagnostics
        );
        PopulationGroupApi populationGroups =
                dependencies.populationGroups() == null
                        ? PopulationGroupApi.unavailable()
                        : new ReplacementPopulationGroupApi(
                        persistence,
                        facades.queries(),
                        dependencies.populationGroups(),
                        clock
                );
        CommandFamilyRosterApi rosters =
                dependencies.commandRosters() == null
                        ? CommandFamilyRosterApi.unavailable()
                        : new ReplacementCommandFamilyRosterApi(
                        facades.queries(),
                        facades.operations(),
                        dependencies.commandRosters()
                );
        ReplacementCommandTimedSummoningApi timedFacade =
                dependencies.timedSummoning() == null
                        ? null
                        : new ReplacementCommandTimedSummoningApi(
                                facades.queries(),
                                facades.operations(),
                                dependencies.timedSummoning(),
                                clock
                        );
        CommandTimedSummoningApi timed = timedFacade == null
                ? CommandTimedSummoningApi.unavailable()
                : timedFacade;
        CompanionProvisioningApi provisioning =
                dependencies.provisioning() == null
                        || dependencies.commandRosters() == null
                        || dependencies.timedSummoning() == null
                        ? CompanionProvisioningApi.unavailable()
                        : new ReplacementCompanionProvisioningApi(
                        facades.queries(),
                        facades.operations(),
                        dependencies.provisioning(),
                        rosters,
                        timed,
                        readTimeout
                );
        PaidCommandRevivalApi paidRevival =
                dependencies.paidRevival() == null
                        ? PaidCommandRevivalApi.unavailable()
                        : new ReplacementPaidCommandRevivalApi(
                        facades.queries(),
                        facades.operations(),
                        dependencies.paidRevival()
                );
        BondedCompanionApi bondedApi =
                bondedCompanions == null
                        ? BondedCompanionApi.unavailable()
                        : bondedCompanions;
        PopulationAdmissionApi populationAdmissions = PopulationAdmissionApi.unavailable();
        AdmissionProviderApi admissionProviders = AdmissionProviderApi.unavailable();
        RequiredContentProfileApi requiredContentProfiles =
                RequiredContentProfileApi.unavailable();
        ManagedBatchAdmissionAuthority managedBatchAdmissions =
                ManagedBatchAdmissionAuthority.unavailable();
        if (dependencies.managedActivities() != null
                && dependencies.populationGroups() != null
                && dependencies.admissionProviders() != null) {
            ReplacementPopulationAdmissionApi admission =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            facades.operations(),
                            dependencies.managedActivities(),
                            dependencies.populationGroups(),
                            dependencies.admissionProviders(),
                            clock
                    );
            populationAdmissions = admission;
            admissionProviders = dependencies.admissionProviders();
            requiredContentProfiles = admission;
            managedBatchAdmissions = admission;
        }
        ReplacementTameworkApi api = new ReplacementTameworkApi(
                base,
                persistence,
                dependencies,
                diagnostics,
                populationGroups,
                rosters,
                timed,
                provisioning,
                paidRevival,
                bondedApi,
                populationAdmissions,
                admissionProviders,
                requiredContentProfiles,
                managedBatchAdmissions
        );
        AutoCloseable timedEventBridge = timedFacade == null
                ? () -> { }
                : eventBus.subscribe(
                        CommandTimedSummoningChangedEvent.class,
                        timedFacade::publishFromOutbox
                );
        return new Composition(base, api, timedEventBridge);
    }

    private static TameworkApiImpl base(
            PersistenceBootstrap persistence,
            Duration readTimeout,
            LongSupplier clock,
            TameworkEventBus eventBus,
            CommandLinkedNpcStateSnapshotService snapshots,
            InteractionExtensionApi interactionExtensions,
            TraitEffectApi traitEffects,
            SimpleClaimsTamedDamagePolicy damagePolicy,
            DiagnosticsApi diagnostics
    ) {
        if (persistence == null || readTimeout == null || clock == null) {
            throw new IllegalArgumentException(
                    "Complete replacement API composition is required"
            );
        }
        PersistenceDomainFacades facades = persistence.facades();
        return new TameworkApiImpl(
                new ReplacementNpcProfilesApi(
                        facades.queries(), readTimeout
                ),
                new ReplacementProfileDataApi(
                        facades.queries(), facades.operations(), clock
                ),
                diagnostics,
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy
        );
    }

    /**
     * Narrow plugin lifecycle/config seam for the composed API. The plugin can
     * retain this object instead of checking whether the API is a concrete
     * implementation.
     */
    public static final class Composition implements AutoCloseable {
        private final TameworkApiImpl base;
        private final ReplacementTameworkApi api;
        private final AutoCloseable timedEventBridge;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Composition(
                TameworkApiImpl base,
                ReplacementTameworkApi api,
                AutoCloseable timedEventBridge
        ) {
            this.base = Objects.requireNonNull(base, "base");
            this.api = Objects.requireNonNull(api, "api");
            this.timedEventBridge = Objects.requireNonNull(
                    timedEventBridge, "timedEventBridge"
            );
        }

        @Nonnull
        public TameworkApi api() {
            return api;
        }

        public void activateCapturePolicyRuntime(
                @Nonnull ItemFeatureRegistry itemConfigs,
                @Nonnull CapturePolicyRegistry policyRegistry
        ) {
            base.activateCapturePolicyRuntime(
                    itemConfigs, policyRegistry
            );
        }

        public void onRuntimeSettingsChanged() {
            base.onRuntimeSettingsChanged();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    timedEventBridge.close();
                } catch (Exception ignored) {
                    // The event bus owns only an in-memory listener handle.
                }
                api.close();
            }
        }
    }
}
