package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Command, revival, and travel policy nested under {@code TwCompanionConfig.Command}. */
public final class TwCompanionCommandSettings {
    static final int MILLIS_PER_MINUTE = 60_000;
    static final double DEFAULT_RETURN_HOME_TELEPORT_DISTANCE = 96.0;
    static final double DEFAULT_RETURN_HOME_PATH_DISTANCE_BEFORE_TELEPORT = 24.0;
    static final int DEFAULT_RETURN_HOME_TELEPORT_DELAY_MS = 2500;
    static final double DEFAULT_RECALL_SAFE_SPAWN_DISTANCE = 20.0;
    static final double DEFAULT_RECALL_FORCE_RELOCATE_DISTANCE = 80.0;
    static final boolean DEFAULT_DEAD_RESPAWN_ENABLED = true;
    static final int DEFAULT_DEAD_RESPAWN_COOLDOWN_MS = 10_000;
    static final int DEFAULT_DEAD_RESPAWN_FOLLOW_RETRY_DELAY_MS = 1250;
    static final double DEFAULT_DEAD_RESPAWN_DISTANCE_CLOSE = 5.0;
    static final double DEFAULT_DEAD_RESPAWN_DISTANCE_NEAR = 8.0;
    static final double DEFAULT_DEAD_RESPAWN_DISTANCE_MID = 12.0;
    static final double DEFAULT_DEAD_RESPAWN_DISTANCE_FAR = 16.0;
    static final double DEFAULT_PLACEMENT_MIN_RELATIVE_Y = -2.0;
    static final double DEFAULT_PLACEMENT_MAX_RELATIVE_Y = 4.0;

    double returnHomeTeleportDistance =
            DEFAULT_RETURN_HOME_TELEPORT_DISTANCE;
    double returnHomePathDistanceBeforeTeleport =
            DEFAULT_RETURN_HOME_PATH_DISTANCE_BEFORE_TELEPORT;
    int returnHomeTeleportDelayMs = DEFAULT_RETURN_HOME_TELEPORT_DELAY_MS;
    double recallSafeSpawnDistance = DEFAULT_RECALL_SAFE_SPAWN_DISTANCE;
    double recallForceRelocateDistance =
            DEFAULT_RECALL_FORCE_RELOCATE_DISTANCE;
    boolean deadRespawnEnabled = DEFAULT_DEAD_RESPAWN_ENABLED;
    int deadRespawnCooldownMs = DEFAULT_DEAD_RESPAWN_COOLDOWN_MS;
    int deadRespawnFollowRetryDelayMs =
            DEFAULT_DEAD_RESPAWN_FOLLOW_RETRY_DELAY_MS;
    double deadRespawnDistanceClose = DEFAULT_DEAD_RESPAWN_DISTANCE_CLOSE;
    double deadRespawnDistanceNear = DEFAULT_DEAD_RESPAWN_DISTANCE_NEAR;
    double deadRespawnDistanceMid = DEFAULT_DEAD_RESPAWN_DISTANCE_MID;
    double deadRespawnDistanceFar = DEFAULT_DEAD_RESPAWN_DISTANCE_FAR;
    double placementMinRelativeY = DEFAULT_PLACEMENT_MIN_RELATIVE_Y;
    double placementMaxRelativeY = DEFAULT_PLACEMENT_MAX_RELATIVE_Y;
    TwCompanionReviveSettings revive = new TwCompanionReviveSettings();
    boolean reviveExplicit;
    TravelSettings travel = new TravelSettings();

    public double getReturnHomeTeleportDistance() {
        return returnHomeTeleportDistance;
    }

    public double getReturnHomePathDistanceBeforeTeleport() {
        return returnHomePathDistanceBeforeTeleport;
    }

    public int getReturnHomeTeleportDelayMs() {
        return returnHomeTeleportDelayMs;
    }

    public double getRecallSafeSpawnDistance() {
        return recallSafeSpawnDistance;
    }

