# Avatar Flight Complete Design

## Purpose

This document consolidates the full avatar-flight brainstorming session into one reviewable spec. It covers the abandoned native-mounted controller path, the selected transformed-avatar architecture, controls, visual rider requirements, pitch and bank presentation, Vigour, HUD, glide balance, directional boost, charged launch, and the implementation risks we identified.

The target feel is dragonriding-inspired momentum flight: the player can dive to build speed, pitch up to trade speed for altitude, spend Vigour for meaningful movement abilities, and eventually needs to land or fly well enough to recover resources. The system should be generic enough for other Tamework flying forms, with HyDragon NordicDrake as the first full test target.

## Scope

- Use transformed-player avatar flight as the primary flight system for NordicDrake-style mounts.
- Keep behavior configurable through `TwAvatarFlightConfig` or focused companion config sections.
- Use Flightmaster's Talisman as the reliable held-item control surface.
- Keep flight movement, Vigour spending, animation state, HUD state, rider visuals, and owner equipment hiding coherent as one player-facing feature.
- Preserve the existing working avatar-flight direction and specify the next tuning and launch work.

## Non-Goals

- Do not revive the old native-mounted glide controller as the main path for this feature.
- Do not depend on native creative flight mode.
- Do not require every modder to hand-author pitch and bank animation sets for every model.
- Do not add NordicDrake attack abilities in the next implementation pass.
- Do not make unpowered glide sustainable forever.
- Do not turn the HUD into a cockpit or large center-screen panel.

## Design History And Settled Direction

The first attempted direction used a native mount attachment and custom mounted movement. It could attach the player to the dragon in some cases, but rider input and server/client motion diverged badly. The player often only rotated, movement was delayed or applied after dismount, native mounted state caused crashes in some variants, and airborne jump/sprint signals were unreliable. That path is considered unsuitable as the foundation for this feature.

The selected direction is transformed-avatar flight:

- the real player is temporarily transformed into the flight model;
- normal player movement and mouse look remain available enough to build custom flight;
- the visible mount body is the player's transformed model;
- a fake rider visual is attached to the mount so the player still appears to be riding;
- Flightmaster's Talisman provide reliable item-action controls for abilities that raw movement input does not expose cleanly.

This is the clean foundation for Tamework's dragon-style flight. The native mount system can still matter for other features, but it should not drive this flight mode unless a future Hytale update exposes better mounted input and motion hooks.

## Architecture

Avatar flight should remain a small set of focused services rather than one large controller:

- Activation service: starts and stops avatar flight, transforms the player model, installs components, and restores state.
- Input capture: records movement intent, mouse heading, Reins actions, and any future ability slot actions.
- Movement controller: owns actual velocity, mode transitions, cooldowns, Vigour spending, and glide math.
- Vigour service: owns charge spend and recharge state.
- HUD service: builds a small view model and updates the custom HUD.
- Owner equipment visual service: hides real-player held item, hands, armor, and equipment while transformed.
- Rider visual service: attaches the fake seated player model to the transformed mount and mirrors appearance/equipment.
- Animation presentation service: applies flight, hover, pitch, and bank presentation without leaking player item-hold animations onto the mount.

Input handlers should queue intents. The movement controller should decide whether those intents are legal, spend Vigour, and apply motion. This keeps item actions, movement packets, and future ability slots from hardcoding flight behavior.

## Base Game And Source Findings

Hytale Workshop source checks were run against indexed release `0.5.6`.

Relevant input evidence:

- `PlayerSystems.ProcessPlayerInput#tick` iterates `PlayerInput.getMovementUpdateQueue()`, applies each input update, and clears the queue.
- `PlayerInput.SetMovementStates#apply` writes movement states into `MovementStatesComponent`.
- `KnockbackPredictionSystems.CaptureKnockbackInput#tick` demonstrates that a system can inspect and remove selected `PlayerInput.InputUpdate` entries before normal processing.
- `SimpleInteraction#tick0` receives `firstRun` and `time`, so custom item interactions can potentially distinguish initial press from held duration if the interaction remains active.

