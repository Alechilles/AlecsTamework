package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingAttemptIdentity;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionRequest;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Translates an immutable birth plan into the shared population authority request. */
final class BreedingPopulationAdmissionRequestFactory {
    private BreedingPopulationAdmissionRequestFactory() {
    }

    /** Builds the shared-authority request for the current job-based breeding pipeline. */
    @Nonnull
    static BreedingPopulationAdmissionRequest create(
            @Nonnull String worldName,
            @Nonnull Vector3d spawnAnchor,
            @Nonnull BreedingBirthPlanSnapshot fullBirthPlan,
            @Nonnull List<BreedingBirthPlanSnapshot.PlannedChild> admittedChildren,
            int maximumAdmittedCount,
            @Nonnull UUID jobId,
            @Nonnull String parentAProfileId,
            @Nonnull String parentBProfileId) {
        List<BreedingPopulationAdmissionRequest.PlannedChild> children = new ArrayList<>(
                admittedChildren.size()
        );
        for (BreedingBirthPlanSnapshot.PlannedChild child : admittedChildren) {
            children.add(new BreedingPopulationAdmissionRequest.PlannedChild(
                    child.childKey(), child.ownerId(), child.ownerName()
            ));
        }
        return new BreedingPopulationAdmissionRequest(
                worldName,
                ChunkUtil.chunkCoordinate(spawnAnchor.x),
                ChunkUtil.chunkCoordinate(spawnAnchor.z),
                children,
                maximumAdmittedCount,
                false,
                BreedingAttemptIdentity.attemptKey(jobId),
                fullBirthPlan,
                List.of(parentAProfileId, parentBProfileId)
        );
    }
}