    public double getRecallForceRelocateDistance() {
        return recallForceRelocateDistance;
    }

    public boolean isDeadRespawnEnabled() {
        return deadRespawnEnabled;
    }

    public int getDeadRespawnCooldownMs() {
        return deadRespawnCooldownMs;
    }

    public int getDeadRespawnFollowRetryDelayMs() {
        return deadRespawnFollowRetryDelayMs;
    }

    public double getDeadRespawnDistanceClose() {
        return deadRespawnDistanceClose;
    }

    public double getDeadRespawnDistanceNear() {
        return deadRespawnDistanceNear;
    }

    public double getDeadRespawnDistanceMid() {
        return deadRespawnDistanceMid;
    }

    public double getDeadRespawnDistanceFar() {
        return deadRespawnDistanceFar;
    }

    public double getPlacementMinRelativeY() {
        return placementMinRelativeY;
    }

    public double getPlacementMaxRelativeY() {
        return placementMaxRelativeY;
    }

    @Nonnull
    public TwCompanionReviveSettings getRevive() {
        return revive != null
                ? revive
                : new TwCompanionReviveSettings();
    }

    @Nonnull
    public TravelSettings getTravel() {
        return travel != null ? travel : new TravelSettings();
    }

    @Nonnull
    TwCompanionCommandSettings copy() {
        TwCompanionCommandSettings copy =
                new TwCompanionCommandSettings();
        copy.returnHomeTeleportDistance = returnHomeTeleportDistance;
        copy.returnHomePathDistanceBeforeTeleport =
                returnHomePathDistanceBeforeTeleport;
        copy.returnHomeTeleportDelayMs = returnHomeTeleportDelayMs;
        copy.recallSafeSpawnDistance = recallSafeSpawnDistance;
        copy.recallForceRelocateDistance = recallForceRelocateDistance;
        copy.deadRespawnEnabled = deadRespawnEnabled;
        copy.deadRespawnCooldownMs = deadRespawnCooldownMs;
        copy.deadRespawnFollowRetryDelayMs =
                deadRespawnFollowRetryDelayMs;
        copy.deadRespawnDistanceClose = deadRespawnDistanceClose;
        copy.deadRespawnDistanceNear = deadRespawnDistanceNear;
        copy.deadRespawnDistanceMid = deadRespawnDistanceMid;
        copy.deadRespawnDistanceFar = deadRespawnDistanceFar;
        copy.placementMinRelativeY = placementMinRelativeY;
        copy.placementMaxRelativeY = placementMaxRelativeY;
        copy.revive = getRevive().copy();
        copy.reviveExplicit = reviveExplicit;
        copy.travel = getTravel().copy();
        return copy;
    }

    void applyLegacyReviveEnabled(boolean enabled) {
        deadRespawnEnabled = enabled;
        if (!reviveExplicit) {
            getRevive().setEnabled(enabled);
        }
    }

    void applyLegacyReviveCooldownMs(int cooldownMs) {
        deadRespawnCooldownMs = cooldownMs;
        if (!reviveExplicit) {
            getRevive().setGameplayCooldownMs(cooldownMs);
        }
    }

    void applyLegacyReviveCooldownMinutes(double minutes) {
        deadRespawnCooldownMs = minutesToMillis(
                minutes,
                deadRespawnCooldownMs
        );
        if (!reviveExplicit) {
            getRevive().setGameplayCooldownMs(deadRespawnCooldownMs);
        }
    }

    void setExplicitRevive(@Nullable TwCompanionReviveSettings revive) {
        this.revive = revive == null
                ? new TwCompanionReviveSettings()
                : revive;
        this.reviveExplicit = true;
    }

