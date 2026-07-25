package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.List;
import java.util.Set;

/** Focused descriptor composition for the timed command feature. */
final class PublicPersistenceTimedFeature {
    private PublicPersistenceTimedFeature() {
    }

    static PersistenceFeatureDescriptor create() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                PublicPersistenceFeatureRegistry.TIMED_SUMMON,
                PersistenceFeatureDomain.COMMAND,
                Set.of("timed_summon_lease"),
                List.of(
                        TimedSummonLeaseMutationDefinition.INSTANCE,
                        TimedSummonTransitionDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        TimedSummonLeaseMutationDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE),
                        TimedSummonTransitionDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER,
                                OperationScopeType.COMMAND_FAMILY
                        )
                ),
                Set.of(
                        PublicPersistenceFeatureRegistry.IDENTITY,
                        PublicPersistenceFeatureRegistry.LIFECYCLE,
                        PublicPersistenceFeatureRegistry.OWNER_POPULATION,
                        PublicPersistenceFeatureRegistry.POPULATION_GROUPS,
                        PublicPersistenceFeatureRegistry.COMMAND_ROSTER
                ),
                Set.of(
                        PublicPersistenceFeatureRegistry.PROFILE_OBSERVER,
                        PublicPersistenceFeatureRegistry.OWNER_POPULATION_INDEX,
                        PublicPersistenceFeatureRegistry.POPULATION_GROUP_INDEX,
                        PublicPersistenceFeatureRegistry.COMMAND_ROSTER_INDEX,
                        PublicPersistenceFeatureRegistry.TIMED_SUMMON_INDEX
                ),
                PublicPersistenceFeatureDescriptorFactory.worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                )
        );
    }
}
