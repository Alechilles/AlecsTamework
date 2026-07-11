package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionRequest;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Translates an immutable birth plan into the shared population authority request. */
final class BreedingPopulationAdmissionRequestFactory {
    private BreedingPopulationAdmissionRequestFactory() {
    }

    @Nonnull
    static BreedingPopulationAdmissionRequest create(
            @Nonnull String worldName,
            @Nonnull Vector3d spawnAnchor,
            @Nonnull BreedingBirthPlan birthPlan,
            @Nonnull BreedingBirthPlanSnapshot fullBirthPlan,
            @Nonnull BreedingNearbyReservationService.Reservation nearbyReservation,
            @Nonnull BreedingPairAdmissionRegistry.Token pairToken
    ) {
        List<BreedingPopulationAdmissionRequest.PlannedChild> children = new ArrayList<>();
        for (BreedingBirthPlan.PlannedChild child : birthPlan.children()) {
            children.add(new BreedingPopulationAdmissionRequest.PlannedChild(
                    child.childKey(),
                    child.owner().ownerId(),
                    child.owner().ownerName()
            ));
        }
        return new BreedingPopulationAdmissionRequest(
                worldName,
                ChunkUtil.chunkCoordinate(spawnAnchor.x),
                ChunkUtil.chunkCoordinate(spawnAnchor.z),
                children,
                nearbyReservation.admittedCount(),
                false,
                attemptKey(pairToken),
                fullBirthPlan
        );
    }

    @Nonnull
    static String attemptKey(@Nonnull BreedingPairAdmissionRegistry.Token pairToken) {
        return "breeding:" + pairToken.jobId();
    }
}
