package com.alechilles.alecstamework.config.assets;

import java.util.Set;
import javax.annotation.Nonnull;

/** Applies declared-key-aware parent fallback to companion command settings. */
final class TwCompanionCommandInheritance {
    private TwCompanionCommandInheritance() {
    }

    static void inheritMissing(
            @Nonnull TwCompanionCommandSettings parent,
            @Nonnull TwCompanionCommandSettings current,
            @Nonnull Set<String> explicit
    ) {
        inheritCommandScalars(parent, current, explicit);
        inheritRevive(parent, current, explicit);
        inheritTravel(parent, current, explicit);
    }

    private static void inheritCommandScalars(
            TwCompanionCommandSettings parent,
            TwCompanionCommandSettings current,
            Set<String> explicit
    ) {
        if (!explicit.contains("ReturnHomeTeleportDistance")) {
            current.returnHomeTeleportDistance =
                    parent.returnHomeTeleportDistance;
        }
        if (!explicit.contains(
                "ReturnHomePathDistanceBeforeTeleport")) {
            current.returnHomePathDistanceBeforeTeleport =
                    parent.returnHomePathDistanceBeforeTeleport;
        }
        if (!explicit.contains("ReturnHomeTeleportDelayMs")) {
            current.returnHomeTeleportDelayMs =
                    parent.returnHomeTeleportDelayMs;
        }
        if (!explicit.contains("RecallSafeSpawnDistance")) {
            current.recallSafeSpawnDistance =
                    parent.recallSafeSpawnDistance;
        }
        if (!explicit.contains("RecallForceRelocateDistance")) {
            current.recallForceRelocateDistance =
                    parent.recallForceRelocateDistance;
        }
        if (!explicit.contains("DeadRespawnEnabled")) {
            current.deadRespawnEnabled = parent.deadRespawnEnabled;
        }
        if (!hasLegacyCooldownOverride(explicit)) {
            current.deadRespawnCooldownMs =
                    parent.deadRespawnCooldownMs;
        }
        if (!explicit.contains("DeadRespawnFollowRetryDelayMs")) {
            current.deadRespawnFollowRetryDelayMs =
                    parent.deadRespawnFollowRetryDelayMs;
        }
        if (!explicit.contains("DeadRespawnDistanceClose")) {
            current.deadRespawnDistanceClose =
                    parent.deadRespawnDistanceClose;
        }
        if (!explicit.contains("DeadRespawnDistanceNear")) {
            current.deadRespawnDistanceNear =
                    parent.deadRespawnDistanceNear;
        }
        if (!explicit.contains("DeadRespawnDistanceMid")) {
            current.deadRespawnDistanceMid =
                    parent.deadRespawnDistanceMid;
        }
        if (!explicit.contains("DeadRespawnDistanceFar")) {
            current.deadRespawnDistanceFar =
                    parent.deadRespawnDistanceFar;
        }
        if (!explicit.contains("PlacementMinRelativeY")) {
            current.placementMinRelativeY =
                    parent.placementMinRelativeY;
        }
        if (!explicit.contains("PlacementMaxRelativeY")) {
            current.placementMaxRelativeY =
                    parent.placementMaxRelativeY;
        }
    }

    private static void inheritRevive(
            TwCompanionCommandSettings parent,
            TwCompanionCommandSettings current,
            Set<String> explicit
    ) {
        TwCompanionReviveSettings childDecoded =
                current.getRevive().copy();
        if (!explicit.contains("Revive")) {
            current.revive = parent.getRevive().copy();
            if (explicit.contains("DeadRespawnEnabled")) {
                current.revive.setEnabled(childDecoded.isEnabled());
            }
            if (hasLegacyCooldownOverride(explicit)) {
                current.revive.setGameplayCooldownMs(
                        childDecoded.getGameplayCooldownMs()
                );
            }
            return;
        }

        TwCompanionReviveSettings parentRevive = parent.getRevive();
        TwCompanionReviveSettings currentRevive = current.getRevive();
        if (!explicit.contains("Revive.Enabled")) {
            currentRevive.setEnabled(parentRevive.isEnabled());
        }
        if (!explicit.contains("Revive.GameplayCooldownMs")) {
            currentRevive.setGameplayCooldownMs(
                    parentRevive.getGameplayCooldownMs()
            );
        }
        if (!explicit.contains("Revive.Costs")) {
            currentRevive.setCosts(parentRevive.getCosts());
        }
        if (!explicit.contains("Revive.InsufficientCostMessage")) {
            currentRevive.setInsufficientCostMessage(
                    parentRevive.getInsufficientCostMessage()
            );
        }
    }

    private static void inheritTravel(
            TwCompanionCommandSettings parent,
            TwCompanionCommandSettings current,
            Set<String> explicit
    ) {
        if (!explicit.contains("Travel")) {
            current.travel = parent.getTravel().copy();
            return;
        }
        TwCompanionCommandSettings.TravelSettings parentTravel =
                parent.getTravel();
        TwCompanionCommandSettings.TravelSettings currentTravel =
                current.getTravel();
        if (!explicit.contains("Travel.CrossWorldRecallEnabled")) {
            currentTravel.crossWorldRecallEnabled =
                    parentTravel.crossWorldRecallEnabled;
        }
        if (!explicit.contains("Travel.OnTransferFailure")) {
            currentTravel.onTransferFailure =
                    parentTravel.onTransferFailure;
        }
        if (!explicit.contains("Travel.FollowMasterOnWorldChange")) {
            currentTravel.followMasterOnWorldChange =
                    parentTravel.followMasterOnWorldChange;
        }
        if (!explicit.contains(
                "Travel.FollowMasterOnWorldChangeStateFilter")) {
            currentTravel.followMasterOnWorldChangeStateFilter =
                    parentTravel.getFollowMasterOnWorldChangeStateFilter();
        }
    }

    private static boolean hasLegacyCooldownOverride(
            @Nonnull Set<String> explicit
    ) {
        return explicit.contains("DeadRespawnCooldownMs")
                || explicit.contains("DeadRespawnCooldownMins");
    }
}
