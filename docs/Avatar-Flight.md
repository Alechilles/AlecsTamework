# Avatar Flight

Avatar flight is the transformed-player flight path used by dragon-style mounts. The real player becomes the flight model, while Tamework can attach a visual rider copy for the seated player appearance.

## NPC Mounting

Avatar flight can start from an ordinary optimized `Mount` interaction. Set the NPC role parameters `MountMode` to `TameworkAvatarFlight` and `AvatarFlightConfig` to an enabled `TwAvatarFlightConfig` asset id. The normal mount entry still owns tame, owner, crouch, and mountable requirements, so no species-specific Java is needed.

During the ride, Tamework keeps the same source NPC entity and its ownership, name, needs, traits, health, inventory, and integration components. The NPC is placed in an inert role and hidden from interaction/tracking while the real player uses the configured transformed model. Dismount and forced recovery restore that same NPC instead of spawning a copy.

```json
"IsMountable": { "Value": true },
"MountMode": { "Value": "TameworkAvatarFlight" },
"AvatarFlightConfig": { "Value": "MyDragonAvatarFlight" }
```

The player and source NPC carry paired `TameworkAvatarFlightMountSession` and `TameworkAvatarFlightSource` components. Death, disconnect, world transfer, source removal, disabled/missing config, and orphan recovery use the same idempotent cleanup path. Cross-world mounted travel is not supported; transferring worlds ends the session. The paired entities stay at or below Hytale's maximum valid entity height, so flying into the build ceiling cannot remove the parked source NPC.

On a clean disconnect, Tamework queues that cleanup on the player's world thread before the entity is discarded. If the player is already gone after a client crash, the source-side watchdog detects the missing rider and restores the parked NPC. Persisted mount pairs also include a server-runtime epoch, so a pair saved before a server crash or restart is treated as stale instead of silently resuming in the next process. When that stale source belongs to an active command-roster summon, Tamework stores and despawns it through the roster transition when its owner next joins a world. Stale player sessions restore the ordinary player model from the saved skin when the process-local pre-mount model snapshot is no longer available. Source-missing cleanup returns the player to the latest recorded safe-ground position. Other exceptional cleanup restores the companion at its original mount position; the last-safe-ground option applies only to normal dismounts.

## Controls

- Forward movement starts or resumes glide.
- Mouse look controls heading and pitch.
- Holding crouch on the ground charges a launch. Releasing after the minimum charge starts avatar flight with upward and forward launch impulse.
- Grounded directional movement is locked while a launch is charging. Mouse look and charge release remain available.
- Avatar flight starts only from a charged launch release, left-click flap, or Q boost from Flightmaster's Talisman. Normal jumps, double jumps, and walking off short ledges remain native grounded/falling movement until the player explicitly enters flight with those controls.
- Left-click with Flightmaster's Talisman performs an upward flap. If avatar flight is not already active, the flap starts avatar flight first.
- Right-click with Flightmaster's Talisman applies the airbrake.
- Q with Flightmaster's Talisman performs a forward boost. If avatar flight is not already active, the boost starts avatar flight before applying the boost impulse.
- Flightmaster's Talisman also binds its native `Ability2` and `Ability3` item slots to optional configured combat roots. In Hytale's default layout those slots are E and R, respectively. They are slot names, not literal-key listeners: players who remap either native ability binding still activate the matching configured root.
- Flightmaster's Talisman must already be selected before mounting or enabling avatar flight. Activation fails before changing the player model when another hotbar item is held.
- While avatar flight is active, the lower-right ability HUD shows a complete custom row for crouch launch, forward boost, upward flap, airbrake, and any configured combat abilities. The row sits inside Hytale's right-side shortcut hints. Flightmaster's Talisman remains tool-only, active utility equipment is temporarily deselected, and hotbar selection stays locked to the talisman so weapon controls cannot replace or overlap the flight controls. The previous utility selection is restored on dismount. Combat glyph labels identify the default E/R layout; the native ability binding itself remains remappable.
- Crouch applies direct downward movement while airborne unless it began as a grounded launch charge.
- Entering liquid exits custom flight velocity and returns control to native swimming until the player leaves the liquid.
- Press F to immediately dismount from an NPC-backed avatar-flight session, including while airborne. An airborne normal dismount restores the companion at the current flight position. Grounded back + crouch remains an alternate hold-to-dismount input; the default hold is `750ms`, and back intent suppresses launch charging while the hold is active.
- Forward boost uses the configured boost input/action and spends Vigour. Q is the default reliable input path because airborne sprint is not consistently detectable.

