# Avatar Flight Post-1981e83 Tuning And Launch Design

## Purpose

This spec captures only the work we need to add or change after commit `1981e83c` (`Fix: make avatar boost inputs one-shot`). It is intentionally narrower than the living complete avatar-flight spec. The goal is to turn the post-`1981e83c` flight tuning and launch brainstorming into a focused implementation-plan source.

Commit `1981e83c` is the baseline where:

- Q/Reins boost is already one-shot instead of repeatedly spending Vigour while held or latched.
- The HUD placeholder/dark overlay issue was addressed.
- Avatar flight already has Vigour, neutral glide speed, natural glide cap, boosted speed cap, Reins flap, Reins airbrake, Q boost, crouch descent, and a small glide-start kick.
- No code changes have landed after that commit except design docs.

## Scope

Add or change only these areas:

- curve-based dive speed gain;
- curve-based climb speed-to-altitude exchange;
- directional Q boost with capped upward lift and full downward thrust;
- charged ground launch, now defaulting to grounded crouch-hold after jump-hold proved unreliable;
- tests, docs, and debug probes needed to validate those changes.

This spec does not cover rider visuals, equipment hiding, pitch/bank animation authoring, fake rider armor, or general avatar-flight architecture unless a change is directly required by the tuning or launch work.

## Baseline Behavior To Preserve

The implementation should preserve these `1981e83c` behaviors:

- Reins flap and Q boost are queued one-shot intents.
- Vigour gating happens in the avatar flight movement layer, not directly in item interactions.
- Fast-flight recharge remains horizontal-speed-only.
- Fast recharge threshold remains above natural glide cap by default:

```text
(MaxForwardSpeed + Boost.ForwardImpulse) * FastFlightRechargeSpeedRatio
(14 + 7) * 0.75 = 15.75
```

- Natural unboosted speed cap remains `Movement.MaxGlideSpeed`, currently `15`.
- Neutral level forward glide trends toward `Movement.NeutralGlideSpeed`, currently `6`.
- Forward input from hover or stall gets a small start kick and must not require crouch or strafe to resume.
- Crouch remains direct downward control and must not become sticky.
- Existing configs continue loading with defaults for new fields.

## Problems To Solve

### Short Dive Cheese

The live linear dive behavior can make speed appear too quickly. A player can dip down briefly, gain meaningful speed, pitch back up, and repeat. The result is closer to infinite flight than the intended altitude-negative glide loop.

### Over-Punishing Pull-Up

Some tuning attempts made pull-up burn horizontal speed too quickly. The player should eventually stall if they climb too long or too steeply, but an initially fast dragon should carry momentum through a clear upward carve before losing speed.

### Q Boost Is Not Directional Enough

At the baseline, Q boost is a forward-speed boost. The desired behavior is directional thrust: when aimed down it can drive a dive, when aimed level it drives speed, and when aimed up it can help lift but remains weaker vertically than flap.

### Ground Takeoff Is Too Expensive

The high-altitude loop can be tuned well, but starting from flat ground with only regular flap/Q spends burns too much Vigour before the player reaches a useful altitude band. A deliberate launch mechanic should handle ground-to-air transition.

## Target Flight Feel

The tuning target is momentum flight, not a helicopter:

- Diving for a sustained period should build speed and spend altitude.
- Pulling up with stored speed should regain a notable amount of altitude.
- Well-executed unboosted dive/climb loops should recover about `70%` of altitude spent, not `100%`.
- Short dip-and-pull-up loops should not pay off.
- Steeper dive angles should build speed faster than shallow dive angles.
- Steeper climb angles should spend speed faster than shallow climb angles.
- Low-speed overpitch should stall quickly.
- Boost and flap should feel powerful because they spend Vigour.

## Dive Curve

Replace the simple per-tick linear pitch-down speed gain with a sustained dive load model.

Design:

- Add a stateful `diveLoad` value on avatar flight state.
- `diveLoad` rises while the player is pitched down beyond the neutral pitch threshold.
- `diveLoad` decays when pitch returns to neutral or upward.
- Dive speed gain is multiplied by both pitch strength and `diveLoad`.
- Altitude loss should also follow the dive curve so a very brief dip does not instantly convert into speed.

