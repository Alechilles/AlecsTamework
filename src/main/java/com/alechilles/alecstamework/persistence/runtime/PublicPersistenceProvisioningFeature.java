package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceCircuitPolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureHookId;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused descriptor for deterministic dormant profile provisioning. */
final class PublicPersistenceProvisioningFeature {
    private PublicPersistenceProvisioningFeature() {
    }

    static PersistenceFeatureDescriptor create() {
        return new PersistenceFeatureDescriptor(
                PublicPersistenceFeatureRegistry.PROVISIONING,
                PersistenceFeatureDomain.PROVISIONING,
                Set.of("provisioning_record"),
                List.of(
                        CompanionProvisioningDefinition.INSTANCE,
                        ProvisioningActivationDefinition.INSTANCE
                ),
                Map.of(
                        CompanionProvisioningDefinition.INSTANCE.kind(),
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER,
                                OperationScopeType.COMMAND_FAMILY
                        ),
                        ProvisioningActivationDefinition.INSTANCE.kind(),
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
                        PublicPersistenceFeatureRegistry.COMMAND_ROSTER,
                        PublicPersistenceFeatureRegistry.TIMED_SUMMON
                ),
                hook("loader"),
                Set.of(
                        PublicPersistenceFeatureRegistry.PROFILE_OBSERVER,
                        PublicPersistenceFeatureRegistry
                                .OWNER_POPULATION_INDEX,
                        PublicPersistenceFeatureRegistry
                                .POPULATION_GROUP_INDEX,
                        PublicPersistenceFeatureRegistry
                                .COMMAND_ROSTER_INDEX,
                        PublicPersistenceFeatureRegistry
                                .TIMED_SUMMON_INDEX,
                        PublicPersistenceFeatureRegistry.PROVISIONING_INDEX
                ),
                hook("recovery"),
                Set.of(
                        PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                        PersistenceStartupNode.RECOVER_OPERATIONS,
                        PersistenceStartupNode.BUILD_PROJECTIONS
                ),
                PersistenceCircuitPolicy.BOUNDED_SCOPE,
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                ),
                hook("shutdown"),
                "persistence."
                        + PublicPersistenceFeatureRegistry.PROVISIONING
        );
    }

    private static PersistenceFeatureHookId hook(String kind) {
        return new PersistenceFeatureHookId(
                PublicPersistenceFeatureRegistry.PROVISIONING
                        + "." + kind
        );
    }
}