While transformed but not actively using Tamework's custom flight velocity, grounded movement-state ownership stays with the base player client. Tamework still reads packet input for launch and talisman actions and suppresses unsafe item/action overlay animation slots, but it does not rewrite grounded walk/run/sprint movement state. When custom flight ends, Tamework sends one cleanup pass for flight-owned movement and pose animation overrides, then native grounded animation selection resumes.

## Vigour

Vigour is a charge resource for avatar-flight movement abilities. Successful charged launches, upward flaps, and forward boosts spend charges. When Vigour reaches zero, those movement abilities stop applying until charges recover.

Default balance:

- `MaxCharges`: 6.
- `UpwardFlapCost`: 1.
- `ForwardBoostCost`: 1.
- `GroundedRechargeSecondsPerCharge`: 4.
- `FastFlightRechargeSecondsPerCharge`: 8.
- `FastFlightRechargeSpeedRatio`: 0.80 of sustainable horizontal glide speed; this also selects `FastFlightAnimation`.
- `RechargeDelayAfterSpendSeconds`: 0.75.

Fast flight uses the sustainable horizontal cap, calculated as `max(Movement.MaxForwardSpeed, Movement.MaxGlideSpeed)`. With defaults, the threshold is `max(14, 15) * 0.80 = 12`. While horizontal speed remains at or above that threshold, Tamework keeps `FastFlightAnimation` active and applies airborne fast-flight Vigour recharge. Ordinary forward cruise at `14` qualifies, while slow or stalled flight does not.

The recharge delay means a spent charge requires the delay plus the recharge cadence. With defaults, one airborne fast-flight charge after spending requires `0.75 + 8 = 8.75` seconds of continuous qualifying speed.

## Companion Flight Progression

Avatar flight can award ordinary companion XP to the parked source companion through `CompanionXpSource.AVATAR_FLIGHT`. Configure it on the source role's `TwLevelingConfig.XpSources.Flight` object:

```json
"XpSources": {
  "Flight": {
    "Enabled": true,
    "XpPerQualifiedSecond": 0.15,
    "AwardIntervalSeconds": 10.0,
    "MaxXpPerMinute": 9.0
  }
}
```

All four fields are required for awards to occur. The default is inert: `Enabled` is false, the XP rate and cap are zero, and the default batch interval is `10.0` seconds. Qualified time is output time, not distance travelled, movement input, or idle time: the avatar must be applying custom flight velocity and meet the configured fast-flight speed threshold. Tamework samples no more than `0.25` qualified seconds per server tick and batches awards every 10 seconds with the example above. The first award starts a source-anchored 60-second accounting window; the example permits up to `9 XP` in that window, and the allowance resets when the window expires.

The player never receives this XP. Tamework validates the active player/source session, runtime epoch, rider UUID, source world, and reverse rider link before resolving the original parked source companion and its role-specific leveling config. A stale, missing, or otherwise invalid session is a safe no-award path.

Purchased companion talents can also tune the active avatar flight. These effect keys resolve from the same valid parked source companion; their neutral value is `1.0` and invalid sessions use neutral tuning:

