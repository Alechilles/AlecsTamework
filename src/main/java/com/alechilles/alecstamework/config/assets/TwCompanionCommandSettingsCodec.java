package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

/** Owns the codec graph for the nested companion command policy. */
final class TwCompanionCommandSettingsCodec {
    private static final ArrayCodec<Long> LONG_ARRAY_CODEC =
            new ArrayCodec<>(Codec.LONG, Long[]::new);

    private static final BuilderCodec<TwCompanionReviveSettings>
            REVIVE_CODEC = BuilderCodec.builder(
                    TwCompanionReviveSettings.class,
                    TwCompanionReviveSettings::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) ->
                            settings.setEnabled(value == null || value),
                    TwCompanionReviveSettings::isEnabled
            )
            .documentation(
                    "Enables paid command-panel revival for this role. "
                            + "Inheritance: an omitted value inherits."
            )
            .add()
            .<Long>append(
                    new KeyedCodec<>("GameplayCooldownMs", Codec.LONG),
                    (settings, value) -> {
                        if (value != null) {
                            settings.setGameplayCooldownMs(value);
                        }
                    },
                    TwCompanionReviveSettings::getGameplayCooldownMs
            )
            .documentation(
                    "Non-negative balance duration after death; zero adds no "
                            + "delay. This is not a world timestamp. "
                            + "Inheritance: an omitted value inherits."
            )
            .add()
            .<TwItemCostComponent[]>append(
                    new KeyedCodec<>(
                            "Costs",
                            TwItemCostComponent.ARRAY_CODEC
                    ),
                    TwCompanionReviveSettings::setCosts,
                    TwCompanionReviveSettings::getCosts
            )
            .documentation(
                    "Ordered AND item cost. Every component is required. "
                            + "Inheritance: an explicit array replaces the "
                            + "parent array (no append or merge). Item IDs "
                            + "must be unique and quantities positive."
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("InsufficientCostMessage", Codec.STRING),
                    TwCompanionReviveSettings::setInsufficientCostMessage,
                    TwCompanionReviveSettings::getInsufficientCostMessage
            )
            .documentation(
                    "Optional localization key used when any configured cost "
                            + "component is missing. Inheritance: an omitted "
                            + "value inherits."
            )
            .add()
            .build();

    private static final BuilderCodec<TwCompanionSummonSettings>
            SUMMON_CODEC = BuilderCodec.builder(
                    TwCompanionSummonSettings.class,
                    TwCompanionSummonSettings::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) ->
                            settings.setEnabled(value != null && value),
                    TwCompanionSummonSettings::isEnabled
            )
            .documentation(
                    "Enables roster summon, dismiss, and stored lifecycle. "
                            + "Inheritance: an omitted value inherits."
            )
            .add()
            .<Long>append(
                    new KeyedCodec<>("ActiveDurationMs", Codec.LONG),
                    (settings, value) -> {
                        if (value != null) {
                            settings.setActiveDurationMs(value);
                        }
                    },
                    TwCompanionSummonSettings::getActiveDurationMs
            )
            .documentation(
                    "Non-negative active-session duration; zero is unlimited. "
                            + "This is not a world timestamp. Inheritance: an "
                            + "omitted value inherits."
            )
            .add()
            .<Long>append(
                    new KeyedCodec<>("ResummonCooldownMs", Codec.LONG),
                    (settings, value) -> {
                        if (value != null) {
                            settings.setResummonCooldownMs(value);
                        }
                    },
                    TwCompanionSummonSettings::getResummonCooldownMs
            )
            .documentation(
                    "Non-negative duration after dismissal or expiry; zero "
                            + "disables the additional cooldown. This is not a "
                            + "world timestamp. Inheritance: omitted inherits."
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("AutoStoreOnOwnerLogout", Codec.BOOLEAN),
                    (settings, value) ->
                            settings.setAutoStoreOnOwnerLogout(
                                    value == null || value
                            ),
                    TwCompanionSummonSettings::isAutoStoreOnOwnerLogout
            )
            .documentation(
                    "Stores an active companion when its owner logs out. "
                            + "Inheritance: an omitted value inherits."
            )
            .add()
            .<Long[]>append(
                    new KeyedCodec<>(
                            "ExpiryWarningThresholdsMs",
                            LONG_ARRAY_CODEC
                    ),
                    TwCompanionSummonSettings
                            ::setExpiryWarningThresholdsMs,
                    TwCompanionSummonSettings
                            ::getExpiryWarningThresholdsBoxed
            )
            .documentation(
                    "Strictly descending unique positive remaining-time "
                            + "warnings below positive ActiveDurationMs. "
                            + "Inheritance: an explicit array replaces the "
                            + "parent array (no append or merge)."
            )
            .add()
            .build();

    private static final BuilderCodec<TwCompanionCommandSettings.TravelSettings>
            TRAVEL_CODEC = BuilderCodec.builder(
                    TwCompanionCommandSettings.TravelSettings.class,
                    TwCompanionCommandSettings.TravelSettings::new
            )
            .<Boolean>append(
                    new KeyedCodec<>(
                            "CrossWorldRecallEnabled",
                            Codec.BOOLEAN
                    ),
                    (settings, value) ->
                            settings.crossWorldRecallEnabled =
                                    value != null && value,
                    settings -> settings.crossWorldRecallEnabled
            )
            .documentation(
                    "Allows companion recall to work across world boundaries."
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("OnTransferFailure", Codec.STRING),
                    (settings, value) -> settings.onTransferFailure =
                            TwCompanionConfig.TransferFailurePolicy.parse(
                                    value,
                                    settings.onTransferFailure
                            ),
                    settings -> settings.getOnTransferFailure().name()
            )
            .documentation(
                    "Fallback behavior to use when world transfer fails."
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>(
                            "FollowMasterOnWorldChange",
                            Codec.BOOLEAN
                    ),
                    (settings, value) ->
                            settings.followMasterOnWorldChange =
                                    value != null && value,
                    settings -> settings.followMasterOnWorldChange
            )
            .documentation(
                    "If true, companion follows owner during world changes."
            )
            .add()
            .<String[]>append(
                    new KeyedCodec<>(
                            "FollowMasterOnWorldChangeStateFilter",
                            Codec.STRING_ARRAY
                    ),
                    (settings, value) ->
                            settings.followMasterOnWorldChangeStateFilter =
                                    TwCompanionCommandSettings.TravelSettings
                                            .normalizeStateFilter(value),
                    settings ->
                            settings.followMasterOnWorldChangeStateFilter
            )
            .documentation(
                    "State filter for cross-world follow behavior."
            )
            .add()
            .build();

    private static final BuilderCodec<TwCompanionFlightToggleSettings>
            FLIGHT_TOGGLE_CODEC = BuilderCodec.builder(
                    TwCompanionFlightToggleSettings.class,
                    TwCompanionFlightToggleSettings::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.setEnabled(value != null && value),
                    TwCompanionFlightToggleSettings::isEnabled
            )
            .documentation("Enables this role's flight-toggle capability.")
            .add()
            .<String>append(
                    new KeyedCodec<>("HookId", Codec.STRING),
                    TwCompanionFlightToggleSettings::setHookId,
                    TwCompanionFlightToggleSettings::getHookId
            )
            .documentation("Hook dispatched to toggle this role's flight mode.")
            .add()
            .build();

    private static final BuilderCodec<TwCompanionShoulderRideSettings>
            SHOULDER_RIDE_CODEC = BuilderCodec.builder(
                    TwCompanionShoulderRideSettings.class,
                    TwCompanionShoulderRideSettings::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.setEnabled(
                            value != null && value),
                    TwCompanionShoulderRideSettings::isEnabled
            )
            .documentation("Enables the bonded-card shoulder-ride action. Inheritance: an omitted value inherits.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("OffsetX", Codec.DOUBLE),
                    (settings, value) -> settings.setOffsetX(value),
                    TwCompanionShoulderRideSettings::getOffsetX
            )
            .documentation("Horizontal attachment offset from the player root. Inheritance: an omitted value inherits.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("OffsetY", Codec.DOUBLE),
                    (settings, value) -> settings.setOffsetY(value),
                    TwCompanionShoulderRideSettings::getOffsetY
            )
            .documentation("Vertical attachment offset from the player root. Inheritance: an omitted value inherits.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("OffsetZ", Codec.DOUBLE),
                    (settings, value) -> settings.setOffsetZ(value),
                    TwCompanionShoulderRideSettings::getOffsetZ
            )
            .documentation("Forward attachment offset from the player root. Inheritance: an omitted value inherits.")
            .add()
            .build();

    static final BuilderCodec<TwCompanionCommandSettings> CODEC =
            BuilderCodec.builder(
                    TwCompanionCommandSettings.class,
                    TwCompanionCommandSettings::new
            )
            .<Double>append(
                    new KeyedCodec<>(
                            "ReturnHomeTeleportDistance",
                            Codec.DOUBLE
                    ),
                    (settings, value) ->
                            settings.returnHomeTeleportDistance = value,
                    settings -> settings.returnHomeTeleportDistance
            )
            .documentation(
                    "Distance threshold before return-home teleport is attempted."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>(
                            "ReturnHomePathDistanceBeforeTeleport",
                            Codec.DOUBLE
                    ),
                    (settings, value) ->
                            settings.returnHomePathDistanceBeforeTeleport =
                                    value,
                    settings ->
                            settings.returnHomePathDistanceBeforeTeleport
            )
            .documentation(
                    "Path distance threshold before teleport fallback is used."
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>(
                            "ReturnHomeTeleportDelayMs",
                            Codec.INTEGER
                    ),
                    (settings, value) ->
                            settings.returnHomeTeleportDelayMs = value,
                    settings -> settings.returnHomeTeleportDelayMs
            )
            .documentation(
                    "Delay in milliseconds before return-home teleport occurs."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallSafeSpawnDistance", Codec.DOUBLE),
                    (settings, value) ->
                            settings.recallSafeSpawnDistance = value,
                    settings -> settings.recallSafeSpawnDistance
            )
            .documentation(
                    "Distance used when searching a safe recall spawn position."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>(
                            "RecallForceRelocateDistance",
                            Codec.DOUBLE
                    ),
                    (settings, value) ->
                            settings.recallForceRelocateDistance = value,
                    settings -> settings.recallForceRelocateDistance
            )
            .documentation(
                    "Distance threshold that forces relocation during recall."
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("DeadRespawnEnabled", Codec.BOOLEAN),
                    (settings, value) ->
                            settings.applyLegacyReviveEnabled(value),
                    settings -> settings.deadRespawnEnabled
            )
            .documentation(
                    "Legacy revival enable flag. Revive.Enabled is "
                            + "authoritative when Revive is present."
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnCooldownMs", Codec.INTEGER),
                    (settings, value) ->
                            settings.applyLegacyReviveCooldownMs(value),
                    settings -> settings.deadRespawnCooldownMs
            )
            .documentation(
                    "Legacy revival cooldown. Revive.GameplayCooldownMs is "
                            + "authoritative when Revive is present."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>(
                            "DeadRespawnCooldownMins",
                            Codec.DOUBLE
                    ),
                    (settings, value) -> {
                        if (value != null) {
                            settings.applyLegacyReviveCooldownMinutes(value);
                        }
                    },
                    settings -> null
            )
            .documentation(
                    "Legacy minute-based revival cooldown. "
                            + "Revive.GameplayCooldownMs is authoritative when "
                            + "Revive is present."
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>(
                            "DeadRespawnFollowRetryDelayMs",
                            Codec.INTEGER
                    ),
                    (settings, value) ->
                            settings.deadRespawnFollowRetryDelayMs = value,
                    settings -> settings.deadRespawnFollowRetryDelayMs
            )
            .documentation(
                    "Retry delay in milliseconds for dead-respawn follow attempts."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceClose", Codec.DOUBLE),
                    (settings, value) ->
                            settings.deadRespawnDistanceClose = value,
                    settings -> settings.deadRespawnDistanceClose
            )
            .documentation("Distance threshold for the close respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceNear", Codec.DOUBLE),
                    (settings, value) ->
                            settings.deadRespawnDistanceNear = value,
                    settings -> settings.deadRespawnDistanceNear
            )
            .documentation("Distance threshold for the near respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceMid", Codec.DOUBLE),
                    (settings, value) ->
                            settings.deadRespawnDistanceMid = value,
                    settings -> settings.deadRespawnDistanceMid
            )
            .documentation("Distance threshold for the mid respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceFar", Codec.DOUBLE),
                    (settings, value) ->
                            settings.deadRespawnDistanceFar = value,
                    settings -> settings.deadRespawnDistanceFar
            )
            .documentation("Distance threshold for the far respawn range.")
            .add()
            .<TwCompanionReviveSettings>append(
                    new KeyedCodec<>("Revive", REVIVE_CODEC),
                    TwCompanionCommandSettings::setExplicitRevive,
                    TwCompanionCommandSettings::getRevive
            )
            .documentation(
                    "Paid command revival settings. Inheritance: omitted "
                            + "section inherits; when present, explicit nested "
                            + "fields override and missing nested fields "
                            + "inherit. Costs replace as an array."
            )
            .add()
            .<TwCompanionSummonSettings>append(
                    new KeyedCodec<>("Summon", SUMMON_CODEC),
                    TwCompanionCommandSettings::setSummon,
                    TwCompanionCommandSettings::getSummon
            )
            .documentation(
                    "Timed roster summoning settings. Inheritance: omitted "
                            + "section inherits; when present, explicit nested "
                            + "fields override and missing nested fields "
                            + "inherit. Warning arrays replace."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMinRelativeY", Codec.DOUBLE),
                    (settings, value) ->
                            settings.placementMinRelativeY = value,
                    settings -> settings.placementMinRelativeY
            )
            .documentation(
                    "Minimum relative Y offset allowed for placement checks."
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMaxRelativeY", Codec.DOUBLE),
                    (settings, value) ->
                            settings.placementMaxRelativeY = value,
                    settings -> settings.placementMaxRelativeY
            )
            .documentation(
                    "Maximum relative Y offset allowed for placement checks."
            )
            .add()
            .<TwCompanionCommandSettings.TravelSettings>append(
                    new KeyedCodec<>("Travel", TRAVEL_CODEC),
                    (settings, value) -> settings.travel =
                            value == null
                                    ? new TwCompanionCommandSettings
                                            .TravelSettings()
                                    : value,
                    settings -> settings.travel
            )
            .documentation(
                    "Travel/recall behavior settings for companions. "
                            + "Inheritance: omitted section inherits; when "
                            + "present, only explicit nested fields override."
            )
            .add()
            .<TwCompanionFlightToggleSettings>append(
                    new KeyedCodec<>("FlightToggle", FLIGHT_TOGGLE_CODEC),
                    (settings, value) -> settings.flightToggle = value == null
                            ? new TwCompanionFlightToggleSettings()
                            : value,
                    TwCompanionCommandSettings::getFlightToggle
            )
            .documentation(
                    "Flight-toggle hook capability. Inheritance: omitted "
                            + "section inherits; when present, explicit nested "
                            + "fields override and missing nested fields inherit."
            )
            .add()
            .<TwCompanionShoulderRideSettings>append(
                    new KeyedCodec<>("ShoulderRide", SHOULDER_RIDE_CODEC),
                    (settings, value) -> settings.shoulderRide = value == null
                            ? new TwCompanionShoulderRideSettings()
                            : value,
                    TwCompanionCommandSettings::getShoulderRide
            )
            .documentation(
                    "Shoulder-ride capability and attachment offset. Inheritance: omitted section inherits; when "
                            + "present, explicit nested fields override and missing nested fields inherit."
            )
            .add()
            .build();

    private TwCompanionCommandSettingsCodec() {
    }
}
