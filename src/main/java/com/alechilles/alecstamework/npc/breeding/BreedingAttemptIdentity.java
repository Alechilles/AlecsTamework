package com.alechilles.alecstamework.npc.breeding;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Derives one restart-stable breeding attempt identity from canonical parents and cooldown state.
 *
 * <p>The cooldown window written provisionally for an accepted attempt becomes the persisted
 * generation used to rediscover that attempt after restart. Parent entity UUIDs are deliberately
 * excluded because they are replaceable projections; canonical profile IDs own the attempt.</p>
 */
public final class BreedingAttemptIdentity {
    private static final String NAMESPACE = "alecs-tamework:breeding-attempt:v3:";

    private BreedingAttemptIdentity() {
    }

    /** Derives the identity for a new attempt from the exact cooldown fingerprints it will write. */
    @Nonnull
    public static UUID forAppliedCooldowns(
            @Nonnull BreedingParentIdentity parentA,
            @Nonnull AppliedCooldownFingerprint cooldownA,
            @Nonnull BreedingParentIdentity parentB,
            @Nonnull AppliedCooldownFingerprint cooldownB) {
        return derive(parentA, generation(cooldownA), parentB, generation(cooldownB), null);
    }

    /**
     * Allocates a collision-resistant new attempt before its exact identity is persisted in the
     * pair-indexed population journal. Replay reads that journal identity rather than rerolling it.
     */
    @Nonnull
    public static UUID forAppliedCooldowns(
            @Nonnull BreedingParentIdentity parentA,
            @Nonnull AppliedCooldownFingerprint cooldownA,
            @Nonnull BreedingParentIdentity parentB,
            @Nonnull AppliedCooldownFingerprint cooldownB,
            @Nonnull UUID admissionNonce) {
        return derive(
                parentA, generation(cooldownA), parentB, generation(cooldownB),
                Objects.requireNonNull(admissionNonce, "admissionNonce")
        );
    }

    /** Derives a possible replay identity from the cooldown windows currently persisted on parents. */
    @Nonnull
    public static UUID forPersistedCooldowns(
            @Nonnull BreedingParentIdentity parentA,
            @Nonnull ParentBreedingSnapshot snapshotA,
            @Nonnull BreedingParentIdentity parentB,
            @Nonnull ParentBreedingSnapshot snapshotB) {
        return derive(parentA, generation(snapshotA), parentB, generation(snapshotB), null);
    }

    /** Stable journal key shared by manual/passive jobs and every planned child unit. */
    @Nonnull
    public static String attemptKey(@Nonnull UUID jobId) {
        return "breeding:" + Objects.requireNonNull(jobId, "jobId");
    }

    private static UUID derive(BreedingParentIdentity parentA,
                               CooldownGeneration generationA,
                               BreedingParentIdentity parentB,
                               CooldownGeneration generationB,
                               UUID admissionNonce) {
        Objects.requireNonNull(parentA, "parentA");
        Objects.requireNonNull(parentB, "parentB");
        if (parentA.profileId().equals(parentB.profileId())) {
            throw new IllegalArgumentException("A breeding attempt requires two profiles");
        }
        boolean aFirst = parentA.profileId().compareTo(parentB.profileId()) < 0;
        BreedingParentIdentity first = aFirst ? parentA : parentB;
        BreedingParentIdentity second = aFirst ? parentB : parentA;
        CooldownGeneration firstGeneration = aFirst ? generationA : generationB;
        CooldownGeneration secondGeneration = aFirst ? generationB : generationA;
        String material = NAMESPACE
                + first.profileId() + ":" + firstGeneration.encode()
                + ":" + second.profileId() + ":" + secondGeneration.encode()
                + ":nonce=" + (admissionNonce == null ? "legacy" : admissionNonce);
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static CooldownGeneration generation(AppliedCooldownFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return new CooldownGeneration(
                fingerprint.cooldownStartedAtMs(),
                fingerprint.cooldownUntilMs(),
                fingerprint.cooldownDurationMs(),
                fingerprint.lastHappinessUpdateMs()
        );
    }

    private static CooldownGeneration generation(ParentBreedingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CooldownGeneration(
                snapshot.cooldownStartedAtMs(),
                snapshot.cooldownUntilMs(),
                snapshot.cooldownDurationMs(),
                snapshot.lastHappinessUpdateMs()
        );
    }

    private record CooldownGeneration(long startedAtMs,
                                      long untilMs,
                                      long durationMs,
                                      long happinessGeneration) {
        private String encode() {
            return startedAtMs + "," + untilMs + "," + durationMs
                    + "," + happinessGeneration;
        }
    }
}
