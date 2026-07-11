package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Lazily opens one population-policy/cache snapshot for an eligible passive breeding sweep. */
final class BreedingPopulationSweepContext {
    private BreedingPopulationAdmissionService authority;
    private BreedingPopulationAdmissionService.PreparationContext context;

    @Nonnull
    BreedingPopulationAdmissionService.PreparationContext resolve(
            @Nonnull BreedingPopulationAdmissionService service
    ) {
        Objects.requireNonNull(service, "service");
        if (context == null) {
            authority = service;
            context = service.openPreparationContext();
        } else if (authority != service) {
            throw new IllegalStateException("Population runtime changed during passive breeding sweep.");
        }
        return context;
    }
}
