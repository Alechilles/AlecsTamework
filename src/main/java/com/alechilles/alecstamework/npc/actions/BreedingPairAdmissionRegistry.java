package com.alechilles.alecstamework.npc.actions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared manual/passive parent and duplicate-callback guard for in-flight breeding attempts. */
final class BreedingPairAdmissionRegistry {
    private static final BreedingPairAdmissionRegistry SHARED = new BreedingPairAdmissionRegistry();
    private static final long LEASE_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, UUID> jobByParent = new HashMap<>();
    private final Map<UUID, Job> jobsById = new HashMap<>();

    private BreedingPairAdmissionRegistry() {
    }

    @Nonnull
    static BreedingPairAdmissionRegistry shared() {
        return SHARED;
    }

    @Nullable
    Token tryReserve(@Nonnull UUID parentA, @Nonnull UUID parentB) {
        return tryReserve(parentA, parentB, 0L, 0L);
    }

    /** Uses the parents' last persisted cooldown generations as a restart-stable attempt key. */
    @Nullable
    Token tryReserve(@Nonnull UUID parentA,
                     @Nonnull UUID parentB,
                     long generationA,
                     long generationB) {
        Objects.requireNonNull(parentA, "parentA");
        Objects.requireNonNull(parentB, "parentB");
        if (parentA.equals(parentB)) {
            return null;
        }
        lock.lock();
        try {
            pruneExpired(System.nanoTime());
            if (jobByParent.containsKey(parentA) || jobByParent.containsKey(parentB)) {
                return null;
            }
            UUID jobId = stableJobId(parentA, parentB, generationA, generationB);
            Token token = new Token(jobId, parentA, parentB, System.nanoTime() + LEASE_NANOS);
            jobByParent.put(parentA, jobId);
            jobByParent.put(parentB, jobId);
            jobsById.put(jobId, new Job(token, JobState.RESERVED));
            return token;
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    private static UUID stableJobId(@Nonnull UUID parentA,
                                    @Nonnull UUID parentB,
                                    long generationA,
                                    long generationB) {
        boolean naturalOrder = parentA.compareTo(parentB) <= 0;
        UUID first = naturalOrder ? parentA : parentB;
        UUID second = naturalOrder ? parentB : parentA;
        long firstGeneration = naturalOrder ? generationA : generationB;
        long secondGeneration = naturalOrder ? generationB : generationA;
        String material = "alecs-tamework:breeding-job:" + first + ":" + firstGeneration
                + ":" + second + ":" + secondGeneration;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    boolean claimSpawn(@Nonnull Token token) {
        lock.lock();
        try {
            pruneExpired(System.nanoTime());
            Job job = jobsById.get(token.jobId());
            if (!matches(token) || job == null || job.state != JobState.RESERVED) {
                return false;
            }
            job.state = JobState.SPAWNING;
            return true;
        } finally {
            lock.unlock();
        }
    }

    void complete(@Nonnull Token token) {
        close(token);
    }

    void cancel(@Nonnull Token token) {
        close(token);
    }

    private void close(Token token) {
        lock.lock();
        try {
            if (!matches(token)) {
                return;
            }
            jobsById.remove(token.jobId());
            jobByParent.remove(token.parentA(), token.jobId());
            jobByParent.remove(token.parentB(), token.jobId());
        } finally {
            lock.unlock();
        }
    }

    private boolean matches(Token token) {
        return token != null
                && token.jobId().equals(jobByParent.get(token.parentA()))
                && token.jobId().equals(jobByParent.get(token.parentB()));
    }

    private void pruneExpired(long nowNanos) {
        jobsById.values().removeIf(job -> {
            if (nowNanos < job.token.expiresAtMonotonicNanos()) {
                return false;
            }
            jobByParent.remove(job.token.parentA(), job.token.jobId());
            jobByParent.remove(job.token.parentB(), job.token.jobId());
            return true;
        });
    }

    record Token(@Nonnull UUID jobId,
                 @Nonnull UUID parentA,
                 @Nonnull UUID parentB,
                 long expiresAtMonotonicNanos) {
    }

    private static final class Job {
        private final Token token;
        private JobState state;

        private Job(Token token, JobState state) {
            this.token = token;
            this.state = state;
        }
    }

    private enum JobState {
        RESERVED,
        SPAWNING
    }
}
