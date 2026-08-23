package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CompanionProvisioningApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProgressionApi;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/**
 * Readiness-gated public API composition over the legacy non-persistence
 * surface and replacement persistence feature facades.
 */
public final class ReplacementTameworkApi
        implements TameworkApi, AutoCloseable {
    private final TameworkApiImpl base;
    private final PersistenceBootstrap persistence;
    private final ReplacementFeatureApiDependencies dependencies;
    private final DiagnosticsApi diagnostics;
    private final PopulationGroupApi populationGroups;
    private final CommandFamilyRosterApi rosters;
    private final CommandTimedSummoningApi timedSummoning;
    private final CompanionProvisioningApi provisioning;
    private final PaidCommandRevivalApi paidRevival;
    private final BondedCompanionApi bondedCompanions;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReplacementTameworkApi(
            @Nonnull TameworkApiImpl base,
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull ReplacementFeatureApiDependencies dependencies,
            @Nonnull DiagnosticsApi diagnostics,
            @Nonnull PopulationGroupApi populationGroups,
            @Nonnull CommandFamilyRosterApi rosters,
            @Nonnull CommandTimedSummoningApi timedSummoning,
            @Nonnull CompanionProvisioningApi provisioning,
            @Nonnull PaidCommandRevivalApi paidRevival,
            @Nonnull BondedCompanionApi bondedCompanions
    ) {
        this.base = Objects.requireNonNull(base, "base");
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
        this.dependencies = Objects.requireNonNull(
                dependencies, "dependencies"
        );
        this.diagnostics = Objects.requireNonNull(
                diagnostics, "diagnostics"
        );
        this.populationGroups = Objects.requireNonNull(
                populationGroups, "populationGroups"
        );
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.timedSummoning = Objects.requireNonNull(
                timedSummoning, "timedSummoning"
        );
        this.provisioning = Objects.requireNonNull(
                provisioning, "provisioning"
        );
        this.paidRevival = Objects.requireNonNull(
                paidRevival, "paidRevival"
        );
        this.bondedCompanions = Objects.requireNonNull(
                bondedCompanions,
                "bondedCompanions"
        );
    }

    @Override
    public String getApiVersion() {
        return base.getApiVersion();
    }

    @Override
    public EnumSet<TameworkApiCapability> getCapabilities() {
        EnumSet<TameworkApiCapability> result = base.getCapabilities();
        if (dependencies.availability() != null
                && dependencies.incidents() != null) {
            result.add(TameworkApiCapability.PERSISTENCE_RESILIENCE);
        }
        addRestoredCapabilities(result);
        return result;
    }

    @Override
    public NpcProfilesApi profiles() {
        return base.profiles();
    }

    @Override
    public CommandLinksApi commandLinks() {
        return base.commandLinks();
    }

    @Override
    public CommandUiApi commandUi() {
        return base.commandUi();
    }

    @Override
    public CommandTimedSummoningApi commandTimedSummoning() {
        return mutationReady(PublicPersistenceFeatureRegistry.TIMED_SUMMON)
                && dependencies.timedSummoning() != null
                ? timedSummoning
                : CommandTimedSummoningApi.unavailable();
    }

    @Override
    public ProgressionApi progression() {
        return base.progression();
    }

    @Override
    public PolicyApi policies() {
        return base.policies();
    }

    @Override
    public InteractionExtensionApi interactionExtensions() {
        return base.interactionExtensions();
    }

    @Override
    public TraitEffectApi traitEffects() {
        return base.traitEffects();
    }

    @Override
    public ProfileDataApi profileData() {
        return base.profileData();
    }

    @Override
    public TameworkEventsApi events() {
        return base.events();
    }

    @Override
    public TameworkConfigReadApi configs() {
        return base.configs();
    }

    @Override
    public DiagnosticsApi diagnostics() {
        return diagnostics;
    }

    @Override
    public CommandFamilyRosterApi commandFamilyRosters() {
        return mutationReady(PublicPersistenceFeatureRegistry.COMMAND_ROSTER)
                && dependencies.commandRosters() != null
                ? rosters
                : CommandFamilyRosterApi.unavailable();
    }

    @Override
    public CompanionProvisioningApi companionProvisioning() {
        return provisioningReady()
                ? provisioning
                : CompanionProvisioningApi.unavailable();
    }

    @Override
    public PaidCommandRevivalApi paidCommandRevival() {
        return mutationReady(PublicPersistenceFeatureRegistry.PAID_REVIVAL)
                && dependencies.paidRevival() != null
                ? paidRevival
                : PaidCommandRevivalApi.unavailable();
    }

    @Override
    public PopulationGroupApi populationGroups() {
        return readReady(PublicPersistenceFeatureRegistry.POPULATION_GROUPS)
                && dependencies.populationGroups() != null
                ? populationGroups
                : PopulationGroupApi.unavailable();
    }

    @Override
    public BondedCompanionApi bondedCompanions() {
        return bondedCompanions.availability().available()
                ? bondedCompanions
                : BondedCompanionApi.unavailable();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            base.close();
        }
    }

    private void addRestoredCapabilities(
            EnumSet<TameworkApiCapability> capabilities
    ) {
        if (readReady(PublicPersistenceFeatureRegistry.POPULATION_GROUPS)
                && dependencies.populationGroups() != null) {
            capabilities.add(TameworkApiCapability.POPULATION_GROUPS);
        }
        if (mutationReady(PublicPersistenceFeatureRegistry.COMMAND_ROSTER)
                && dependencies.commandRosters() != null) {
            capabilities.add(TameworkApiCapability.COMMAND_FAMILY_ROSTERS);
        }
        if (mutationReady(PublicPersistenceFeatureRegistry.TIMED_SUMMON)
                && dependencies.timedSummoning() != null) {
            capabilities.add(TameworkApiCapability.COMMAND_TIMED_SUMMONING);
        }
        if (provisioningReady()) {
            capabilities.add(TameworkApiCapability.COMPANION_PROVISIONING);
        }
        if (mutationReady(PublicPersistenceFeatureRegistry.PAID_REVIVAL)
                && dependencies.paidRevival() != null) {
            capabilities.add(TameworkApiCapability.PAID_COMMAND_REVIVAL);
        }
        if (mutationReady(PublicPersistenceFeatureRegistry.CAPTURE)) {
            if (dependencies.captureResolvedEventsReady()) {
                capabilities.add(
                        TameworkApiCapability
                                .CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION
                );
            }
            if (dependencies.captureTameAndLinkReady()) {
                capabilities.add(
                        TameworkApiCapability.CAPTURE_TAME_AND_LINK
                );
            }
        }
        if (bondedCompanions.availability().available()) {
            capabilities.add(TameworkApiCapability.BONDED_COMPANIONS);
        }
    }

    private boolean provisioningReady() {
        return mutationReady(PublicPersistenceFeatureRegistry.PROVISIONING)
                && mutationReady(
                PublicPersistenceFeatureRegistry.COMMAND_ROSTER
        )
                && mutationReady(PublicPersistenceFeatureRegistry.TIMED_SUMMON)
                && dependencies.provisioning() != null
                && dependencies.commandRosters() != null
                && dependencies.timedSummoning() != null;
    }

    private boolean mutationReady(PersistenceFeatureId featureId) {
        return persistence.readiness(featureId)
                == PersistenceReadinessLevel.MUTATION_READY;
    }

    private boolean readReady(PersistenceFeatureId featureId) {
        PersistenceReadinessLevel readiness =
                persistence.readiness(featureId);
        return readiness == PersistenceReadinessLevel.PROJECTION_READY
                || readiness
                == PersistenceReadinessLevel.WORLD_EVIDENCE_PENDING
                || readiness == PersistenceReadinessLevel.MUTATION_READY;
    }
}
