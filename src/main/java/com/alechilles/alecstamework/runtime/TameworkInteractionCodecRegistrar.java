package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.interactions.*;
import com.alechilles.alecstamework.items.scarecrow.TameworkCollectScarecrowInteraction;
import com.alechilles.alecstamework.items.scarecrow.TameworkPlaceScarecrowInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/** Registers setup-only interaction codecs without starting runtime work. */
public final class TameworkInteractionCodecRegistrar {
    private TameworkInteractionCodecRegistrar() {
    }

    /** Registers all Tamework interaction codecs. */
    public static void registerAll() {
        Interaction.CODEC.register("TameworkSpawn", TameworkSpawnInteraction.class,
                TameworkSpawnInteraction.CODEC);
        Interaction.CODEC.register(TameworkManagedCoopCaptureCrateInteraction.TYPE_ID,
                TameworkManagedCoopCaptureCrateInteraction.class,
                TameworkManagedCoopCaptureCrateInteraction.CODEC);
        Interaction.CODEC.register("TameworkCaptureChannel", TameworkCaptureChannelInteraction.class,
                TameworkCaptureChannelInteraction.CODEC);
        Interaction.CODEC.register("TameworkNameNpc", TameworkNameNpcInteraction.class,
                TameworkNameNpcInteraction.CODEC);
        Interaction.CODEC.register("TameworkCommand", TameworkCommandInteraction.class,
                TameworkCommandInteraction.CODEC);
        Interaction.CODEC.register("TameworkCommandHotswap", TameworkCommandHotswapInteraction.class,
                TameworkCommandHotswapInteraction.CODEC);
        Interaction.CODEC.register("TameworkFlightFlap", TameworkFlightFlapInteraction.class,
                TameworkFlightFlapInteraction.CODEC);
        Interaction.CODEC.register("TameworkFlightAirbrake", TameworkFlightAirbrakeInteraction.class,
                TameworkFlightAirbrakeInteraction.CODEC);
        Interaction.CODEC.register("TameworkFlightBoost", TameworkFlightBoostInteraction.class,
                TameworkFlightBoostInteraction.CODEC);
        Interaction.CODEC.register("TameworkAvatarFlightCombatAbility",
                TameworkAvatarFlightCombatAbilityInteraction.class,
                TameworkAvatarFlightCombatAbilityInteraction.CODEC);
        Interaction.CODEC.register("TameworkClearFeedTroughWater",
                TameworkClearFeedTroughWaterInteraction.class,
                TameworkClearFeedTroughWaterInteraction.CODEC);
        Interaction.CODEC.register("TameworkLaunchProjectile", TameworkLaunchProjectileInteraction.class,
                TameworkLaunchProjectileInteraction.CODEC);
        Interaction.CODEC.register("TameworkLaunchHomingVisualProjectile",
                TameworkLaunchHomingVisualProjectileInteraction.class,
                TameworkLaunchHomingVisualProjectileInteraction.CODEC);
        Interaction.CODEC.register(TameworkPlaceScarecrowInteraction.TYPE_ID,
                TameworkPlaceScarecrowInteraction.class, TameworkPlaceScarecrowInteraction.CODEC);
        Interaction.CODEC.register(TameworkCollectScarecrowInteraction.TYPE_ID,
                TameworkCollectScarecrowInteraction.class, TameworkCollectScarecrowInteraction.CODEC);
    }
}