Repo-side evidence:

- `PlayerInputDebugProbe` can log `jumping`, `crouching`, and mouse interaction packet fields.
- `TameworkFlightFlapInteraction`, `TameworkFlightAirbrakeInteraction`, and `TameworkFlightBoostInteraction` route Reins item actions into avatar-flight intents.
- The existing Reins actions act on `firstRun` for one-shot controls, which is required for flap and Q boost to avoid repeated spending.
- Airborne sprint/shift is not reliable enough to be a primary boost input.

Playtest result: jump-hold launch was not reliable enough in the current client path. Grounded crouch-hold is now the preferred launch input because crouch is observable, visually reads as a launch coil, and does not fight the normal jump impulse. Reins primary-hold remains a possible fallback if crouch-hold becomes problematic.

## Control Model

Primary controls:

| Input | Target Behavior |
| --- | --- |
| Mouse look | Controls heading and pitch. Pitch affects movement and visual presentation. |
| Forward input | Starts or resumes glide from hover or stall with a small initial kick, not an instant cruise motor. |
| Backward input | Airbrakes when moving forward; gives slow backward movement only when already stationary or moving backward. |
| Crouch | Applies smooth, direct downward velocity while airborne, unless it began as a grounded launch charge. |
| Left click with Reins | Upward flap while airborne. Grounded use may become charged launch fallback if crouch-hold fails. |
| Right click with Reins | Airbrake. Tap applies a slowing pulse; hold can bring the player to a hover over time. |
| Q with Reins | Directional forward boost. This replaces shift as the primary boost input. |
| Crouch hold while grounded | Preferred charged launch input. Release applies the charged takeoff if the hold reached the minimum. |

Controls must be intent-based, not sticky booleans. Pressing Q once should trigger one boost. Holding or latching a signal must not spend every cooldown until Vigour is empty. The same rule applies to flaps and launch release.

## Flight Modes

The controller should distinguish at least these modes:

- Grounded: normal transformed ground locomotion. No forced glide while standing or walking.
- LaunchCharging: grounded crouch-hold charge state before takeoff.
- Launching: short impulse application when a charged launch releases.
- ForwardFlight: active glide or powered forward motion.
- Hover: nearly stationary aerial state, usually after airbrake.
- Braking: active airbrake drag.
- Backing: slow backward movement when backward input is intentional and the avatar is not moving forward.
- Stall: low-speed state with stronger sink and reduced horizontal control.

Mode transitions should be explicit. Walking off a small ledge should not permanently lock the player into high-speed flight. Landing should return to grounded behavior. From hover or stall, forward input should start moving again with a modest kick so the player is not trapped unless they spend Vigour.

## Movement And Glide Balance

The flight model must avoid a passive infinite motor.

Accepted balance direction:

- Level forward glide tends toward a modest neutral speed, not full cruise.
- Neutral speed default target: `6`.
- Natural glide speed cap default target: `15`.
- Boosted speed cap default target: `21`.
- Ordinary level glide should gradually decay toward neutral speed.
- Diving can build speed up to the natural glide cap, but should ramp slowly enough that short dip-and-pull-up loops are not free altitude.
- Active Q boost is the only default path into the boosted speed band.
- Pitching up trades speed for altitude and should feel like carving upward, not hovering like a helicopter.
- Clean unboosted dive/climb maneuvers should recover roughly `70%` of lost altitude, not `100%`.
- Low-speed or stalled flight should sink much faster than clean glide.

The current linear tuning was difficult to balance because dive speed appeared too quickly and climb penalties could become too harsh. The next tuning pass should use curved load values:

- `diveLoad`: increases while the player remains pitched downward; gates how quickly altitude converts to speed.
- `climbLoad`: increases while the player remains pitched upward; gates how strongly speed converts into lift and drag.
- pitch angle power: steeper pitch should have a nonlinear effect. Straight down builds speed faster than a shallow dive. Straight up spends speed faster than a shallow climb.
- speed eligibility: climb lift should be stronger when the player has stored speed and weaker near neutral or stall.

