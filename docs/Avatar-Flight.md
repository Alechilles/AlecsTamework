# Avatar Flight

Avatar flight is the transformed-player flight path used by dragon-style mounts. The real player becomes the flight model, while Tamework can attach a visual rider copy for the seated player appearance.

## Controls

- Forward movement starts or resumes glide.
- Mouse look controls heading and pitch.
- Holding crouch on the ground charges a launch. Releasing after the minimum charge starts avatar flight with upward and forward launch impulse.
- Left-click with Flightmaster's Reins performs an upward flap.
- Right-click with Flightmaster's Reins applies the airbrake.
- Q with Flightmaster's Reins performs a forward boost.
- Crouch applies direct downward movement while airborne unless it began as a grounded launch charge.
- Forward boost uses the configured boost input/action and spends Vigour. Q is the default reliable input path because airborne sprint is not consistently detectable.

## Vigour

Vigour is a charge resource for avatar-flight movement abilities. Successful charged launches, upward flaps, and forward boosts spend charges. When Vigour reaches zero, those movement abilities stop applying until charges recover.

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

## Launch

Charged launch is the default takeoff path for avatar flight. With `Launch.PreferredInput` set to `CrouchHold`, holding crouch while grounded starts charging instead of feeding crouch into descent. Releasing before `Launch.MinChargeMs` cancels the launch, while releasing after the minimum applies a charge-scaled upward and forward impulse. If the player leaves the ground during the hold, the release can still apply the launch that began on the ground. Airborne crouch without an active grounded launch charge remains direct downward movement.

Default charge timing is `500ms` to `3000ms` with a `0.65` exponent. That front-loads some launch strength after the minimum hold, while still rewarding longer charge time. Partial launches cost `1` Vigour by default, and launches at or above `Launch.FullChargeCostThreshold` cost `2`.

## Glide Balance

Unpowered forward glide has a passive sink so zero-Vigour flight eventually needs landing. Level forward glide does not act like a motor: forward input can seed movement from hover or stall, but flat unpowered flight decays gradually toward the `Movement.GlideStartKickSpeed` floor instead of preserving `Movement.NeutralGlideSpeed` forever. Very shallow downward pitch, currently less than about `8°`, is treated like neutral glide for speed and sink so tiny dive angles cannot preserve max glide speed or carry leftover flap lift indefinitely. This neutral/shallow speed bleed is intentionally gentle, with default `Movement.NeutralGlideDeceleration` set to `0.15`, so it preserves more horizontal momentum than a shallow climb while still losing altitude through glide sink. Higher speeds require sustained diving or spending Vigour. Pitching upward can trade speed for altitude, but it spends momentum instead of being refilled by forward input. Pitching downward past the shallow-dive dead zone can trade altitude for speed up to `Movement.MaxGlideSpeed`; with defaults, that is `15`, below the `15.75` fast-flight recharge threshold. The pitch-down speed gain is deliberately slow so short dip-and-pull-up loops lose altitude over time. Active boost is the only default path to the boosted max-speed band.

Sink rate scales with speed. At or above `Movement.StallSpeedThreshold`, neutral glide uses `Movement.GlideSinkSpeed`. As horizontal speed falls toward zero, sink blends toward `Movement.StallSinkSpeed`, so stalled or nearly stalled flight loses altitude much faster than a clean glide.

The default maneuver curve is tuned so a clean unboosted steep dive followed by a moderate pull-up can recover most, but not all, of the lost altitude. Controller regression tests keep that recovery in the roughly `60%` to `85%` band so strong flight lines feel rewarding without allowing infinite no-Vigour loops.

## HUD

The compact avatar-flight HUD appears above the hotbar while avatar flight is active. The upper label shows current pitch relative to flat, such as `-30°` while diving or `+30°` while climbing. The speed bar shows current horizontal speed relative to boosted max speed, and the thin red marker shows the target horizontal speed the controller is trending toward at the current pitch, airbrake, or boost state. The pips show Vigour charges, including partial recharge progress. The HUD keeps a transparent root and renders only the label, bar, marker, and charge pip backgrounds so it does not draw a modal panel or missing-texture backdrop over the hotbar.

## Config Fields

### Movement

- `MaxForwardSpeed`: normal forward cruise speed.
- `MaxGlideSpeed`: maximum horizontal speed reachable without an active boost.
- `NeutralGlideSpeed`: reference neutral cruise speed for glide metrics and climb eligibility; level forward glide can pass below it without spending Vigour.
- `NeutralGlideAcceleration`: low-speed acceleration toward the `GlideStartKickSpeed` floor.
- `NeutralGlideDeceleration`: speed decay toward the `GlideStartKickSpeed` floor.
- `GlideStartKickSpeed`: small forward speed seed applied when forward glide starts from hover or stall.
- `ForwardAcceleration`: legacy forward acceleration field; neutral level glide uses the neutral glide acceleration fields instead.
- `GlideSinkSpeed`: target downward speed for unpowered forward glide.
- `GlideSinkAcceleration`: rate at which glide approaches the sink speed.
- `StallSpeedThreshold`: horizontal speed where low-speed sink starts blending toward stall sink.
- `StallSinkSpeed`: target downward speed when forward glide is nearly stalled.

### Curve

- `DiveLoadRampSeconds`: seconds for dive pitch load to ramp in.
- `DiveLoadDecaySeconds`: seconds for dive pitch load to decay.
- `DivePitchExponent`: exponent applied to normalized downward pitch when gaining dive speed.
- `ClimbLoadRampSeconds`: seconds for climb pitch load to ramp in.
- `ClimbLoadDecaySeconds`: seconds for climb pitch load to decay.
- `ClimbPitchExponent`: exponent applied to normalized upward pitch when spending speed for lift.
- `ClimbSpeedEligibilityExponent`: exponent applied to speed eligibility for climb lift.
- `BoostedSpeedDecay`: speed decay after boosted flight when above `MaxGlideSpeed`.

### Boost

- `ForwardImpulse`: forward boost impulse.
- `CooldownSeconds`: seconds between boost activations.
- `DurationSeconds`: time a detected boost pulse remains active.
- `Directional`: when true, the boost follows look pitch instead of applying only horizontal speed.
- `UpwardPitchLiftMultiplier`: multiplier for upward directional boost lift.
- `UpwardPitchLiftCap`: maximum upward impulse from directional boost. Flaps remain the stronger raw vertical lift tool.

### Launch

- `Enabled`: enables charged launch.
- `PreferredInput`: primary launch input path. `CrouchHold` is the default built-in packet path because it is reliable and visually reads as a launch coil. `JumpHold` remains supported, and `ReinsPrimaryHold` is reserved for future/custom item hold integrations.
- `FallbackInput`: fallback launch input path. Defaults to `CrouchHold`; `ReinsPrimaryHold` is not wired by the default Flightmaster's Reins item.
- `MinChargeMs`: minimum hold before release applies launch.
- `MaxChargeMs`: hold duration that reaches full launch charge.
- `ChargeExponent`: exponent applied to normalized charge amount.
- `MinUpImpulse`: vertical impulse at minimum charge.
- `MaxUpImpulse`: vertical impulse at full charge.
- `MinForwardImpulse`: forward impulse at minimum charge.
- `MaxForwardImpulse`: forward impulse at full charge.
- `PartialChargeCost`: Vigour cost for a partial charged launch.
- `FullChargeCost`: Vigour cost for a full charged launch.
- `FullChargeCostThreshold`: normalized charge threshold that spends `FullChargeCost`.

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