- `AvatarFlightVigourCapacityMultiplier`: increases maximum Vigour capacity; runtime range `1.0..1.35`.
- `AvatarFlightVigourRechargeRateMultiplier`: increases Vigour recharge rate; runtime range `1.0..1.35`.
- `AvatarFlightForwardBoostCostMultiplier`: reduces forward-boost Vigour cost; runtime range `0.70..1.0`.
- `AvatarFlightForwardBoostImpulseMultiplier`: increases forward-boost impulse; runtime range `1.0..1.25`.
- `AvatarFlightGlideSinkMultiplier`: reduces passive glide sink; runtime range `0.70..1.0`.
- `AvatarFlightClimbLiftMultiplier`: increases climb lift; runtime range `1.0..1.25`.

## Launch

Charged launch is the default takeoff path for avatar flight. With `Launch.PreferredInput` set to `CrouchHold`, holding crouch while grounded starts charging instead of feeding crouch into descent. Releasing before `Launch.MinChargeMs` cancels the launch, while releasing after the minimum applies a charge-scaled upward and forward impulse. If the player leaves the ground during the hold, the release can still apply the launch that began on the ground. Airborne crouch without an active grounded launch charge remains direct downward movement.

While the avatar remains grounded during that charge, Tamework temporarily locks native directional movement so the player cannot scoot away from the charge position. Mouse look and charge release are unaffected, and Tamework does not cancel knockback or other physics-driven motion.

Default charge timing is `500ms` to `3000ms` with a `0.65` exponent. That front-loads some launch strength after the minimum hold, while still rewarding longer charge time. Partial launches cost `1` Vigour by default, and launches at or above `Launch.FullChargeCostThreshold` cost `2`.

Launch presentation uses short world-space particle and positional audio cues. Grounded charging emits increasingly frequent inward pressure pulses paired with varied sampled wind gusts whose volume and pitch rise with charge progress. Reaching full charge plays one ready cue. A release below the minimum or a rejected release emits a small visual and audio fizzle. Successful launches emit partial, mid, or full release effects selected from the configured launch curve. Particle release stays at the last grounded charge origin if the avatar leaves the ground before releasing, while audio follows the avatar's current release position.

## Trails

Avatar-flight trails use Hytale's dedicated model-trail system, not particle systems. A species profile supplies RootInteraction assets whose `Effects.Trails` entries attach trail assets to model nodes such as wingtip or outer-wing joints. Tamework reads those trail definitions and synchronizes them onto the transformed player model only while their movement condition is active. Launch, flap, and boost trails remain for the authored interaction runtime; fast-glide trails remain until speed falls below their stop threshold. Replacing the model state at each transition gives both one-shot and sustained trails an explicit cleanup path.

Fast glide starts at `Trails.FastGlideStartSpeedRatio * Movement.MaxGlideSpeed` and stops at the lower `Trails.FastGlideStopSpeedRatio` threshold. The default `0.92`/`0.86` pair avoids flicker when speed hovers near the boundary. With the default maximum glide speed of `15`, those thresholds are `13.8` and `12.9`.

## Glide Balance

Unpowered forward glide has a passive sink so zero-Vigour flight eventually needs landing. Level forward glide does not act like a motor: forward input can seed movement from hover or stall, but flat unpowered flight decays gradually toward the `Movement.GlideStartKickSpeed` floor instead of preserving `Movement.NeutralGlideSpeed` forever. Very shallow downward pitch, currently less than about `8°`, is treated like neutral glide for speed and sink so tiny dive angles cannot preserve max glide speed or carry leftover flap lift indefinitely. This neutral/shallow speed bleed is intentionally gentle, with default `Movement.NeutralGlideDeceleration` set to `0.15`, so it preserves more horizontal momentum than a shallow climb while still losing altitude through glide sink. Pitching upward can trade speed for altitude, but it spends momentum instead of being refilled by forward input. Pitching downward past the shallow-dive dead zone can trade altitude for speed up to `Movement.MaxGlideSpeed`; with defaults, sustained speed at or above `12` enters fast flight and recharges Vigour. The pitch-down speed gain is deliberately slow so short dip-and-pull-up loops lose altitude over time. Active boost remains the only default path above the sustainable glide cap.

