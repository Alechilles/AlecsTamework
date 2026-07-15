# Avatar Flight

Avatar flight is the transformed-player flight path used by dragon-style mounts. The real player becomes the flight model, while Tamework can attach a visual rider copy for the seated player appearance.

## Controls

- Forward movement starts or resumes glide.
- Mouse look controls heading and pitch.
- Holding crouch on the ground charges a launch. Releasing after the minimum charge starts avatar flight with upward and forward launch impulse.
- Avatar flight starts only from a charged launch release, left-click flap, or Q boost from Flightmaster's Reins. Normal jumps, double jumps, and walking off short ledges remain native grounded/falling movement until the player explicitly enters flight with those controls.
- Left-click with Flightmaster's Reins performs an upward flap. If avatar flight is not already active, the flap starts avatar flight first.
- Right-click with Flightmaster's Reins applies the airbrake.
- Q with Flightmaster's Reins performs a forward boost. If avatar flight is not already active, the boost starts avatar flight before applying the boost impulse.
- Crouch applies direct downward movement while airborne unless it began as a grounded launch charge.
- Forward boost uses the configured boost input/action and spends Vigour. Q is the default reliable input path because airborne sprint is not consistently detectable.

While transformed but not actively using Tamework's custom flight velocity, grounded movement-state ownership stays with the base player client. Tamework still reads packet input for launch and Reins actions and suppresses unsafe item/action overlay animation slots, but it does not rewrite grounded walk/run/sprint movement state. When custom flight ends, Tamework sends one cleanup pass for flight-owned movement and pose animation overrides, then native grounded animation selection resumes.

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

Launch presentation uses short world-space particle and positional audio cues. Grounded charging emits increasingly frequent inward pressure pulses paired with varied sampled wind gusts whose volume and pitch rise with charge progress. Reaching full charge plays one ready cue. A release below the minimum or a rejected release emits a small visual and audio fizzle. Successful launches emit partial, mid, or full release effects selected from the configured launch curve. Particle release stays at the last grounded charge origin if the avatar leaves the ground before releasing, while audio follows the avatar's current release position.

## Glide Balance

Unpowered forward glide has a passive sink so zero-Vigour flight eventually needs landing. Level forward glide does not act like a motor: forward input can seed movement from hover or stall, but flat unpowered flight decays gradually toward the `Movement.GlideStartKickSpeed` floor instead of preserving `Movement.NeutralGlideSpeed` forever. Very shallow downward pitch, currently less than about `8°`, is treated like neutral glide for speed and sink so tiny dive angles cannot preserve max glide speed or carry leftover flap lift indefinitely. This neutral/shallow speed bleed is intentionally gentle, with default `Movement.NeutralGlideDeceleration` set to `0.15`, so it preserves more horizontal momentum than a shallow climb while still losing altitude through glide sink. Higher speeds require sustained diving or spending Vigour. Pitching upward can trade speed for altitude, but it spends momentum instead of being refilled by forward input. Pitching downward past the shallow-dive dead zone can trade altitude for speed up to `Movement.MaxGlideSpeed`; with defaults, that is `15`, below the `15.75` fast-flight recharge threshold. The pitch-down speed gain is deliberately slow so short dip-and-pull-up loops lose altitude over time. Active boost is the only default path to the boosted max-speed band.

Sink rate scales with speed. At or above `Movement.StallSpeedThreshold`, neutral glide uses `Movement.GlideSinkSpeed`. As horizontal speed falls toward zero, sink blends toward `Movement.StallSinkSpeed`, so stalled or nearly stalled flight loses altitude much faster than a clean glide.

The default maneuver curve is tuned so a clean unboosted steep dive followed by a moderate pull-up can recover most, but not all, of the lost altitude. Controller regression tests keep that recovery in the roughly `60%` to `85%` band so strong flight lines feel rewarding without allowing infinite no-Vigour loops.

## HUD

