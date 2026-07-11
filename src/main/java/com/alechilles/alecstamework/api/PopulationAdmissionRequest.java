package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Complete compare-and-transition request for a mutation-bound owner/claim admission.
 *
 * <p>Revision {@link #NEW_PROFILE_REVISION} denotes a profile that is not committed yet. All
 * other revisions require a canonical profile identity. This validation catches incomplete
 * integration requests before they can reach the runtime authority.
 */
public record PopulationAdmissionRequest(@Nonnull PopulationAdmissionIdentity identity,
                                         @Nullable UUID currentNpcUuid,
                                         long expectedProfileRevision,
                                         @Nullable UUID oldOwnerUuid,
                                         @Nullable UUID newOwnerUuid,
                                         @Nullable PopulationAdmissionLocation source,
                                         @Nullable PopulationAdmissionLocation destination,
                                         @Nonnull PopulationAdmissionOperation operation,
                                         int exactSlots,
                                         @Nonnull PopulationAdmissionForcePolicy forcePolicy,
                                         @Nonnull PopulationCompanionLifecycle targetLifecycle) {
    public static final long NEW_PROFILE_REVISION = -1L;

    public PopulationAdmissionRequest {
        identity = Objects.requireNonNull(identity, "identity");
        operation = Objects.requireNonNull(operation, "operation");
        forcePolicy = Objects.requireNonNull(forcePolicy, "forcePolicy");
        targetLifecycle = Objects.requireNonNull(targetLifecycle, "targetLifecycle");
        if (expectedProfileRevision < NEW_PROFILE_REVISION) {
            throw new IllegalArgumentException("Expected profile revision must be -1 or non-negative.");
        }
        if (expectedProfileRevision >= 0L && !identity.canonical()) {
            throw new IllegalArgumentException("A committed profile revision requires a canonical profile id.");
        }
        if (identity.provisional() && expectedProfileRevision != NEW_PROFILE_REVISION) {
            throw new IllegalArgumentException("A provisional profile must use NEW_PROFILE_REVISION.");
        }
        if (identity.canonical() && expectedProfileRevision == NEW_PROFILE_REVISION) {
            throw new IllegalArgumentException("A canonical profile must use its committed revision.");
        }
        if (expectedProfileRevision == NEW_PROFILE_REVISION && (oldOwnerUuid != null || source != null)) {
            throw new IllegalArgumentException("A new profile cannot declare an old owner or source location.");
        }
        if (exactSlots <= 0) {
            throw new IllegalArgumentException("Exact requested slots must be positive.");
        }
        if (exactSlots != 1) {
            throw new IllegalArgumentException(
                    "A single-profile admission reserves one slot; use PopulationBatchAdmissionRequest for batches."
            );
        }
        validateForce(operation, forcePolicy);
        validateLifecycle(operation, newOwnerUuid, destination, targetLifecycle);
        validateOperation(
                operation,
                expectedProfileRevision,
                currentNpcUuid,
                oldOwnerUuid,
                newOwnerUuid,
                source,
                destination
        );
    }

    /**
     * Convenience constructor for ownership operations with an unambiguous lifecycle result.
     * Lifecycle-only transitions must use the canonical constructor and name their target state.
     */
    public PopulationAdmissionRequest(@Nonnull PopulationAdmissionIdentity identity,
                                      @Nullable UUID currentNpcUuid,
                                      long expectedProfileRevision,
                                      @Nullable UUID oldOwnerUuid,
                                      @Nullable UUID newOwnerUuid,
                                      @Nullable PopulationAdmissionLocation source,
                                      @Nullable PopulationAdmissionLocation destination,
                                      @Nonnull PopulationAdmissionOperation operation,
                                      int exactSlots,
                                      @Nonnull PopulationAdmissionForcePolicy forcePolicy) {
        this(
                identity,
                currentNpcUuid,
                expectedProfileRevision,
                oldOwnerUuid,
                newOwnerUuid,
                source,
                destination,
                operation,
                exactSlots,
                forcePolicy,
                defaultLifecycle(operation)
        );
    }

    private static PopulationCompanionLifecycle defaultLifecycle(PopulationAdmissionOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation == PopulationAdmissionOperation.LIFECYCLE_CHANGE) {
            throw new IllegalArgumentException("LIFECYCLE_CHANGE requires an explicit target lifecycle.");
        }
        return operation == PopulationAdmissionOperation.OWNER_CLEAR
                ? PopulationCompanionLifecycle.RELEASED
                : PopulationCompanionLifecycle.ACTIVE;
    }

    private static void validateLifecycle(PopulationAdmissionOperation operation,
                                          UUID newOwnerUuid,
                                          PopulationAdmissionLocation destination,
                                          PopulationCompanionLifecycle lifecycle) {
        if (lifecycle == PopulationCompanionLifecycle.RELEASED && newOwnerUuid != null) {
            throw new IllegalArgumentException("A released profile cannot retain an owner.");
        }
        if (lifecycle.occupiesPhysicalClaim() && destination == null) {
            throw new IllegalArgumentException("An active lifecycle requires a destination.");
        }
    }

    private static void validateForce(PopulationAdmissionOperation operation,
                                      PopulationAdmissionForcePolicy forcePolicy) {
        if (forcePolicy == PopulationAdmissionForcePolicy.ADMIN_OVERRIDE
                && operation != PopulationAdmissionOperation.ADMIN_FORCE) {
            throw new IllegalArgumentException("ADMIN_OVERRIDE requires the ADMIN_FORCE operation.");
        }
        if (forcePolicy == PopulationAdmissionForcePolicy.ENGINE_RELOCATION
                && operation != PopulationAdmissionOperation.REHOME) {
            throw new IllegalArgumentException("ENGINE_RELOCATION is valid only for REHOME.");
        }
        if (operation == PopulationAdmissionOperation.ADMIN_FORCE
                && forcePolicy != PopulationAdmissionForcePolicy.ADMIN_OVERRIDE) {
            throw new IllegalArgumentException("ADMIN_FORCE requires ADMIN_OVERRIDE.");
        }
    }

    private static void validateOperation(PopulationAdmissionOperation operation,
                                          long expectedRevision,
                                          UUID currentNpcUuid,
                                          UUID oldOwnerUuid,
                                          UUID newOwnerUuid,
                                          PopulationAdmissionLocation source,
                                          PopulationAdmissionLocation destination) {
        switch (operation) {
            case NEW_OWNERSHIP, LEGACY_ADOPTION -> {
                requireNull(oldOwnerUuid, "old owner", operation);
                requirePresent(newOwnerUuid, "new owner", operation);
                requirePresent(destination, "destination", operation);
            }
            case BREEDING -> {
                requireNull(oldOwnerUuid, "old owner", operation);
                requirePresent(destination, "destination", operation);
            }
            case OWNER_TRANSFER -> {
                requireExistingProfile(expectedRevision, currentNpcUuid, operation);
                requirePresent(oldOwnerUuid, "old owner", operation);
                requirePresent(newOwnerUuid, "new owner", operation);
                if (Objects.equals(oldOwnerUuid, newOwnerUuid)) {
                    throw new IllegalArgumentException("OWNER_TRANSFER requires different owners.");
                }
                requirePresent(source, "source", operation);
                requirePresent(destination, "destination", operation);
            }
            case OWNER_CLEAR -> {
                requireExistingProfile(expectedRevision, currentNpcUuid, operation);
                requirePresent(oldOwnerUuid, "old owner", operation);
                requireNull(newOwnerUuid, "new owner", operation);
                requirePresent(source, "source", operation);
            }
            case RESTORE -> {
                requirePresent(newOwnerUuid, "new owner", operation);
                requirePresent(destination, "destination", operation);
                if (oldOwnerUuid != null && !oldOwnerUuid.equals(newOwnerUuid)) {
                    throw new IllegalArgumentException("RESTORE cannot change owners; use OWNER_TRANSFER.");
                }
            }
            case REHOME -> {
                requireExistingProfile(expectedRevision, currentNpcUuid, operation);
                requirePresent(oldOwnerUuid, "old owner", operation);
                requirePresent(newOwnerUuid, "new owner", operation);
                if (!oldOwnerUuid.equals(newOwnerUuid)) {
                    throw new IllegalArgumentException("REHOME must preserve the owner.");
                }
                requirePresent(source, "source", operation);
                requirePresent(destination, "destination", operation);
                // A same-chunk relocation is a valid zero-delta transition and still needs a
                // mutation-bound capability so its durable revision follows the live move.
            }
            case LIFECYCLE_CHANGE -> {
                requireExistingProfile(expectedRevision, currentNpcUuid, operation);
                if (!Objects.equals(oldOwnerUuid, newOwnerUuid)) {
                    throw new IllegalArgumentException("LIFECYCLE_CHANGE cannot change owners.");
                }
            }
            case ADMIN_FORCE -> requirePresent(destination, "destination", operation);
        }
    }

    private static void requireExistingProfile(long expectedRevision,
                                               UUID currentNpcUuid,
                                               PopulationAdmissionOperation operation) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException(operation + " requires a committed profile revision.");
        }
        if (currentNpcUuid == null) {
            throw new IllegalArgumentException(operation + " requires the current NPC UUID.");
        }
    }

    private static void requirePresent(Object value, String field, PopulationAdmissionOperation operation) {
        if (value == null) {
            throw new IllegalArgumentException(operation + " requires " + field + ".");
        }
    }

    private static void requireNull(Object value, String field, PopulationAdmissionOperation operation) {
        if (value != null) {
            throw new IllegalArgumentException(operation + " cannot declare " + field + ".");
        }
    }
}
