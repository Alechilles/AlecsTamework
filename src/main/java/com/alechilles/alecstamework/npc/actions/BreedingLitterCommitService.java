package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/** Applies the pairing effects and cooldowns to both parents of a litter. */
final class BreedingLitterCommitService {
    private final BreedingParentCooldownResolver cooldowns =
            new BreedingParentCooldownResolver();
    private final BreedingPairEffectsService effects =
            new BreedingPairEffectsService();

    boolean applyPairEffects(
            BreedingPairCandidate candidate,
            @Nullable TwBreedingConfig config,
            @Nullable CommandBuffer<EntityStore> commandBuffer
    ) {
        long now = BreedingTimeService.resolveCurrentTimeMs(
                candidate.store()
        );
        BreedingParentCooldownResolver.ResolvedCooldown source =
                cooldowns.resolve(
                        config,
                        candidate.sourceRef(),
                        candidate.store()
                );
        BreedingParentCooldownResolver.ResolvedCooldown partner =
                cooldowns.resolve(
                        config,
                        candidate.partnerRef(),
                        candidate.store()
                );
        return effects.apply(new BreedingPairEffectsService.EffectContext(
                candidate.sourceRef(),
                candidate.sourceNpc(),
                candidate.sourceBreeding(),
                candidate.partnerRef(),
                candidate.partnerNpc(),
                candidate.partnerBreeding(),
                source,
                partner,
                candidate.sourceOwner(),
                candidate.partnerOwner(),
                now,
                System.currentTimeMillis(),
                candidate.store(),
                commandBuffer
        ));
    }
}