The compact avatar-flight HUD appears above the hotbar while avatar flight is active. The upper label shows current pitch relative to flat, such as `-30°` while diving or `+30°` while climbing. The speed bar shows current horizontal speed relative to boosted max speed, and the thin red marker shows the target horizontal speed the controller is trending toward at the current pitch, airbrake, or boost state. The pips show Vigour charges, including partial recharge progress. The HUD keeps a transparent root and renders only the label, bar, marker, and charge pip backgrounds so it does not draw a modal panel or missing-texture backdrop over the hotbar.

While grounded and holding the charged launch input, the compact avatar-flight HUD shows an amber launch-charge bar above the pitch readout. The bar fills from `0%` at hold start to `100%` at `Launch.MaxChargeMs`, and a small marker shows the minimum valid release threshold from `Launch.MinChargeMs`. The launch bar hides as soon as the charge is released, cancelled, or the player is airborne.

## Debugging

Use `/tw debugdragonflight inputprobe on` or `/tw debugplayerinput on` for input logs without changing player movement capability. `/tw debugdragonflight flightprobe on` is a separate client-flight capability probe and can change native movement states, so it should not be used for normal launch tuning unless client flight behavior is the thing being tested.

Avatar-flight controller logs use `TameworkAvatarFlight debug` when `Debug.LogControllerTicks` is enabled. These lines include controller input/output, raw packet sprint and input staleness, movement-state flags, client flying sync, forced movement/pose animation IDs, visual-override ownership, and overlay-suppression state. For grounded sprint or stuck-animation issues, capture both `TameworkInput debug` and `TameworkAvatarFlight debug` lines from the same reproduction.

Normal avatar flight does not enable the client's native `canFly` movement setting. The standalone `/tw debugdragonflight flightprobe` command remains available when client flight behavior itself needs investigation, but default avatar-flight activation avoids the native double-jump flight path entirely.

## Fake Rider Model Variants

When `RiderVisual.ShowRider` is enabled, the fake rider is attached to the transformed model. If the transformed model's animation tracks use player-like node names such as `Pelvis`, `Chest`, `Head`, `L-Arm`, or `R-Arm`, those animations can also affect the fake rider. Use the AvatarFlight namespace generator to create a model variant whose rider-colliding rig nodes are prefixed while unrelated nodes such as mount anchors are left unchanged. `Origin` remains unchanged for Tamework's injected pitch/bank poses:

```powershell
python scripts/tools/avatarflight_namespace_assets.py --mod-root "C:\Path\To\Mod" --model-id MyDragon
```

The script creates a `<ModelId>_AvatarFlight` server model, a copied `.blockymodel`, and copied `.blockyanim` files with matching node names. By default it only renames nodes that collide with Tamework's fake-rider/player rig. Pass `--rename-mode all` only when a fully namespaced model is intentionally needed. It rewrites animation paths in the generated server model, but leaves server-model enum fields such as camera targets unchanged. Use the generated model id from `Model.ModelId` in the AvatarFlight config.

The generator warns when player-style locomotion sets that the native transformed-player client can request while grounded are missing: `Sprint`, `JumpSprint`, and `StepSprint`. Keep real asset-level keys for these aliases in static avatar models, usually by mapping them to the model's `Run`, `JumpRun`, and `StepRun` sets when no dedicated sprint variants exist. The generator does not add them automatically because animation choice belongs to the model author. Runtime animation injection adds only forced flight pose clips and preserves every model-authored grounded locomotion set unchanged; native grounded walk/run/sprint selection can consult those declared animation set ids before Tamework sends any custom movement animation.

## Config Fields

### Input

- `IntentTimeoutMs`: milliseconds before packet-derived movement intent decays to neutral.
- `ForwardDeadzone`: absolute forward-axis threshold for W/S intent.
- `StrafeDeadzone`: absolute strafe-axis threshold for future A/D tuning.
- `AirborneJumpActivationDelayMs`: legacy input setting retained for config compatibility. Default avatar flight no longer uses jump or double-jump as an entry path.

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

### Ability Animation