Storyboard candidate values:

```text
divePitchPower = (absPitch / 70)^1.55
diveLoad = quadratic ramp over about 1.6s
climbPitchPower = (absPitch / 70)^1.35
climbLoad = curved ramp over about 1.1s
climbSpeedEligibility = sqrt((speed - neutralSpeed) / (maxGlideSpeed - neutralSpeed))
```

These values are design starting points. The important rule is not the exact exponent; it is that sustained maneuvers matter more than short flicks, while strong pitch angles still produce a clear response.

## Directional Q Boost

Q with Flightmaster's Talisman is the primary forward boost. It spends Vigour and applies directional thrust based on where the player is looking.

Design rules:

- Q boost costs one Vigour charge.
- A single press triggers one boost.
- Downward boost can use full directional thrust.
- Upward boost can contribute lift, but should be capped so flap remains the stronger vertical recovery tool.
- Boosted excess speed should decay toward the natural glide cap over time.
- Boost should feel good when pointed level or slightly upward, but it should not let the player convert every boosted block of speed into free altitude.

Initial formula direction:

```text
boost = 7
horizontalImpulse = boost * cos(abs(pitch))

if pitch < 0:
  verticalImpulse = -boost * sin(abs(pitch))

if pitch > 0:
  verticalImpulse = min(boost * sin(pitch) * 0.45, 3)
```

The cap is the key distinction: downward boost is full directional thrust; upward boost is directional but lift-limited.

## Upward Flap

Left click with Reins performs an upward flap.

Rules:

- Costs one Vigour charge.
- Applies more raw upward lift than Q boost.
- Should work while airborne regardless of whether the avatar is currently climbing or descending.
- Should not become stuck as a continuous upward force.
- Should not fire repeatedly from one click or one latched signal.

Flap is the main emergency altitude recovery tool. Q boost is a speed and directional tool that can assist climbs but should not replace flap.

## Airbrake

Airbrake is available through right click with Reins and backward movement intent.

Rules:

- Tapping airbrake applies a noticeable but not instant speed reduction.
- Holding airbrake should bring the avatar to a full hover over a short, realistic time window.
- While held at a stop, the avatar should remain horizontally and vertically stable enough to feel like a hover.
- Releasing airbrake and pressing forward should resume glide without requiring crouch or strafe.
- Backward input should not reverse full-speed flight instantly; it should brake first.

The airbrake should not feel like hitting a wall. The player should be able to correct a route, stop on purpose, and restart cleanly.

## Crouch Descent

Crouch should be direct downward control:

- holding crouch continuously applies smooth downward velocity;
- it should not become a sticky downward state after release;
- it should be useful for controlled landing and altitude correction;
- it should not be required to resume forward flight from hover.

## Vigour

Resource name: `Vigour`.

Default values:

```text
MaxCharges = 6
UpwardFlapCost = 1
ForwardBoostCost = 1
GroundedRechargeSecondsPerCharge = 4
FastFlightRechargeSecondsPerCharge = 8
FastFlightRechargeSpeedRatio = 0.75
RechargeDelayAfterSpendSeconds = 0.75
```

With default movement values, fast-flight recharge threshold is:

```text
(MaxForwardSpeed + ForwardImpulse) * FastFlightRechargeSpeedRatio
(14 + 7) * 0.75 = 15.75
```

That threshold is intentionally above the natural glide cap of `15`. Unboosted dive loops should not recover Vigour. Fast recharge should require the player to reach boosted-speed territory through ability use or excellent high-speed routing.

Recharge rules:

- Grounded recharge is the reliable reset path.
- Airborne recharge is speed-only, using horizontal speed.
- Fast airborne recharge grants one charge every eight seconds at or above the threshold.
- Spending a charge starts a short recharge delay.
- Slow flight, hovering, braking, and low-speed climbing do not recharge Vigour.

This economy should make the best flights feel extendable, not free. A skilled route can stretch Vigour, but careless flight still lands.

## HUD

