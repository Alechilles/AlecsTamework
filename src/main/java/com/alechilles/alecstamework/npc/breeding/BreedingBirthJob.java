package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Immutable snapshot of one admitted breeding birth job.
 *
 * <p>The two parent identities are stored in the same canonical order as the pair key. Updated
 * snapshots replace prior snapshots inside {@link BreedingBirthJobRegistry}; callers never mutate
 * registry-owned state.
 */
public record BreedingBirthJob(@Nonnull UUID jobId,
                               @Nonnull BreedingPairKey pairKey,
                               @Nonnull BreedingParentIdentity firstParent,
                               @Nonnull BreedingParentIdentity secondParent,
                               @Nonnull BreedingBirthJobState state) {
    public BreedingBirthJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(pairKey, "pairKey");
        Objects.requireNonNull(firstParent, "firstParent");
        Objects.requireNonNull(secondParent, "secondParent");
        Objects.requireNonNull(state, "state");
        if (!pairKey.firstParentUuid().equals(firstParent.entityUuid())
                || !pairKey.secondParentUuid().equals(secondParent.entityUuid())) {
            throw new IllegalArgumentException("Parent identities must match canonical pair order");
        }
        if (firstParent.profileId().equals(secondParent.profileId())) {
            throw new IllegalArgumentException("A breeding pair requires two distinct profiles");
        }
    }

    /** Creates a reserved job while canonicalizing caller parent order. */
    @Nonnull
    public static BreedingBirthJob reserved(@Nonnull UUID jobId,
                                            @Nonnull String worldId,
                                            @Nonnull BreedingParentIdentity parentA,
                                            @Nonnull BreedingParentIdentity parentB) {
        Objects.requireNonNull(parentA, "parentA");
        Objects.requireNonNull(parentB, "parentB");
        BreedingPairKey pairKey = BreedingPairKey.of(worldId, parentA.entityUuid(), parentB.entityUuid());
        BreedingParentIdentity first = pairKey.firstParentUuid().equals(parentA.entityUuid()) ? parentA : parentB;
        BreedingParentIdentity second = first == parentA ? parentB : parentA;
        return new BreedingBirthJob(jobId, pairKey, first, second, BreedingBirthJobState.RESERVED);
    }

    @Nonnull
    BreedingBirthJob withState(@Nonnull BreedingBirthJobState nextState) {
        return new BreedingBirthJob(jobId, pairKey, firstParent, secondParent, nextState);
    }

    boolean hasSameIdentity(BreedingBirthJob other) {
        return jobId.equals(other.jobId)
                && pairKey.equals(other.pairKey)
                && firstParent.equals(other.firstParent)
                && secondParent.equals(other.secondParent);
    }
}
