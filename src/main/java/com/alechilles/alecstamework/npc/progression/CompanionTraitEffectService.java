package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.internal.TraitEffectRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/** Applies registered trait effects and refreshes the owned companion movement-speed effect. */
public final class CompanionTraitEffectService {
    private CompanionTraitEffectService() {
    }

    public static void applyTraitEffects(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        applyMoveSpeedEffect(npcRef, store);
        applyRegisteredTraitEffects(npcRef, store);
    }

    private static void applyRegisteredTraitEffects(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        Tamework instance = Tamework.getInstance();
        if (instance == null) {
            return;
        }
        TraitEffectRuntime runtime = instance.getTraitEffectRuntime();
        if (runtime != null) {
            runtime.applyRegisteredEffects(npcRef, store);
        }
    }

    private static void applyMoveSpeedEffect(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        CompanionMovementSpeedEffectService.apply(npcRef, store);
    }
}
