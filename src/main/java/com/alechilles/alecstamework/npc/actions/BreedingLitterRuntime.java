package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.internal.ManagedBatchAdmissionAuthority;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Internal runtime bridge for the codec-created breeding action path.
 *
 * <p>The action package cannot receive the root composition through Hytale's
 * codec constructors. This bridge carries only stable operation values and
 * facade callbacks. It is not part of {@code TameworkApi}.</p>
 */
public final class BreedingLitterRuntime {
    private static final AtomicReference<BreedingLitterRuntime> CURRENT =
            new AtomicReference<>(unavailable());

    private final Supplier<ManagedBatchAdmissionAuthority> admissions;
    private final Function<BreedingLitterOperation, CompletionStage<Boolean>>
            prepareDurable;
    private final Function<BreedingLitterOperation, PublicOperationSubmission>
            submitDurable;

    private BreedingLitterRuntime(
            Supplier<ManagedBatchAdmissionAuthority> admissions,
            Function<BreedingLitterOperation, CompletionStage<Boolean>>
                    prepareDurable,
            Function<BreedingLitterOperation, PublicOperationSubmission>
                    submitDurable
    ) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.prepareDurable = Objects.requireNonNull(
                prepareDurable, "prepareDurable"
        );
        this.submitDurable = Objects.requireNonNull(
                submitDurable, "submitDurable"
        );
    }

    /** Installs the current composition callbacks for internal breeding use. */
    public static void install(
            @Nonnull Supplier<ManagedBatchAdmissionAuthority> admissions,
            @Nonnull Function<BreedingLitterOperation, CompletionStage<Boolean>>
                    prepareDurable,
            @Nonnull Function<BreedingLitterOperation, PublicOperationSubmission>
                    submitDurable
    ) {
        CURRENT.set(new BreedingLitterRuntime(
                admissions, prepareDurable, submitDurable
        ));
    }

    static BreedingLitterRuntime current() {
        return CURRENT.get();
    }

    CompletionStage<PopulationAdmissionDecision> prepareManaged(
            ManagedBatchAdmissionRequest request
    ) {
        ManagedBatchAdmissionAuthority authority = admissions.get();
        return authority == null
                ? CompletableFuture.completedFuture(
                        PopulationAdmissionDecision.unavailable(
                                "breeding_litter_runtime_unavailable"
                        )
                )
                : authority.prepareManagedBatch(request);
    }

    CompletionStage<Boolean> prepareDurable(BreedingLitterOperation litter) {
        try {
            CompletionStage<Boolean> result = prepareDurable.apply(litter);
            return result == null
                    ? CompletableFuture.completedFuture(false) : result;
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    CompletionStage<ManagedBatchSettlement> cancelManaged(
            PopulationAdmissionToken token
    ) {
        ManagedBatchAdmissionAuthority authority = admissions.get();
        if (!(authority instanceof PopulationAdmissionApi population)) {
            return CompletableFuture.completedFuture(unavailable(
                    "breeding_litter_cancel_unavailable"
            ));
        }
        try {
            CompletionStage<PopulationAdmissionDecision> result =
                    population.cancel(token);
            return result == null
                    ? CompletableFuture.completedFuture(unavailable(
                            "breeding_litter_cancel_unavailable"
                    ))
                    : result.thenApply(decision ->
                            decision != null
                                    && decision.status()
                                    == PopulationAdmissionDecision.Status.CANCELED
                                    ? new ManagedBatchSettlement(
                                            ManagedBatchSettlement.Status.CANCELED,
                                            decision.reason(),
                                            1,
                                            Set.of(),
                                            Map.of()
                                    )
                                    : unavailable(
                                            decision == null
                                                    ? "breeding_litter_cancel_unavailable"
                                                    : decision.reason()
                                    ));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(unavailable(
                    "breeding_litter_cancel_failed"
            ));
        }
    }

    PublicOperationSubmission submitDurable(BreedingLitterOperation litter) {
        try {
            return submitDurable.apply(litter);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    static void scheduleCompanionXp(
            String worldName,
            java.util.UUID parentA,
            java.util.UUID parentB
    ) {
        World world;
        try {
            world = Universe.get().getWorld(worldName);
        } catch (RuntimeException failure) {
            return;
        }
        if (world == null || !world.isAlive()) {
            return;
        }
        try {
            world.execute(() -> {
                World current = Universe.get().getWorld(worldName);
                if (current == null || current != world
                        || !current.isAlive()
                        || current.getEntityStore() == null) {
                    return;
                }
                Store<EntityStore> store = current.getEntityStore().getStore();
                Ref<EntityStore> a = current.getEntityRef(parentA);
                Ref<EntityStore> b = current.getEntityRef(parentB);
                if (live(a, store)) {
                    CompanionLevelingService.awardBreedingXp(a, store);
                }
                if (live(b, store)) {
                    CompanionLevelingService.awardBreedingXp(b, store);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            // XP is ancillary to the already settled litter.
        }
    }

    private static boolean live(
            @Nullable Ref<EntityStore> ref,
            Store<EntityStore> store
    ) {
        return ref != null && ref.isValid()
                && store.getComponent(ref, NPCEntity.getComponentType()) != null;
    }

    private static BreedingLitterRuntime unavailable() {
        return new BreedingLitterRuntime(
                () -> ManagedBatchAdmissionAuthority.unavailable(),
                ignored -> CompletableFuture.completedFuture(false),
                ignored -> null
        );
    }

    private static ManagedBatchSettlement unavailable(String reason) {
        return new ManagedBatchSettlement(
                ManagedBatchSettlement.Status.UNAVAILABLE,
                reason,
                1,
                Set.of(),
                Map.of()
        );
    }
}