    static int minutesToMillis(double minutes, int fallbackMs) {
        if (!Double.isFinite(minutes) || minutes < 0) {
            return fallbackMs;
        }
        double millis = minutes * MILLIS_PER_MINUTE;
        if (millis >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(millis);
    }

    /** Cross-world command movement settings. */
    public static final class TravelSettings {
        private static final boolean DEFAULT_CROSS_WORLD_RECALL_ENABLED =
                true;
        private static final boolean DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE =
                false;
        private static final String[] DEFAULT_STATE_FILTER =
                new String[] { "Follow", "Defend", "Aggressive" };

        boolean crossWorldRecallEnabled =
                DEFAULT_CROSS_WORLD_RECALL_ENABLED;
        TwCompanionConfig.TransferFailurePolicy onTransferFailure =
                TwCompanionConfig.TransferFailurePolicy.QueueForRecall;
        boolean followMasterOnWorldChange =
                DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE;
        String[] followMasterOnWorldChangeStateFilter =
                normalizeStateFilter(DEFAULT_STATE_FILTER);

        public boolean isCrossWorldRecallEnabled() {
            return crossWorldRecallEnabled;
        }

        @Nonnull
        public TwCompanionConfig.TransferFailurePolicy
        getOnTransferFailure() {
            return onTransferFailure != null
                    ? onTransferFailure
                    : TwCompanionConfig.TransferFailurePolicy.QueueForRecall;
        }

        public boolean isFollowMasterOnWorldChange() {
            return followMasterOnWorldChange;
        }

        public String[] getFollowMasterOnWorldChangeStateFilter() {
            return followMasterOnWorldChangeStateFilter != null
                    ? followMasterOnWorldChangeStateFilter.clone()
                    : ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public boolean isStateAllowedForWorldChange(@Nullable String state) {
            if (!followMasterOnWorldChange) {
                return false;
            }
            return isStateAllowedByFilters(
                    state,
                    followMasterOnWorldChangeStateFilter
            );
        }

        @Nonnull
        TravelSettings copy() {
            TravelSettings copy = new TravelSettings();
            copy.crossWorldRecallEnabled = crossWorldRecallEnabled;
            copy.onTransferFailure = getOnTransferFailure();
            copy.followMasterOnWorldChange = followMasterOnWorldChange;
            copy.followMasterOnWorldChangeStateFilter =
                    getFollowMasterOnWorldChangeStateFilter();
            return copy;
        }

        static String[] normalizeStateFilter(@Nullable String[] rawStates) {
            if (rawStates == null || rawStates.length == 0) {
                return ArrayUtil.EMPTY_STRING_ARRAY;
            }
            ArrayList<String> normalized =
                    new ArrayList<>(rawStates.length);
            for (String value : rawStates) {
                String normalizedValue = normalizeStateKey(value);
                if (normalizedValue != null) {
                    normalized.add(normalizedValue);
                }
            }
            return normalized.isEmpty()
                    ? ArrayUtil.EMPTY_STRING_ARRAY
                    : normalized.toArray(String[]::new);
        }

        static boolean isStateAllowedByFilters(
                @Nullable String state,
                @Nullable String[] filters
        ) {
            if (filters == null || filters.length == 0) {
                return true;
            }
            String normalizedState = normalizeStateKey(state);
            if (normalizedState == null) {
                return false;
            }
            for (String filter : filters) {
                if (matchesStateFilter(normalizedState, filter)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matchesStateFilter(
                @Nonnull String normalizedState,
                @Nullable String filter
        ) {
            String normalizedFilter = normalizeStateKey(filter);
            if (normalizedFilter == null) {
                return false;
            }
            if (normalizedState.equals(normalizedFilter)
                    || normalizedState.startsWith(normalizedFilter)) {
                return true;
            }
            String[] segments = normalizedState.split("[^a-z0-9]+");
            for (String segment : segments) {
                if (segment != null
                        && !segment.isBlank()
                        && (segment.equals(normalizedFilter)
                        || segment.startsWith(normalizedFilter))) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        private static String normalizeStateKey(@Nullable String state) {
            if (state == null || state.isBlank()) {
                return null;
            }
            String normalized = state.trim().toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? null : normalized;
        }
    }
}
