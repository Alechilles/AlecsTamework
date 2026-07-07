# Avatar Flight Charged Launch Design

## Purpose

Avatar flight has a solid high-altitude loop, but takeoff from flat ground is still too expensive. A player who starts on the ground should be able to deliberately launch into a useful altitude band without spending the entire Vigour bar on awkward low-speed flaps.

The selected direction is a charged launch: tapping jump remains a normal jump, while holding jump charges a takeoff. Releasing after the charge threshold launches the transformed avatar into flight with a configurable mix of upward impulse, forward impulse, and Vigour cost.

## Decision

Prototype jump-hold launch first.

Fallback if native jump suppression is unreliable: grounded Flightmaster's Reins primary-hold. In that fallback, holding left click on the ground charges launch, releasing launches, and left click returns to normal airborne flap behavior once flight has started.

## Source-Backed Input Notes

Hytale Workshop source checks were run against indexed release `0.5.6`.

Relevant base-game evidence:

- `com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems.ProcessPlayerInput#tick` iterates `PlayerInput.getMovementUpdateQueue()`, applies each input update, and clears the queue.
- `com.hypixel.hytale.server.core.modules.entity.player.PlayerInput.SetMovementStates#apply` writes movement states into `MovementStatesComponent`.
- `com.hypixel.hytale.server.core.modules.entity.player.KnockbackPredictionSystems.CaptureKnockbackInput#tick` demonstrates that systems can inspect and remove selected `PlayerInput.InputUpdate` entries before normal processing.
- `com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction#tick0` receives `firstRun` and `time`, so custom item interactions can potentially distinguish press start from held duration if the interaction remains active.

Repo-side evidence:

- `PlayerInputDebugProbe` already logs `jumping`, `crouching`, and mouse interaction packet button/motion data.
- `TameworkFlightFlapInteraction` and `TameworkFlightBoostInteraction` currently treat Flightmaster's Reins actions as one-shot intents by only acting on `firstRun`.
- `AvatarFlightInteractionControlService` already routes Reins actions into queued avatar-flight inputs.

Inference: jump-hold charge is plausible because Tamework can read jump state before native input is processed. The risk is whether native jump can be filtered cleanly enough to prevent a small normal jump or jitter while charging.

## Player Experience

Normal ground behavior:

- Tap jump under `500ms`: normal jump, no avatar-flight launch.
- Hold jump for at least `500ms`: enter launch charge.
- Continue holding up to `3s`: charge increases.
- Release after charge threshold: spend Vigour, launch upward and forward, and enter avatar flight.

The player should not need to look up perfectly to take off. Camera pitch can affect the launch direction slightly, but the launch should primarily be a takeoff impulse, not the same directional Q boost formula.

After launch, normal avatar-flight controls resume:

- left click with Reins: upward flap;
- right click with Reins: airbrake;
- Q with Reins: directional forward boost;
- crouch: downward velocity;
- pitch: speed/altitude exchange.

## Initial Charge Curve

First-pass values from the storyboard:

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
| `< 500ms` | native | native | 0 | normal jump |
| `500ms` | 6.0 | 6.0 | 1 | small hop into glide |
| `1000ms` | 10.2 | 7.8 | 1 | reliable quick launch |
| `2000ms` | 14.6 | 9.6 | 2 | strong takeoff |
| `3000ms` | 18.0 | 11.0 | 2 | full launch into cruising altitude |

Vigour cost should be configurable. The first tuning target is `1` charge for a partial launch and `2` charges for a full launch. If playtesting feels too punishing, keep the full launch at `1` charge first before raising launch strength.

## Balance Intent

Charged launch should solve a different problem from flap and Q boost:

- Flap is an in-flight vertical recovery tool.
- Q boost is directional thrust that can help climb but is mostly speed-positive.
- Charged launch is the deliberate ground-to-air transition.

The player pays for launch by standing still or moving slowly while charging. Full launch should feel powerful enough to reach a useful low-altitude route, but it should not replace high-altitude management. After the launch window, the same Vigour economy and altitude-negative unboosted loops still apply.

Target outcome:

- A quick `1s` launch should get the player safely airborne but not into a long cruise by itself.
- A full `3s` launch should establish roughly the `40-60` block comfort band shown in the grounded takeoff storyboard.
- Poor pitch management after launch should still stall.

## Implementation Shape

Design-level components:

- Add launch settings under `TwAvatarFlightConfig`, likely in a nested `Launch` section.
- Track launch hold state on `AvatarFlightInputComponent` or a focused companion state object.
- Add an input capture path that detects grounded jump hold while avatar flight is available.
- On release, queue a launch intent with normalized charge.
- Let `AvatarFlightMovementSystem` validate state, spend Vigour, and apply movement.

The movement system should remain the authority for Vigour cost and impulses. Input capture should only report intent and timing.

If the jump-hold prototype fails:

- Add a Reins grounded launch interaction instead of forcing jump.
- Reuse the same launch config and movement-system application path.
- Keep airborne Reins primary behavior as flap.

## Risks

- Native jump may fire before or during charge, causing visible jitter or accidental normal jumps.
- Filtering `PlayerInput.InputUpdate` entries must stay ECS-safe and avoid unsafe component writes in runtime systems.
- The client may show jump/fall animations during charge unless movement states are suppressed or overwritten cleanly.
- Charge release detection may be noisy if jump state is latched in the same way mounted jump previously was.
- Full launch values may need rapid iteration once actual Hytale physics and camera behavior are involved.

## Prototype Checks

Before committing the final input path:

- Log jump press, hold duration, release, native jump state, and resulting velocity while transformed on the ground.
- Verify tap jump still behaves normally and does not enter flight mode.
- Verify holding jump does not repeatedly spend Vigour.
- Verify releasing at `500ms`, `1s`, `2s`, and `3s` produces distinct launch strengths.
- Verify running forward before launch carries into the launch rather than starting from zero airspeed.
- Verify fallback Reins-hold can read hold/release if jump filtering is not viable.

## Non-Goals

- Do not change the high-altitude dive/climb balance in this pass.
- Do not add new attack abilities.
- Do not make takeoff free from Vigour cost unless playtesting proves the cost is too punitive.
- Do not remove normal jump behavior for short taps.
