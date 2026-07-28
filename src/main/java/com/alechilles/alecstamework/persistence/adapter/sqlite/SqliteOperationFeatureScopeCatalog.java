package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Exact persisted feature scopes for every registered SQLite operation.
 *
 * <p>The catalog references each operation adapter's own constant so API
 * composition does not reproduce a second string switch.</p>
 */
public final class SqliteOperationFeatureScopeCatalog {
    private final Map<OperationKind, String> featureScopes;

    public SqliteOperationFeatureScopeCatalog(
            @Nonnull PersistenceFeatureRegistry registry
    ) {
        if (registry == null) {
            throw new IllegalArgumentException(
                    "Persistence feature registry is required"
            );
        }
        featureScopes = scopes();
        Set<OperationKind> registered = registry.descriptors().stream()
                .flatMap(descriptor ->
                        descriptor.operationDefinitions().stream())
                .map(definition -> definition.kind())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!featureScopes.keySet().equals(registered)) {
            throw new IllegalStateException(
                    "sqlite_operation_feature_scope_registry_mismatch"
            );
        }
    }

    /** Returns the exact feature scope persisted by the operation adapter. */
    @Nonnull
    public Optional<String> resolve(@Nonnull OperationKind operationKind) {
        if (operationKind == null) {
            throw new IllegalArgumentException(
                    "Operation kind is required"
            );
        }
        return Optional.ofNullable(featureScopes.get(operationKind));
    }

    private Map<OperationKind, String> scopes() {
        HashMap<OperationKind, String> scopes = new HashMap<>();
        put(scopes, CompanionProfileMutationDefinition.KIND,
                SqliteCompanionProfileOperations.FEATURE_SCOPE);
        put(scopes, CompanionAliasRotationDefinition.KIND,
                SqliteCompanionAliasRotationOperations.FEATURE_SCOPE);
        put(scopes, OwnerPopulationTransitionDefinition.KIND,
                SqliteOwnerPopulationTransitionOperations.FEATURE_SCOPE);
        put(scopes, OwnerPopulationReconciliationDefinition.KIND,
                SqliteOwnerPopulationReconciliationOperations.FEATURE_SCOPE);
        put(scopes, PopulationGroupAssignmentDefinition.KIND,
                SqlitePopulationGroupAssignmentOperations.FEATURE_SCOPE);
        put(scopes, CommandRosterMembershipDefinition.KIND,
                SqliteCommandRosterMembershipOperations.FEATURE_SCOPE);
        put(scopes, CommandRosterTransitionDefinition.KIND,
                SqliteCommandRosterTransitionOperations.FEATURE_SCOPE);
        put(scopes, TimedSummonLeaseMutationDefinition.KIND,
                SqliteTimedSummonLeaseOperations.FEATURE_SCOPE);
        put(scopes, TimedSummonTransitionDefinition.KIND,
                SqliteTimedSummonTransitionOperations.FEATURE_SCOPE);
        put(scopes, CompanionProvisioningDefinition.KIND,
                SqliteCompanionProvisioningOperations.FEATURE_SCOPE);
        put(scopes, ProvisioningActivationDefinition.KIND,
                SqliteProvisioningActivationOperations.FEATURE_SCOPE);
        put(scopes, PaidRevivalDefinition.KIND,
                SqlitePaidRevivalOperations.FEATURE_SCOPE);
        put(scopes, CompanionCaptureDefinition.KIND,
                SqliteCompanionCaptureOperations.FEATURE_SCOPE);
        put(scopes, CompanionCaptureReleaseDefinition.KIND,
                SqliteCompanionCaptureReleaseOperations.FEATURE_SCOPE);
        put(scopes, CompanionDormantTransitionDefinition.KIND,
                SqliteCompanionDormantOperations.FEATURE_SCOPE);
        put(scopes, CompanionRestorationDefinition.KIND,
                SqliteCompanionRestorationOperations.FEATURE_SCOPE);
        put(scopes, CoopSlotRegistrationDefinition.KIND,
                SqliteCoopSlotOperations.FEATURE_SCOPE);
        put(scopes, CompanionCoopCaptureDefinition.KIND,
                SqliteCompanionCoopCaptureOperations.FEATURE_SCOPE);
        put(scopes, CompanionCoopReleaseDefinition.KIND,
                SqliteCompanionCoopReleaseOperations.FEATURE_SCOPE);
        put(scopes, ProfileExtensionMutationDefinition.KIND,
                SqliteProfileExtensionOperations.FEATURE_SCOPE);
        return Map.copyOf(scopes);
    }

    private void put(
            Map<OperationKind, String> scopes,
            OperationKind kind,
            String featureScope
    ) {
        if (featureScope == null || featureScope.isBlank()
                || scopes.put(kind, featureScope) != null) {
            throw new IllegalStateException(
                    "sqlite_operation_feature_scope_duplicate"
            );
        }
    }
}