- `Enabled`: enables configured one-shot ability animations without changing movement behavior.
- `Slot`: slot used for all configured ability cues. `Action` is the compatibility default and preserves the ordinary movement clip. `Movement` temporarily replaces that clip, allowing a Status-slot pitch/bank pose to remain layered over a full-body ability animation.
- `UpwardBoostAnimation`: animation-set ID played after a successful upward flap. Blank disables this cue.
- `UpwardBoostDurationMs`: time the configured slot remains reserved for the upward-flap cue.
- `ForwardBoostAnimation`: animation-set ID played after a successful forward boost. Blank disables this cue.
- `ForwardBoostDurationMs`: time the configured slot remains reserved for the forward-boost cue.
- `AirbrakeAnimation`: animation-set ID played once when an accepted airbrake press becomes active. Holding the brake does not replay it.
- `AirbrakeDurationMs`: time the configured slot remains reserved for the airbrake cue.

Ability animations are presentation hooks, not ability inputs. Cooldown rejection, insufficient Vigour, and inactive airbrake state do not play a cue. Tamework validates each nonblank ID against the transformed model before sending the configured-slot animation; a missing animation warns once and leaves flight behavior unchanged. When `Movement` is selected, normal movement animation resumes after the cue duration. Tamework does not inject or ship generic ability clips, so each transformed model can supply animations suited to its own rig.

Omitting `AbilityAnimation` inherits the complete parent section. An explicit `AbilityAnimation` object overrides only its explicit nested keys and inherits the remaining values. An explicitly blank animation ID disables only that inherited cue.

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

### Vfx

- `Enabled`: enables avatar-flight particle presentation without changing movement behavior.
- `GroundOffsetY`: vertical offset from the avatar foot position for launch effects.
- `MaxDurationSeconds`: defensive client-side lifetime cap for each spawned system.
- `LaunchChargeEnabled`: enables grounded charge pulses.
- `LaunchChargeParticleSystem`: system ID for each charge pulse.
- `LaunchChargeEarlyIntervalMs` / `LaunchChargeFullIntervalMs`: pulse cadence at the start and end of charging.
- `LaunchChargeMinScale` / `LaunchChargeMaxScale`: pulse scale at the start and end of charging.
- `LaunchCancelEnabled`, `LaunchCancelParticleSystem`, `LaunchCancelScale`: canceled or rejected release effect.
- `LaunchReleaseEnabled`: enables successful release effects.
- `LaunchReleasePartialParticleSystem`, `LaunchReleaseMidParticleSystem`, `LaunchReleaseFullParticleSystem`: release systems by launch-curve tier.
- `LaunchReleasePartialScale`, `LaunchReleaseMidScale`, `LaunchReleaseFullScale`: whole-system scale by tier.
- `LaunchReleaseMidThreshold` / `LaunchReleaseFullThreshold`: launch-curve charge boundaries for mid and full release effects.

Omitting `Vfx` inherits the complete parent section. An explicit `Vfx` object overrides only its explicit nested keys and inherits the remaining values.

### Audio

- `Enabled`: enables positional launch audio without changing movement behavior.
- `LaunchChargeSoundEvent`: short wind cue emitted repeatedly while grounded charge builds. Blank disables charge pulses.
- `LaunchChargeEarlyIntervalMs` / `LaunchChargeFullIntervalMs`: sound-pulse cadence at the start and end of charging.
- `LaunchChargeMinVolume` / `LaunchChargeMaxVolume`: linear volume modifiers interpolated across charge progress.
- `LaunchChargeMinPitch` / `LaunchChargeMaxPitch`: linear pitch modifiers interpolated across charge progress.
- `LaunchReadySoundEvent`: one-shot cue emitted once when charge first reaches `Launch.MaxChargeMs`. Blank disables it.
- `LaunchCancelSoundEvent`: one-shot cue for a canceled or rejected release. Blank disables it.
- `LaunchReleasePartialSoundEvent`, `LaunchReleaseMidSoundEvent`, `LaunchReleaseFullSoundEvent`: one-shot release cues selected with the same launch-curve tiers as release VFX. Blank disables an individual tier.

Omitting `Audio` inherits the complete parent section. An explicit `Audio` object overrides only its explicit nested keys and inherits the remaining values. Launch sounds are positional mono events routed through Hytale's dragon NPC audio category.
