package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.List;
import java.util.Set;

/** Focused descriptor composition for exact paid companion revival. */
final class PublicPersistencePaidRevivalFeature {
    private PublicPersistencePaidRevivalFeature() {
    }

    static PersistenceFeatureDescriptor create() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                PublicPersistenceFeatureRegistry.PAID_REVIVAL,
                PersistenceFeatureDomain.COMMAND,
                Set.of(),
                List.of(PaidRevivalDefinition.INSTANCE),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        PaidRevivalDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER,
                                OperationScopeType.COMMAND_FAMILY
                        )
                ),
                Set.of(
                        PublicPersistenceFeatureRegistry.IDENTITY,
                        PublicPersistenceFeatureRegistry.LIFECYCLE,
                        PublicPersistenceFeatureRegistry.POPULATION_GROUPS,
                        PublicPersistenceFeatureRegistry.COMMAND_ROSTER,
                        PublicPersistenceFeatureRegistry.TIMED_SUMMON,
                        PublicPersistenceFeatureRegistry
                                .ECONOMIC_COMPENSATION
                ),
                Set.of(
                        PublicPersistenceFeatureRegistry.PROFILE_OBSERVER,
                        PublicPersistenceFeatureRegistry
                                .OWNER_POPULATION_INDEX,
                        PublicPersistenceFeatureRegistry
                                .POPULATION_GROUP_INDEX,
                        PublicPersistenceFeatureRegistry
                                .COMMAND_ROSTER_INDEX,
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