Sink rate scales with speed. At or above `Movement.StallSpeedThreshold`, neutral glide uses `Movement.GlideSinkSpeed`. As horizontal speed falls toward zero, sink blends toward `Movement.StallSinkSpeed`, so stalled or nearly stalled flight loses altitude much faster than a clean glide.

The default maneuver curve is tuned so a clean unboosted steep dive followed by a moderate pull-up can recover most, but not all, of the lost altitude. Controller regression tests keep that recovery in the roughly `60%` to `85%` band so strong flight lines feel rewarding without allowing infinite no-Vigour loops.

## HUD

The compact avatar-flight HUD appears above the hotbar while avatar flight is active. The upper label shows current pitch relative to flat, such as `-30°` while diving or `+30°` while climbing. The speed bar shows current horizontal speed relative to boosted max speed, and the thin red marker shows the target horizontal speed the controller is trending toward at the current pitch, airbrake, or boost state. The pips show Vigour charges, including partial recharge progress. The HUD keeps a transparent root and renders only the label, bar, marker, and charge pip backgrounds so it does not draw a modal panel or missing-texture backdrop over the hotbar.

While grounded and holding the charged launch input, the compact avatar-flight HUD shows an amber launch-charge bar above the pitch readout. The bar fills from `0%` at hold start to `100%` at `Launch.MaxChargeMs`, and a small marker shows the minimum valid release threshold from `Launch.MinChargeMs`. The launch bar hides as soon as the charge is released, cancelled, or the player is airborne.

## Debugging

Use `/tw debugdragonflight inputprobe on` or `/tw debugplayerinput on` for input logs without changing player movement capability. `/tw debugdragonflight flightprobe on` is a separate client-flight capability probe and can change native movement states, so it should not be used for normal launch tuning unless client flight behavior is the thing being tested.

Avatar-flight logs use `TameworkAvatarFlight debug` only when the active `TwDebugConfig.DebugCommands.AvatarFlight` toggle and the avatar-flight asset's `Debug.LogControllerTicks` setting are enabled. The global toggle is disabled by default. These lines include controller input/output, raw packet sprint and input staleness, movement-state flags, client flying sync, forced movement/pose animation IDs, visual-override ownership, VFX emission, rider attachment state, and overlay-suppression state. For grounded sprint or stuck-animation issues, enable the global avatar-flight debug toggle and capture both `TameworkInput debug` and `TameworkAvatarFlight debug` lines from the same reproduction.

Normal avatar flight does not enable the client's native `canFly` movement setting. The standalone `/tw debugdragonflight flightprobe` command remains available when client flight behavior itself needs investigation, but default avatar-flight activation avoids the native double-jump flight path entirely.

## Fake Rider Model Variants

When `RiderVisual.ShowRider` is enabled, the fake rider is attached to the transformed model. If the transformed model's animation tracks use player-like node names such as `Origin`, `Pelvis`, `Chest`, `Head`, `L-Arm`, or `R-Arm`, those animations can also affect the fake rider and its equipment. Use the AvatarFlight namespace generator to create a model variant whose rider-colliding rig nodes are prefixed while `MountAnchor` remains unchanged. The transformed model's root becomes `AF_Origin`, and Tamework's injected pitch/bank clips target that dedicated root so the rider can retain the standard player skeleton required by skin, cosmetic, and armor attachments.

Tamework attaches the reconstructed rider in the same model update as the transformed dragon. Skin, cosmetic, and equipment attachments are included by default, and equipment changes refresh the rider while flight remains active.

`RiderVisual.IncludeAppearanceAttachments` controls whether the rider receives those reconstructed skin, cosmetic, and equipment model attachments in addition to its body model. It defaults to `true`; set it to `false` only when an integration intentionally needs a body-only rider.

```powershell
python scripts/tools/avatarflight_namespace_assets.py --mod-root "C:\Path\To\Mod" --model-id MyDragon
```

