package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves canonical dormant spawn sources and translates them into immutable admission units.
 * The caller remains responsible for reservation, live mutation, and terminal persistence.
 */
final class CompanionSpawnAdmissionPlanner {
    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimIndex;

    CompanionSpawnAdmissionPlanner(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimIndex
    ) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
    }

    @Nonnull
    PlannedBatch planBatch(
            @Nonnull List<CompanionSpawnAdmissionRequest> requests,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        List<CompanionPopulationAdmissionUnit> units = new ArrayList<>(requests.size());
        List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns = new ArrayList<>(requests.size());
        for (CompanionSpawnAdmissionRequest request : requests) {
            final PlanResult result;
            try {
                result = plan(request, policy);
            } catch (RuntimeException | LinkageError failure) {
                releaseUnadmitted(spawns, 0);
                throw failure;
            }
            if (!result.allowed()) {
                releaseUnadmitted(spawns, 0);
                return PlannedBatch.denied(result.reason());
            }
            units.add(result.unit());
            spawns.add(result.spawn());
        }
        return PlannedBatch.allowed(units, spawns);
    }

    @Nonnull
    private PlanResult plan(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        Source source = request.replacement()
                ? resolveSource(request)
                : Source.fresh(deterministicIdentity(request, "profile").toString());
        if (!source.allowed()) {
            return PlanResult.denied(source.reason());
        }
        String profileId = source.profileId();
        UUID plannedNpcUuid = deterministicIdentity(request, "npc");
        OwnerPopulationOperation effectiveOperation = source.legacyAdoption()
                ? OwnerPopulationOperation.LEGACY_ADOPTION
                : request.operation();
        PreparedCompanionSpawnBatch.ReservedSpawn spawn =
                new PreparedCompanionSpawnBatch.ReservedSpawn(
                        request, profileId, plannedNpcUuid, request.previousNpcUuid(),
                        effectiveOperation, source.legacyAdoption()
                );
        final CompanionPopulationAdmissionUnit unit;
        try {
            unit = CompanionSpawnAdmissionPlanFactory.create(
                    request,
                    profileId,
                    plannedNpcUuid,
                    effectiveOperation,
                    source.owner(),
                    source.claim(),
                    policy
            );
        } catch (RuntimeException | LinkageError failure) {
            releaseProvisional(spawn);
            throw failure;
        }
        return PlanResult.allowed(unit, spawn);
    }

    @Nonnull
    private Source resolveSource(@Nonnull CompanionSpawnAdmissionRequest request) {
        UUID previousUuid = request.previousNpcUuid();
        String aliasProfile = previousUuid == null
                ? null : identityResolver.resolveProfileId(previousUuid).orElse(null);
        if (request.canonicalProfileId() != null
                && aliasProfile != null
                && !request.canonicalProfileId().equals(aliasProfile)) {
            return Source.denied("spawn-source-canonical-profile-mismatch");
        }
        String profileId = request.canonicalProfileId() != null
                ? request.canonicalProfileId()
                : aliasProfile;
        OwnerPopulationEntry owner = profileId == null
                ? null : ownerIndex.entry(profileId).orElse(null);
        ClaimOccupancyEntry claim = profileId == null
                ? null : claimIndex.entry(profileId).orElse(null);
        boolean legacy = false;
        if (owner == null && claim == null && request.allowLegacyAdoption()) {
            if (request.canonicalProfileId() != null) {
                return Source.denied("spawn-source-population-profile-unavailable");
            }
            CompanionIdentityResolver.Resolution resolution = identityResolver.resolveOrAllocate(
                    previousUuid, request.idempotencyKey() + ":legacy-adoption"
            );
            if (request.canonicalProfileId() != null
                    && !request.canonicalProfileId().equals(resolution.profileId())) {
                return Source.denied("spawn-source-canonical-profile-mismatch");
            }
            if (!resolution.provisional()) {
                return Source.denied("spawn-source-population-profile-unavailable");
            }
            profileId = resolution.profileId();
            legacy = true;
        }
        if (profileId == null) {
            return Source.denied("spawn-source-canonical-profile-unavailable");
        }
        UUID currentUuid = identityResolver.currentNpcUuid(profileId).orElse(null);
        String invalid = validateDormantSource(
                previousUuid, currentUuid, request.requiredSourceLifecycle(), owner, claim, legacy
        );
        if (invalid == null) {
            invalid = validateRequestedOwner(
                    owner, request.ownerId(), request.operation(), legacy
            );
        }
        return invalid == null
                ? Source.allowed(profileId, owner, claim, legacy)
                : Source.denied(invalid);
    }

    /** Releases only planned identities that did not become caller-owned capabilities. */
    void releaseUnadmitted(
            @Nonnull List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns,
            int admittedCount
    ) {
        int retained = Math.max(0, Math.min(admittedCount, spawns.size()));
        for (int index = retained; index < spawns.size(); index++) {
            releaseProvisional(spawns.get(index));
        }
    }

    boolean releaseProvisional(@Nonnull PreparedCompanionSpawnBatch.ReservedSpawn spawn) {
        Objects.requireNonNull(spawn, "spawn");
        if (!spawn.legacyAdoption() || !spawn.claimIdentityTerminal()) {
            return true;
        }
        UUID previousNpcUuid = spawn.previousNpcUuid();
        if (previousNpcUuid == null) {
            return false;
        }
        try {
            return identityResolver.releaseProvisional(spawn.profileId(), previousNpcUuid);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Nullable
    static String validateDormantSource(
            @Nullable UUID previousNpcUuid,
            @Nullable UUID currentNpcUuid,
            @Nonnull CompanionLifecycleState requiredLifecycle,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim,
            boolean legacyAdoption
    ) {
        if (previousNpcUuid == null ? currentNpcUuid != null : !previousNpcUuid.equals(currentNpcUuid)) {
            return "spawn-source-duplicate-active-profile";
        }
        if (legacyAdoption) {
            return previousNpcUuid != null && owner == null && claim == null
                    ? null
                    : "spawn-source-population-state-mismatch";
        }
        if (owner == null || claim == null) {
            return "spawn-source-population-profile-unavailable";
        }
        if (owner.lifecycleState() == CompanionLifecycleState.ACTIVE
                || owner.lifecycleState() == CompanionLifecycleState.UNLOADED
                || owner.lifecycleState() == CompanionLifecycleState.RESTORING
                || (claim != null && (claim.lifecycleState() == CompanionLifecycleState.ACTIVE
                || claim.lifecycleState() == CompanionLifecycleState.UNLOADED
                || claim.lifecycleState() == CompanionLifecycleState.RESTORING))) {
            return "spawn-source-duplicate-active-profile";
        }
        if (owner.lifecycleState() != requiredLifecycle
                || (claim != null && claim.lifecycleState() != requiredLifecycle)) {
            return "spawn-source-lifecycle-mismatch";
        }
        if (claim != null && (owner.revision() != claim.revision()
                || !Objects.equals(owner.ownerId(), claim.ownerId()))) {
            return "spawn-source-population-state-mismatch";
        }
        return null;
    }

    @Nullable
    static String validateRequestedOwner(
            @Nullable OwnerPopulationEntry owner,
            @Nullable UUID requestedOwnerId,
            @Nonnull OwnerPopulationOperation operation,
            boolean legacyAdoption
    ) {
        if (legacyAdoption || operation == OwnerPopulationOperation.ADMIN_FORCE || owner == null) {
            return null;
        }
        UUID currentOwnerId = owner.ownerId();
        return currentOwnerId != null && !currentOwnerId.equals(requestedOwnerId)
                ? "spawn-source-owner-mismatch"
                : null;
    }

    /** Stable identities make an exact top-level retry contend on the same canonical unit. */
    @Nonnull
    static UUID deterministicIdentity(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull String purpose
    ) {
        String fingerprint = "tamework-companion-spawn-v1|" + purpose
                + "|" + request.idempotencyKey()
                + "|" + Objects.toString(request.canonicalProfileId(), "-")
                + "|" + Objects.toString(request.previousNpcUuid(), "-")
                + "|" + Objects.toString(request.requiredSourceLifecycle(), "-")
                + "|" + Objects.toString(request.ownerId(), "-")
                + "|" + request.worldName()
                + "|" + request.chunkX() + "|" + request.chunkZ()
                + "|" + request.operation()
                + "|" + request.sourceKind()
                + "|" + request.force();
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    record PlannedBatch(
            boolean allowed,
            @Nonnull String reason,
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns
    ) {
        static PlannedBatch allowed(
                List<CompanionPopulationAdmissionUnit> units,
                List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns
        ) {
            return new PlannedBatch(
                    true, "spawn-population-planned", List.copyOf(units), List.copyOf(spawns)
            );
        }

        static PlannedBatch denied(String reason) {
            return new PlannedBatch(false, reason, List.of(), List.of());
        }
    }

    private record PlanResult(
            boolean allowed,
            @Nonnull String reason,
            @Nullable CompanionPopulationAdmissionUnit unit,
            @Nullable PreparedCompanionSpawnBatch.ReservedSpawn spawn
    ) {
        static PlanResult allowed(
                CompanionPopulationAdmissionUnit unit,
                PreparedCompanionSpawnBatch.ReservedSpawn spawn
        ) {
            return new PlanResult(true, "spawn-population-unit-planned", unit, spawn);
        }

        static PlanResult denied(String reason) {
            return new PlanResult(false, reason, null, null);
        }
    }

    private record Source(
            boolean allowed,
            @Nonnull String reason,
            @Nullable String profileId,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim,
            boolean legacyAdoption
    ) {
        static Source fresh(String profileId) {
            return new Source(true, "spawn-source-new", profileId, null, null, false);
        }

        static Source allowed(
                String profileId,
                OwnerPopulationEntry owner,
                ClaimOccupancyEntry claim,
                boolean legacyAdoption
        ) {
            return new Source(
                    true, "spawn-source-resolved", profileId, owner, claim, legacyAdoption
            );
        }

        static Source denied(String reason) {
            return new Source(false, reason, null, null, null, false);
        }
    }
}