The HUD should be a compact center cluster just above the vanilla health/stamina/hotbar area.

Elements:

- speed gauge showing horizontal speed over boosted cap;
- six Vigour pips with partial fill for the currently recharging charge;
- no large text labels by default;
- no modal panel;
- no full-screen darkening;
- no broken image or placeholder background.

Implementation constraints:

- Any images used by the custom UI must live under the custom UI directory path expected by Hytale.
- The root should stay transparent.
- The HUD should render only intentional bar and pip assets, not a generic window background.
- It should be visible while airborne, spending, recharging, or below full Vigour.
- It can dim or hide when grounded at full Vigour.

The speed gauge should use the same horizontal speed metric that gates fast-flight recharge. If those diverge later, the HUD must label or visually distinguish the metric clearly.

## Charged Launch

Ground takeoff was shown by the storyboard to be too expensive if the player must spend normal flaps from a flat start. The selected launch concept is grounded crouch-hold first, with Reins primary-hold as a fallback if crouch-hold cannot be made reliable. `JumpHold` remains a configurable input option but is not the default.

Preferred behavior:

- Hold crouch while grounded at least `500ms`: start charge.
- Continue holding up to `3000ms`: charge increases.
- Release after charge threshold: spend Vigour, apply launch, and enter flight.

Initial curve:

```text
minChargeMs = 500
maxChargeMs = 3000
charge = ((holdMs - minChargeMs) / (maxChargeMs - minChargeMs))^0.65
upImpulse = 6 + 12 * charge
forwardImpulse = 6 + 5 * charge
```

Suggested sample points:

| Hold Time | Up Impulse | Forward Impulse | Vigour Cost | Expected Result |
| --- | ---: | ---: | ---: | --- |
| `< 500ms` | none | none | 0 | launch cancel |
| `500ms` | 6.0 | 6.0 | 1 | small hop into glide |
| `1000ms` | 10.2 | 7.8 | 1 | reliable quick launch |
| `2000ms` | 14.6 | 9.6 | 2 | strong takeoff |
| `3000ms` | 18.0 | 11.0 | 2 | full launch into useful altitude |

Balance intent:

- Launch is the ground-to-air transition.
- Flap remains the in-air vertical recovery tool.
- Q boost remains directional thrust.
- Launch should not make low-altitude misplay irrelevant.
- Full launch should reach a useful low-altitude route without spending the entire Vigour bar.

If grounded crouch-hold becomes unreliable, use grounded Reins primary-hold for the same launch system. In that fallback, left click remains airborne flap after flight has begun.

## Visual Mount And Rider

The transformed player is the mount body. A rider copy is attached for presentation.

Requirements:

- The real player's held item, hands, armor, and equipment visuals should be hidden on the transformed mount body.
- The fake rider should use the player's skin, face, hair, clothes, armor, and held equipment where possible.
- Armor on the fake rider should hide the corresponding body/clothing parts, matching normal player equipment behavior.
- If a player hides a piece of armor, the matching clothing/body part should become visible again.
- Visual hiding must restore correctly on dismount or flight deactivation.
- The held item on the real transformed player must not float under the mount.
- The fake rider should attach to a model node such as `MountAnchor`.
- If the target model lacks the configured anchor node, the system should fall back gracefully to a configured offset and log a useful warning.

The model-attachment route is preferred over using the game's mount system for the fake rider because native mounting caused client crashes and input/motion conflicts during earlier experiments.

## Fake Rider Pose And Animation

The fake rider should look seated and stable:

- seated pelvis and legs;
- arms forward as if holding reins or a harness;
- hands aligned with forearms so untextured wrist gaps are not exposed;
- head and face can follow player look where the base system supports it;
- flight animations from the transformed mount should not distort the fake rider body.

The rider attachment needs a separate or protected rig path so armor follows the seated rider, not the transformed mount. Previous experiments showed that disabling rider animation can break armor if the armor is attached to the wrong animated rig, so the correct target is a seated rider rig that is stable but still provides the expected attachment nodes.

## Pitch And Bank Presentation