The script creates a `<ModelId>_AvatarFlight` server model, a copied `.blockymodel`, and copied `.blockyanim` files with matching node names. By default it renames `Origin` to the fixed `AF_Origin` pose root and prefixes other nodes that collide with Tamework's fake-rider/player rig. Pass `--rename-mode all` only when a fully namespaced model is intentionally needed. The `--prefix` option does not change `AF_Origin`, because Tamework's standard pose clips use that stable name. The script rewrites animation paths in the generated server model, but leaves server-model enum fields such as camera targets unchanged. Use the generated model id from `Model.ModelId` in the AvatarFlight config.

The generator warns when player-style locomotion sets that the native transformed-player client can request while grounded are missing: `Sprint`, `JumpSprint`, and `StepSprint`. Keep real asset-level keys for these aliases in static avatar models, usually by mapping them to the model's `Run`, `JumpRun`, and `StepRun` sets when no dedicated sprint variants exist. The generator does not add them automatically because animation choice belongs to the model author. Runtime animation injection adds only forced flight pose clips and preserves every model-authored grounded locomotion set unchanged; native grounded walk/run/sprint selection can consult those declared animation set ids before Tamework sends any custom movement animation.

Do not define nonempty `FootstepIntervals` arrays on transformed-player model animations. The current client retains its footstep interval index across model and movement-animation swaps without checking it against the new array length, so changing from an ordinary player animation to a shorter AvatarFlight interval array can crash the client on the next grounded step. The namespace generator strips these arrays from generated model variants and reports each correction. Use the AvatarFlight audio fields for timed wing sounds instead of model-animation footsteps.

## Config Fields

### Model Camera

- `Model.CameraPositionOffset`: optional runtime `X`/`Y`/`Z` camera offset. Omit it to use the transformed ModelAsset's camera offset. This is the primary per-config third-person framing control.
- `Model.EyeHeight`: optional runtime eye-height override. It positions the first-person viewpoint vertically relative to the transformed player's entity root and also affects other engine eye-height consumers.

The camera override preserves the ModelAsset's existing yaw and pitch target settings. The current client camera contract cannot anchor directly to an arbitrary model-attachment node such as the fake rider's `Head`, so `EyeHeight` provides the closest stable first-person alignment.

### Grounded Movement

- `Movement.GroundedMoveSpeed`: native player base speed used while the transformed avatar is genuinely grounded. It defaults to `8.0`, matching the base game's `Mount` movement configuration. Tamework restores the player's previous base speed while airborne, swimming, and when avatar flight ends.

### Mounting

- `DismountHoldMs`: grounded back+crouch hold duration required for voluntary dismount.
- `RequireGroundedDismount`: blocks the back+crouch hold gesture while airborne when true. The immediate F-key dismount remains available.
- `RestoreNpcAtLastSafeGround`: restores the source at the most recent grounded avatar transform for a grounded normal dismount. Airborne normal dismount restores at the current flight position; exceptional cleanup uses the original mount origin.
- `PlayerDismountOffset`: distance used to place the player behind the restored NPC.

Omitting `Mounting` inherits the complete parent section. An explicit `Mounting` object overrides only its explicit nested keys and inherits the remaining values.

### Animation

- `IdleAnimation`: movement-slot animation used while hovering or horizontally idle.
- `FlightAnimation`: movement-slot animation used during ordinary forward flight.
- `FastFlightAnimation`: movement-slot animation used while forward boost speed is active.
- `ResendIntervalMs`: interval for defensively resending an unchanged movement animation. Set this to `0` for models that attach a looping `SoundEventId` to flight animations; repeated play packets restart those sound events before the model-authored timing can complete.

Omitting `Animation` inherits the complete parent section. An explicit `Animation` object overrides only its explicit nested keys and inherits the remaining values.

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

### Combat Abilities

`CombatAbilities` configures optional item abilities for a transformed player. It is a top-level map whose only runtime slot keys are `Ability2` and `Ability3`:

```json
"CombatAbilities": {
  "Ability2": {
    "RootInteraction": "Root_NPC_NordicDrake_Avatar_Fire_Ball",
    "Glyph": "FIRE",
    "GlyphTexturePath": "MyDragon/AvatarFlightIcons/Fireball.png",
    "CooldownSeconds": 15.0
  },
  "Ability3": {
    "RootInteraction": "Root_NPC_NordicDrake_Avatar_Flame_Breath",
    "Glyph": "BREATH"
  }
}
```