Storyboard candidate values:

```text
divePitchPower = (absPitch / 70)^1.55
diveLoad = quadratic ramp over about 1.6s
```

Reference storyboard results for the proposed model:

| Dive | Time | Proposed Speed | Proposed Alt Lost | Read |
| --- | ---: | ---: | ---: | --- |
| `-45 deg` | `0.75s` | `6.18` | `1.67` | very low payoff |
| `-45 deg` | `1.50s` | `6.82` | `3.88` | modest payoff |
| `-45 deg` | `3.00s` | `9.09` | `9.36` | useful but not capped |
| `-70 deg` | `0.75s` | `6.35` | `1.84` | short dive still weak |
| `-70 deg` | `1.50s` | `7.63` | `4.74` | short dive cheese fails |
| `-70 deg` | `3.00s` | `12.13` | `13.52` | real speed after commitment |
| `-70 deg` | `6.00s` | `15.00` | `31.52` | reaches natural cap |

The exact math can move during implementation, but the shape should match this: sustained steep dives matter, short dips do not.

## Climb Curve

Replace the current mostly linear pitch-up trade with a sustained climb load and speed eligibility model.

Design:

- Add a stateful `climbLoad` value on avatar flight state.
- `climbLoad` rises while the player remains pitched up beyond the neutral pitch threshold.
- `climbLoad` decays when pitch returns to neutral or downward.
- Climb lift uses current horizontal speed eligibility, pitch strength, and `climbLoad`.
- Climb drag increases with pitch strength and sustained climb.
- The first part of a climb should carry momentum; drag should become more punishing as the climb continues.

Storyboard candidate values:

```text
climbPitchPower = (absPitch / 70)^1.35
climbLoad = curved ramp over about 1.1s
climbSpeedEligibility = sqrt((speed - NeutralGlideSpeed) / (MaxGlideSpeed - NeutralGlideSpeed))
```

Reference storyboard results:

| Maneuver | Dive Speed | Alt Lost | Alt Recovered | Return | End Speed | Read |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `-45 deg 1.5s -> +30 deg 4s` | `6.82` | `3.88` | `-6.97` | stall | `5.10` | not enough speed |
| `-70 deg 1.5s -> +45 deg 4s` | `7.63` | `4.74` | `-5.55` | stall | `4.47` | short dive cheese fails |
| `-70 deg 3.0s -> +45 deg 5s` | `12.13` | `13.52` | `9.70` | `72%` | `5.70` | target behavior |
| `-70 deg 3.0s -> +60 deg 5s` | `12.13` | `13.52` | `6.54` | `48%` | `4.12` | steeper pull-up stalls earlier |
| `-70 deg 6.0s -> +45 deg 6s` | `15.00` | `31.52` | `24.00` | `76%` | `5.97` | strong dive, still net losing |

Important: pitching up should not immediately zero horizontal speed. It should turn stored speed into lift, then bleed speed as the maneuver continues.

## Directional Q Boost

Change Q/Reins boost from a pure forward-speed pulse into a directional impulse based on pitch.

Design:

- Keep Q boost one-shot and Vigour-gated.
- Use camera pitch to split boost into horizontal and vertical components.
- Downward pitch gets full directional thrust.
- Upward pitch gets capped vertical lift so flap remains the stronger vertical ability.
- Boosted horizontal speed can exceed the natural glide cap, but should decay back toward natural cap unless refreshed by another boost.

Initial formula direction:

```text
boost = Boost.ForwardImpulse
horizontalImpulse = boost * cos(abs(pitch))

if pitch < 0:
  verticalImpulse = -boost * sin(abs(pitch))

if pitch > 0:
  verticalImpulse = min(boost * sin(pitch) * UpwardPitchLiftMultiplier, UpwardPitchLiftCap)
```

Default candidates:

```text
UpwardPitchLiftMultiplier = 0.45
UpwardPitchLiftCap = 3.0
```

The downward case can be full directional thrust because it spends altitude. The upward case must be capped because it creates altitude.