The mount body should visually communicate flight direction:

- pitch should broadly match where the player is looking;
- banking should roll left or right based on turning direction;
- roll should be modest, around `20-30` degrees, not extreme;
- pitch/bank presentation must not take control away from mouse look;
- it must not cause continuous unintended yaw drift.

Directly forcing model rotation interfered with camera control and caused slow yaw drift, so the accepted direction is animation-based presentation.

To avoid forcing modders to manually add many pitch and bank animations to every model asset, Tamework should inject generic origin-node pitch and bank clips into compatible model assets at runtime or asset-load time. The animation should target a common root/origin node pattern and degrade gracefully when a model does not support it.

Accepted presentation direction:

- generic origin animation clips;
- breakpoints for pitch and bank rather than continuous rotation;
- blend duration around `0.75s` to smooth transitions;
- pitch levels around `20%` and `40%`;
- bank levels around `20%` and `30%`;
- bank thresholds sensitive enough that high bank states are reachable during sharp turns.

This is not perfect continuous pitch/roll, but it is currently more viable than direct transform rotation and far more modder-friendly than per-model custom clips.

## Main Dragon Animations

The transformed flight model should not play player item-hold or weapon animations.

Animation targets:

- Grounded idle/run should use the transformed model's normal ground animations.
- Hover or stationary airborne state should use the model's flying idle animation.
- Normal forward flight should use the model's fly animation.
- Boosted or fast flight should use the model's fast fly animation where available.
- Falling should not dominate simply because glide always has slight downward velocity.
- Pitch/bank presentation should layer without breaking the model's normal flying animation.

The current working owner-equipment hiding path should be preserved. `UsePlayerAnimations: false` alone was not enough because passive item-hold and equipment animations still leaked through in earlier tests.

## Ability Slots Beyond Movement

The session discussed future NordicDrake abilities, but they are out of scope for the next implementation pass.

Longer-term direction:

- Q is currently used for forward boost, so future attacks may need E/R or alternate Reins action slots.
- Config should eventually support ability slots with cooldowns and costs.
- NordicDrake examples could include fire breath and fireball.
- Ability behavior belongs in avatar-flight config, not hardcoded into Flightmaster's Talisman.

Future ability config should preserve backward compatibility and should not make Reins species-specific.

## Outcome Targets

The math storyboard should remain the balancing tool for this feature. It should show altitude, speed, distance, and Vigour over time for representative routes.

Accepted targets:

- A clean unboosted dive/climb loop should recover about `70%` of the altitude lost during the dive.
- Repeating unboosted dive/climb loops should be net altitude-negative.
- Best-case route should maintain altitude for a while by spending Vigour and flying fast enough to recover some charges.
- Mid-case unpowered route should travel a useful distance but eventually land.
- Worst-case overpitch route should stall and land quickly.
- Ground takeoff should be possible without burning the entire Vigour bar, hence charged launch.

Representative storyboard scenarios to keep:

- high-altitude dive then climb at several pitch angles;
- best/mid/worst 90-second routes from a fixed altitude;
- ground takeoff route with and without charged launch;
- Q boost at downward, level, and upward pitch;
- Vigour recovery under boosted-speed and non-boosted-speed routes.

## Config Shape

All important values should be configurable from the start. The exact Java field names can follow existing style, but conceptually the config needs these sections:

```text
Movement:
  NeutralGlideSpeed
  MaxGlideSpeed
  BoostedSpeedCap
  GlideStartKickSpeed
  GlideSinkSpeed
  StallSinkSpeed
  StallSpeedThreshold
  DiveLoadRampSeconds
  DivePitchExponent
  ClimbLoadRampSeconds
  ClimbPitchExponent
  ClimbAltitudeRecoveryTarget

Boost:
  ForwardImpulse
  UpwardPitchLiftMultiplier
  UpwardPitchLiftCap
  CooldownMs
  BoostedSpeedDecay

Flap:
  UpImpulse
  CooldownMs

Airbrake:
  TapDrag
  HoldDrag
  HoverStabilization
  MinStopSeconds

Vigour:
  Enabled
  MaxCharges
  UpwardFlapCost
  ForwardBoostCost
  LaunchPartialCost
  LaunchFullCost
  GroundedRechargeSecondsPerCharge
  FastFlightRechargeSecondsPerCharge
  FastFlightRechargeSpeedRatio
  RechargeDelayAfterSpendSeconds
  HudEnabled

Launch:
  Enabled
  PreferredInput
  FallbackInput
  MinChargeMs
  MaxChargeMs
  ChargeExponent
  MinUpImpulse
  MaxUpImpulse
  MinForwardImpulse
  MaxForwardImpulse

RiderVisual:
  HideOwnerEquipment
  HideOwnerArmor
  HideOwnerHands
  ShowRider
  AnchorNode
  AttachmentModel
  MatchAppearance
  MatchEquipment

Presentation:
  EnablePitchBankAnimations
  BlendDuration
  PitchBreakpoints
  BankBreakpoints
```

Schema additions must preserve old configs through defaults. Parent/child inheritance should follow Tamework's normal asset inheritance rules.

## Debugging And Validation

Needed probes:

- crouch press, hold duration, release, native crouch suppression, and launch result;
- optional jump-hold diagnostics if a config explicitly selects `JumpHold`;
- Reins primary hold/release for fallback launch;
- per-intent logs for flap, airbrake, Q boost, and failed Vigour spend;
- speed, pitch, dive load, climb load, altitude delta, Vigour value, and recharge mode;
- HUD asset path validation so missing images are caught before runtime;
- rider visual anchor resolution and fallback logs.

Needed automated coverage where feasible:

- config defaults and inheritance;
- Vigour spend at zero charges;
- recharge delay, grounded recharge, and fast-flight threshold;
- Q boost one-shot behavior;
- Q boost upward cap and downward full thrust math;
- natural glide cap below fast recharge threshold;
- no unboosted infinite-flight loop in simulated route tests;
- launch curve sample points;
- airbrake can stop and forward input can resume;
- owner equipment hiding restore;
- fake rider appearance and equipment selection mapping;
- pitch/bank clip injection default behavior.

Manual verification:

- enter avatar flight with NordicDrake;
- confirm ground mode stays grounded until launch/jump/fall behavior requires flight;
- test forward start from hover without crouch workaround;
- test flap, Q boost, airbrake, crouch, and pitch transitions;
- test the HUD above hotbar with no placeholder image and no screen darkening;
- test fake rider with several skin, hair, clothes, armor, hidden armor, and held item combinations;
- test dismount/deactivation restores real player visuals.

## Risks And Open Questions

- Jump-hold launch may not be viable if native jump cannot be suppressed cleanly.
- Reins held interaction duration may not expose release cleanly enough for fallback launch without more packet handling.
- Pitch/bank animation breakpoints may still feel stepped; more breakpoints improve feel but add asset/runtime complexity.
- The exact dive/climb exponents need playtesting against real Hytale movement.
- Rider appearance mirroring may miss future modded player attachment conventions unless the mapping stays generic.
- HUD asset paths are easy to break if images are placed outside the custom UI directory.
- Q is currently movement boost, so future offensive abilities need a clear input plan.

## Relationship To Existing Specs

This document supersedes the narrow launch-only spec as the main design reference for the next planning step. It incorporates and updates:

- `2026-07-04-dragon-reins-avatar-flight-design.md`
- `2026-07-07-avatar-flight-vigour-hud-design.md`
- `2026-07-07-avatar-flight-charged-launch-design.md`

The older mounted glide design remains useful as historical context only. The next implementation plan should target transformed-avatar flight, not native mounted glide.

## Approval Gate

Before implementation planning, review this complete spec for:

- whether the final control mapping matches the intended player experience;
- whether the Vigour economy and glide balance targets are acceptable;
- whether charged launch should be included in the next implementation pass or prototyped separately;
- whether fake rider visuals and pitch/bank presentation are required for the same milestone as balance tuning.