- `RootInteraction`: ID of the downstream root interaction. Omit it or set it blank to disable that slot.
- `Glyph`: optional fallback text when no custom glyph texture is configured. A configured root with both `Glyph` and `GlyphTexturePath` blank still runs, but its HUD control is hidden.
- `GlyphTexturePath`: optional custom UI texture path for the glyph, relative to the mod's `Common/UI/Custom` directory. It replaces Tamework's built-in artwork for that ability while keeping the standard avatar-flight frame and layout. Leave it blank to use a built-in texture for known glyphs or the `Glyph` text fallback.
- `CooldownSeconds`: optional real-time cooldown for this slot. It defaults to `0`; each transformed player's Ability2 and Ability3 timers are independent and begin only after the server accepts the configured root. During cooldown, the matching HUD control is dimmed and shows a whole-second countdown; it refreshes at least once per second regardless of the regular HUD resend setting.

The Flightmaster's Talisman maps its native `Ability2` and `Ability3` item interactions to this map. E and R are the default Hytale bindings for those slots, not hard-coded input keys. A player can remap native ability bindings and the same slot still resolves; custom roots must not depend on literal E/R input events.

`CombatAbilities` has whole-map inheritance. If a child omits the property, it inherits the complete parent map. If a child supplies the property, including `{}`, it replaces the complete parent map; entries are not merged per slot. To keep one inherited ability while changing the other, repeat the retained entry in the child map. An explicit blank `RootInteraction` disables that entry in the replacement map.

Combat roots execute in the transformed player's interaction context. Author every downstream root to be player-safe: it must resolve aim from the player/look direction and cannot require an NPC entity, NPC marked-target state, or other NPC-only components. This keeps transformed player combat separate from native NPC attacks.

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
- `PreferredInput`: primary launch input path. `CrouchHold` is the default built-in packet path because it is reliable and visually reads as a launch coil. `JumpHold` remains supported, and the legacy `ReinsPrimaryHold` config identifier is reserved for future/custom item hold integrations.
- `FallbackInput`: fallback launch input path. Defaults to `CrouchHold`; `ReinsPrimaryHold` is not wired by the default Flightmaster's Talisman item.
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
- `FastFlightRechargeSpeedRatio`: ratio of sustainable horizontal glide speed that activates both `FastFlightAnimation` and airborne fast-flight recharge.
- `RechargeDelayAfterSpendSeconds`: delay before recharge resumes after spending.
- `HudEnabled`: shows the compact speed and Vigour HUD.
- `HudResendIntervalMs`: minimum delay between changed HUD refreshes. Unchanged HUD state is not resent.

### Trails

- `Enabled`: enables interaction-authored model trails without changing movement behavior.
- `LaunchRootInteraction`: one-shot root started after a successful charged launch; blank disables this cue.
- `FlapRootInteraction`: one-shot root started after a successful upward flap; blank disables this cue.
- `BoostRootInteraction`: one-shot root started after a successful forward boost; blank disables this cue.
- `FastGlideRootInteraction`: long-running root started while horizontal speed is near the unboosted maximum glide speed.
- `FastGlideStartSpeedRatio`: ratio of `Movement.MaxGlideSpeed` that starts fast-glide trails.
- `FastGlideStopSpeedRatio`: lower ratio that stops fast-glide trails and supplies threshold hysteresis.

Omitting `Trails` inherits the complete parent section. An explicit `Trails` object overrides only its explicit nested keys and inherits the remaining values. Explicitly blank root IDs disable only those inherited cues. Trail assets, textures, attachment nodes, offsets, colors, widths, lifetimes, and render modes remain species-owned assets.

### Vfx

