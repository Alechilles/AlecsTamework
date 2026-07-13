package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleasePresentationDispatcher.LiveProjection;
import com.alechilles.alecstamework.items.ManagedCoopReleasePresentationDispatcher.PresentationApplier;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.PresentationCommand;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Applies finalized release state and then emits its best-effort configured coop effects. */
final class HytaleManagedCoopReleasePresentationApplier implements PresentationApplier {
    private final PlannedNpcProjectionPostAddService postAdd;
    private final CoopEffectService effects;

    HytaleManagedCoopReleasePresentationApplier(
            @Nonnull PlannedNpcProjectionPostAddService postAdd,
            @Nonnull CoopEffectService effects) {
        this.postAdd = Objects.requireNonNull(postAdd, "postAdd");
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    @Override
    public void apply(@Nonnull PresentationCommand command,
                      @Nonnull LiveProjection projection,
                      @Nonnull CoopResidentStateRestorer.PostAddWork work) {
        postAdd.apply(projection.reference(), projection.npc(), projection.store(), work);
        CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                projection.reference(), projection.npc(), projection.store());
        effects.playTransitionEffects(
                projection.store(), projection.reference(), command.coopId());
    }
}
