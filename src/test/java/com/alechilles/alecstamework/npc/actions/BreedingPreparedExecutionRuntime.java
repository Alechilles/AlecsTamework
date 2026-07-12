package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingCapacityHeadroom;
import com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingReservationScope;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Test runtime that crosses real prepared population terminal boundaries without a live world. */
final class BreedingPreparedExecutionRuntime
        implements BreedingJobExecutionService.Runtime<String> {
    private final Object storeScope;
    private final String worldName;
    private final int maxNearby;
    private final BreedingReservationScope scope;
    private final AtomicReference<Map<String, Integer>> liveNearby;
    private final BreedingPreparedPopulationRegistry prepared;
    private final AtomicInteger spawnCalls = new AtomicInteger();

    BreedingPreparedExecutionRuntime(
            Object storeScope,
            String worldName,
            int maxNearby,
            BreedingReservationScope scope,
            AtomicReference<Map<String, Integer>> liveNearby,
            BreedingPreparedPopulationRegistry prepared
    ) {
        this.storeScope = Objects.requireNonNull(storeScope, "storeScope");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.maxNearby = maxNearby;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.liveNearby = Objects.requireNonNull(liveNearby, "liveNearby");
        this.prepared = Objects.requireNonNull(prepared, "prepared");
    }

    int spawnCalls() {
        return spawnCalls.get();
    }

    @Override
    public BreedingJobExecutionService.ParentResolution<String> resolveParents(
            BreedingBirthJob job) {
        return BreedingJobExecutionService.ParentResolution.valid(storeScope, "parents");
    }

    @Override
    public void showHearts(BreedingBirthJob job, String context) {
    }

    @Override
    public BreedingPopulationAdmissionService.AdmissionRequest buildSpawnAdmissionRequest(
            BreedingBirthJob job,
            String context) {
        return new BreedingPopulationAdmissionService.AdmissionRequest(
                job.jobId(), worldName, job.mode(), job.plan(), job.anchor(), scope,
                maxNearby, liveNearby.get(), BreedingCapacityHeadroom.unlimited()
        );
    }

    @Override
    public boolean spawnChild(
            BreedingBirthJob job, PlannedChild child, int childIndex, String context) {
        return false;
    }

    @Override
    public BreedingJobExecutionService.ChildSpawnResult spawnChildResult(
            BreedingBirthJob job, PlannedChild child, int childIndex, String context) {
        spawnCalls.incrementAndGet();
        int unitIndex = prepared.unitIndexForActiveOrdinal(job.jobId(), childIndex);
        if (unitIndex < 0 || !prepared.claimForSpawn(job.jobId(), unitIndex)) {
            return BreedingJobExecutionService.ChildSpawnResult.FAILED;
        }
        if (!prepared.markMaterialized(job.jobId(), unitIndex)) {
            return BreedingJobExecutionService.ChildSpawnResult.AMBIGUOUS;
        }
        return prepared.commitSpawn(job.jobId(), unitIndex).join().committed()
                ? BreedingJobExecutionService.ChildSpawnResult.SPAWNED
                : BreedingJobExecutionService.ChildSpawnResult.AMBIGUOUS;
    }

    @Override
    public void onAdmissionShrunk(
            BreedingBirthJob original, List<PlannedChild> retained, String context) {
        List<String> keys = retainedKeys(original, retained);
        prepared.retainOnly(original.jobId(), keys, "spawn-time-nearby-cap-shrink");
    }

    private List<String> retainedKeys(BreedingBirthJob original, List<PlannedChild> retained) {
        List<String> keys = new ArrayList<>();
        int retainedIndex = 0;
        for (int sourceIndex = 0;
             sourceIndex < original.admittedChildren().size()
                     && retainedIndex < retained.size();
             sourceIndex++) {
            if (original.admittedChildren().get(sourceIndex).equals(retained.get(retainedIndex))) {
                keys.add(prepared.child(original.jobId(), sourceIndex).orElseThrow().childKey());
                retainedIndex++;
            }
        }
        if (retainedIndex != retained.size()) {
            throw new IllegalStateException("Retained child mapping is incomplete");
        }
        return List.copyOf(keys);
    }

    @Override
    public void cancelPopulation(BreedingBirthJob job, String reason) {
        prepared.cancelRemaining(job.jobId(), reason);
    }

    @Override
    public void onCompleted(BreedingBirthJob job, int spawnedChildren, String context) {
    }

    @Override
    public void rollbackProvisionalCooldown(BreedingBirthJob job) {
    }
}
