package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceCircuitPolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureHookId;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused descriptor composition for the timed command feature. */
final class PublicPersistenceTimedFeature {
    private PublicPersistenceTimedFeature() {
    }

    static PersistenceFeatureDescriptor create() {
        return new PersistenceFeatureDescriptor(
                PublicPersistenceFeatureRegistry.TIMED_SUMMON,
                PersistenceFeatureDomain.COMMAND,
                Set.of("timed_summon_lease"),
                List.of(
                        TimedSummonLeaseMutationDefinition.INSTANCE,
                        TimedSummonTransitionDefinition.INSTANCE
                ),
                Map.of(
                        TimedSummonLeaseMutationDefinition.INSTANCE.kind(),
                        Set.of(OperationScopeType.PROFILE),
                        TimedSummonTransitionDefinition.INSTANCE.kind(),
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
                hook("loader"),
                Set.of(
                        PublicPersistenceFeatureRegistry.TIMED_SUMMON_INDEX
                ),
                hook("recovery"),
                Set.of(
                        PersistenceStartupNode.RECOVER_OPERATIONS,
                        PersistenceStartupNode.BUILD_PROJECTIONS,
                        PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                        PersistenceStartupNode.RECONCILE_WORLD
                ),
                PersistenceCircuitPolicy.BOUNDED_SCOPE,
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                ),
                hook("shutdown"),
                "persistence." + PublicPersistenceFeatureRegistry.TIMED_SUMMON
        );
    }

    private static PersistenceFeatureHookId hook(String kind) {
        return new PersistenceFeatureHookId(
                PublicPersistenceFeatureRegistry.TIMED_SUMMON
                        + "." + kind
        );
    }
}