## Boosted Speed Decay

Boost should be the only normal path above natural glide cap, but that extra speed should not last forever.

Design:

- Track whether current horizontal speed is in boosted excess territory:

```text
horizontalSpeed > Movement.MaxGlideSpeed
```

- While not actively boosted, decay boosted excess toward `Movement.MaxGlideSpeed`.
- Do not decay so aggressively that Q feels wasted.
- Do not let boosted excess fully convert to climb altitude without the climb drag/lift caps applying.

This may reuse existing boost duration and cooldown fields, but it needs a distinct conceptual rule: boost duration controls active acceleration, while boosted-excess decay controls how long extra speed remains after the active pulse.

## Charged Ground Launch

Add a deliberate ground takeoff system.

Preferred input:

- hold crouch while grounded for at least `500ms`: begin launch charge;
- hold up to `3000ms`: charge increases;
- release after charge threshold: spend Vigour, apply launch impulse, enter flight.

Other supported inputs:

- `JumpHold` remains configurable, but is not the default after playtesting showed jump-hold was not reliably observable;
- grounded Flightmaster's Reins primary hold;
- hold left click while grounded to charge launch;
- release to launch;
- once airborne, left click returns to normal flap behavior.

Initial launch curve:

```text
minChargeMs = 500
maxChargeMs = 3000
charge = ((holdMs - minChargeMs) / (maxChargeMs - minChargeMs))^0.65
upImpulse = 6 + 12 * charge
forwardImpulse = 6 + 5 * charge
```

Sample points:

| Hold Time | State | Up Impulse | Forward Impulse | Vigour Cost | Expected Result |
| --- | --- | ---: | ---: | ---: | --- |
| `< 500ms` | tap | native | native | `0` | normal jump |
| `500ms` | ready | `6.0` | `6.0` | `1` | small hop into glide |
| `1000ms` | quick launch | `10.2` | `7.8` | `1` | reliable takeoff |
| `2000ms` | strong launch | `14.6` | `9.6` | `2` | useful low altitude |
| `3000ms` | full launch | `18.0` | `11.0` | `2` | target `40-60` altitude band |

Ground takeoff storyboard context:

- A controlled takeoff using `3 flaps + 3 Q` peaked near altitude `56` but spent almost the whole Vigour bar.
- A modest takeoff using `2 flaps + 1 Q` peaked near altitude `30`, never entering the target comfort band.
- A failed overpitch from low speed landed in about `5s`.

Launch exists to replace the awkward heavy-spend ground climb, not to replace in-flight flap or Q boost.

## Launch Input Probe

Jump-hold testing showed the held jump channel was not reliable enough for launch. The default should use grounded crouch-hold instead. Keep this probe pattern for future launch input candidates:

- log jump press start while grounded and transformed;
- log crouch press start while grounded and transformed;
- log held duration;
- log release;
- log whether native jump/crouch behavior was suppressed or still applied;
- log whether release generated exactly one launch intent;
- log resulting velocity and Vigour spend.

Acceptance for crouch-hold:

- airborne crouch remains direct descent unless a grounded launch charge is already active;
- holding does not spam launch/flap spend;
- releasing at `500ms`, `1s`, `2s`, and `3s` produces distinct impulses;
- native crouch does not create visible double-pop or jitter during charge;
- launch only fires once per hold/release.

If these fail, use the Reins primary-hold fallback without changing the launch movement math.

## Config Additions

Add fields with default values so existing configs inherit current behavior safely.

Recommended additions under `Movement`:

```text
DiveLoadRampSeconds = 1.6
DiveLoadDecaySeconds = 0.6
DivePitchExponent = 1.55
ClimbLoadRampSeconds = 1.1
ClimbLoadDecaySeconds = 0.6
ClimbPitchExponent = 1.35
ClimbSpeedEligibilityExponent = 0.5
BoostedSpeedDecay = 2.0
```

Recommended additions under `Boost`:

```text
Directional = true
UpwardPitchLiftMultiplier = 0.45
UpwardPitchLiftCap = 3.0
```

Recommended new `Launch` section:

```text
Enabled = true
PreferredInput = "CrouchHold"
FallbackInput = "CrouchHold"
MinChargeMs = 500
MaxChargeMs = 3000
ChargeExponent = 0.65
MinUpImpulse = 6.0
MaxUpImpulse = 18.0
MinForwardImpulse = 6.0
MaxForwardImpulse = 11.0
PartialChargeCost = 1.0
FullChargeCost = 2.0
FullChargeCostThreshold = 0.6
```

The exact field names can be adjusted to fit current `TwAvatarFlightConfig` style. The behavior must remain configurable from the start.

Backward compatibility:

- Existing `PitchUpLiftScale`, `PitchUpSpeedCost`, `PitchDownDiveScale`, and `PitchDownSpeedGain` should keep loading.
- New curve fields can modulate or replace those values internally, but old configs should not fail validation.
- Missing `Launch` should default to disabled or enabled according to the default asset, without breaking inherited configs. For the bundled default, enable launch once the input path is proven.

## Runtime State Additions

The movement controller needs state beyond velocity and cooldowns:

```text
diveLoad
climbLoad
launchChargeStartedAtMs
launchInputActive
nextLaunchAtMs or launch cooldown if needed
```

Likely locations:

- `AvatarFlightComponent` for persistent per-flight movement state such as `diveLoad` and `climbLoad`;
- `AvatarFlightInputComponent` for transient input-held state and queued launch intent;
- movement system/controller for validation and final application.

Input capture reports intent and timing. The controller remains the authority for launch, Vigour spend, and movement application.

## Tests

Add focused tests for:

- dive load ramps slowly enough that a `0.75s` dive gives low speed gain;
- sustained `-70 deg` dive reaches near the storyboard speed values and respects natural cap;
- climb from speed `12.13` at `+45 deg` recovers roughly `70%` of the recent dive altitude in simulation;
- short dive followed by climb remains net negative and stalls;
- steeper pull-up recovers less altitude than moderate pull-up;
- Q boost one press applies one directional impulse and spends one charge;
- upward Q boost vertical impulse is capped;
- downward Q boost applies full directional vertical component;
- boosted excess decays toward natural cap when not actively boosted;
- launch curve sample points match expected impulses;
- launch spend gating blocks launch without enough Vigour;
- grounded crouch release below threshold cancels launch;
- launch intent is one-shot;
- existing configs without `Launch` and curve fields still load.

Run the existing test suite after implementation:

```text
./mvnw test
```

Also run the ECS/player-access guard grep from `AGENTS.md` before finalizing runtime-system changes.

## Manual Verification

Manual pass after implementation:

- From hover, press forward and verify glide resumes without crouch or strafe.
- From altitude `100`, run a no-spend dive/climb route and confirm it eventually loses altitude.
- Dive steeply for `3s`, pull up around `45 deg`, and verify a clear but not full altitude recovery.
- Try short dip-and-pull-up loops and confirm they are net losing.
- Q boost level, downward, and upward; confirm each spends once and feels directional.
- Hold crouch while grounded for each launch sample duration and confirm distinct takeoff strength.
- Release crouch below threshold and confirm no charged launch.
- Confirm airborne crouch still applies direct descent when no grounded launch charge is active.
- If crouch-hold fails, repeat with grounded Reins primary-hold fallback.
- Confirm HUD still has no placeholder image and remains positioned above the hotbar.

## Non-Goals For This Delta

- Do not change fake rider visuals.
- Do not change armor/equipment hiding.
- Do not add fire breath, fireball, E/R abilities, or attack cooldowns.
- Do not rework pitch/bank visual animation breakpoints.
- Do not revisit the native mounted glide controller.
- Do not make launch free by default unless playtesting proves the Vigour cost is too punitive.

## Approval Gate

Use this spec as the source for the next implementation plan. Before planning, confirm:

- grounded crouch-hold is the default launch input, with Reins primary-hold as fallback;
- the `70%` clean maneuver altitude recovery target is still right;
- the launch default should spend `1` charge for partial launch and `2` for strong/full launch;
- the first implementation should include both curve tuning and charged launch, rather than splitting them into two commits.
