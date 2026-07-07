# Avatar Flight

Avatar flight is the transformed-player flight path used by dragon-style mounts. The real player becomes the flight model, while Tamework can attach a visual rider copy for the seated player appearance.

## Controls

- Forward movement starts or resumes glide.
- Mouse look controls heading and pitch.
- Left-click with Flightmaster's Reins performs an upward flap.
- Right-click with Flightmaster's Reins applies the airbrake.
- Crouch applies direct downward movement.
- Forward boost uses the configured boost input/action and spends Vigour.

## Vigour

Vigour is a charge resource for avatar-flight movement abilities. Successful upward flaps and forward boosts spend charges. When Vigour reaches zero, those movement abilities stop applying until charges recover.

Default balance:

- `MaxCharges`: 6.
- `UpwardFlapCost`: 1.
- `ForwardBoostCost`: 1.
- `GroundedRechargeSecondsPerCharge`: 4.
- `FastFlightRechargeSecondsPerCharge`: 8.
- `FastFlightRechargeSpeedRatio`: 0.75 of boosted horizontal speed cap.
- `RechargeDelayAfterSpendSeconds`: 0.75.

Fast-flight recharge uses boosted max horizontal speed, calculated as `Movement.MaxForwardSpeed + Boost.ForwardImpulse`. With defaults, the threshold is `(14 + 7) * 0.75 = 15.75`, so ordinary cruise at `14` speed does not recharge by itself.

The recharge delay means a spent charge requires the delay plus the recharge cadence. With defaults, one airborne fast-flight charge after spending requires `0.75 + 8 = 8.75` seconds of continuous qualifying speed.

## Glide Balance

Unpowered forward glide has a passive sink so zero-Vigour flight eventually needs landing. Pitching upward can trade speed for altitude, but it bleeds momentum back toward normal cruise. Pitching downward can trade altitude for speed up to `Movement.MaxGlideSpeed`; with defaults, that is `15`, below the `15.75` fast-flight recharge threshold. Active boost is the only default path to the boosted max-speed band.

## HUD

The compact avatar-flight HUD appears above the hotbar while avatar flight is active. The upper bar shows current horizontal speed relative to boosted max speed. The pips show Vigour charges, including partial recharge progress. The HUD dims when grounded at full Vigour.

## Config Fields

### Movement

- `MaxForwardSpeed`: normal forward cruise speed.
- `MaxGlideSpeed`: maximum horizontal speed reachable without an active boost.
- `GlideSinkSpeed`: target downward speed for unpowered forward glide.
- `GlideSinkAcceleration`: rate at which glide approaches the sink speed.

### Vigour

- `Enabled`: enables charge spending and recharge.
- `MaxCharges`: maximum charges.
- `UpwardFlapCost`: charge cost for a successful flap.
- `ForwardBoostCost`: charge cost for a successful boost.
- `GroundedRechargeSecondsPerCharge`: grounded recharge rate.
- `FastFlightRechargeSecondsPerCharge`: airborne fast-flight recharge rate.
- `FastFlightRechargeSpeedRatio`: required ratio of boosted horizontal speed cap.
- `RechargeDelayAfterSpendSeconds`: delay before recharge resumes after spending.
- `HudEnabled`: shows the compact speed and Vigour HUD.
- `HudResendIntervalMs`: throttles unchanged HUD refreshes.