- `Enabled`: enables avatar-flight particle presentation without changing movement behavior.
- `GroundOffsetY`: vertical offset from the avatar foot position for launch effects.
- `MaxDurationSeconds`: defensive client-side lifetime cap for each spawned system.
- `ForwardBoostEnabled`, `ForwardBoostParticleSystem`, `ForwardBoostScale`: successful forward-boost burst and whole-system scale.
- `UpwardBoostEnabled`, `UpwardBoostParticleSystem`, `UpwardBoostScale`: successful upward-flap burst and whole-system scale.
- `LaunchChargeEnabled`: enables grounded charge pulses.
- `LaunchChargeParticleSystem`: system ID for each charge pulse.
- `LaunchChargeEarlyIntervalMs` / `LaunchChargeFullIntervalMs`: pulse cadence at the start and end of charging.
- `LaunchChargeMinScale` / `LaunchChargeMaxScale`: pulse scale at the start and end of charging.
- `LaunchCancelEnabled`, `LaunchCancelParticleSystem`, `LaunchCancelScale`: canceled or rejected release effect.
- `LaunchReleaseEnabled`: enables successful release effects.
- `LaunchReleasePartialParticleSystem`, `LaunchReleaseMidParticleSystem`, `LaunchReleaseFullParticleSystem`: release systems by launch-curve tier.
- `LaunchReleasePartialScale`, `LaunchReleaseMidScale`, `LaunchReleaseFullScale`: whole-system scale by tier.
- `LaunchReleaseMidThreshold` / `LaunchReleaseFullThreshold`: launch-curve charge boundaries for mid and full release effects.

Boost particles fire only when the movement controller accepts the corresponding impulse, so cooldown or insufficient-Vigour rejection remains visually silent. A boost scale of `0` follows `LaunchReleaseMidScale`; existing species profiles therefore retain their established avatar-relative sizing unless they explicitly override the boost scale.

Omitting `Vfx` inherits the complete parent section. An explicit `Vfx` object overrides only its explicit nested keys and inherits the remaining values.

### Audio

- `Enabled`: enables positional avatar-flight audio without changing movement behavior.
- `LaunchChargeSoundEvent`: short wind cue emitted repeatedly while grounded charge builds. Blank disables charge pulses.
- `LaunchChargeEarlyIntervalMs` / `LaunchChargeFullIntervalMs`: sound-pulse cadence at the start and end of charging.
- `LaunchChargeMinVolume` / `LaunchChargeMaxVolume`: linear volume modifiers interpolated across charge progress.
- `LaunchChargeMinPitch` / `LaunchChargeMaxPitch`: linear pitch modifiers interpolated across charge progress.
- `LaunchReadySoundEvent`: one-shot cue emitted once when charge first reaches `Launch.MaxChargeMs`. Blank disables it.
- `LaunchCancelSoundEvent`: one-shot cue for a canceled or rejected release. Blank disables it.
- `LaunchReleasePartialSoundEvent`, `LaunchReleaseMidSoundEvent`, `LaunchReleaseFullSoundEvent`: one-shot release cues selected with the same launch-curve tiers as release VFX. Blank disables an individual tier.
- `UpwardFlapSoundEvent`: one-shot wing displacement cue after an accepted upward flap. Blank disables it.
- `ForwardBoostSoundEvent`: one-shot accelerating wind burst after an accepted forward boost. Blank disables it.
- `AirbrakeSoundEvent`: one-shot wing-displacement cue when an accepted airbrake press first becomes active. Holding airbrake does not replay it; the default reuses `UpwardFlapSoundEvent`'s sound asset.
- `IdleFlightFlapSoundEvent` / `IdleFlightFlapIntervalMs`: one-shot wing cue and cadence while hovering. Blank or zero disables it.
- `FlightFlapSoundEvent` / `FlightFlapIntervalMs`: one-shot wing cue and cadence during normal forward flight. Blank or zero disables it; fast flight is excluded.

Omitting `Audio` inherits the complete parent section. An explicit `Audio` object overrides only its explicit nested keys and inherits the remaining values. Avatar-flight sounds are positional mono events routed through Hytale's dragon NPC audio category.
