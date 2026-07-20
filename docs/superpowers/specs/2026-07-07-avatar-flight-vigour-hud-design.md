# Avatar Flight Vigour and HUD Design

## Purpose

Avatar flight needs a resource layer that makes powerful movement abilities feel intentional and prevents indefinite low-effort flight. Vigour is a charge-based resource inspired by WoW dragonriding: players spend charges on upward flaps and forward boosts, then recover charges by landing or maintaining high-speed flight.

The HUD should make this state readable without turning the screen into an aircraft cockpit. The selected direction is a compact center cluster above the hotbar: speed readout plus six Vigour pips, visible only when contextually useful.

## Goals

- Add a configurable Vigour resource for transformed avatar flight.
- Spend Vigour on upward flap and forward boost abilities.
- Recharge Vigour smoothly into discrete charges.
- Reward high-speed flight with airborne recharge while still encouraging landing when the player runs dry.
- Show a compact HUD with Vigour pips and current speed versus configured max speed.
- Keep names and tuning values in `TwAvatarFlightConfig` so future flying forms can use different balance.

## Non-Goals

- Do not implement stamina for the old native-mounted glide controller.
- Do not add attack ability costs in the first Vigour pass.
- Do not create a full custom cockpit UI.
- Do not add complex pitch, descent, or route-quality scoring for recharge. Speed alone controls airborne recharge.
- Do not make fast-flight recharge fully sustain careless flight.

## Player Experience

The player sees six Vigour pips above the hotbar while avatar flight is relevant. Full pips represent usable charges. The next recovering pip fills smoothly so the player can tell when the next movement ability is nearly ready.

Upward flaps and forward boosts each spend one charge. If no charges are available, the action does not fire. Initial failure feedback can be subtle or omitted; the HUD state should be enough for the prototype.

On the ground, Vigour recovers quickly. In the air, Vigour recovers only when the dragon is moving fast enough. The player can extend a flight by diving, keeping speed, and using boosts wisely, but hovering or slow climbing will eventually force a landing.

## Baseline Tuning

Initial values:

- `MaxVigour`: `6`
- `UpwardFlapCost`: `1`
- `ForwardBoostCost`: `1`
- `GroundedRechargeSecondsPerCharge`: `4.0`
- `FastFlightRechargeSecondsPerCharge`: `8.0`
- `FastFlightRechargeSpeedRatio`: `0.75`
- `RechargeDelayAfterSpendSeconds`: `0.75`

Airborne recharge uses horizontal speed only. If current horizontal speed is at least `75%` of the configured max speed, Vigour recharges at one charge every eight seconds. Pitch and vertical direction are not direct recharge gates.

Horizontal speed is the preferred first-pass definition because it matches the speed gauge and avoids rewarding vertical fall speed if the player is simply dropping.

## Flight Balance

Normal gliding should slowly lose altitude unless the player spends Vigour or actively manages speed. Pitching upward can trade speed for altitude, but it should drain momentum quickly enough that climbing cannot stay fast indefinitely.

Fast-flight recharge should be helpful but not dominant:

- Grounded recovery is the reliable reset path.
- Fast airborne recovery rewards skilled momentum routes.
- Slow flight, hovering, airbraking, and low-speed climbing do not recover meaningful Vigour.
- Spending a charge creates a short recharge delay so an ability cannot immediately refund itself.

If in-game testing shows high-speed flight is too sustainable, tighten one value at a time:

- Raise `FastFlightRechargeSpeedRatio` from `0.75` toward `0.80`.
- Increase `FastFlightRechargeSecondsPerCharge` from `8.0` toward `10.0`.
- Increase climb drag or speed loss instead of adding pitch-based recharge rules.

## HUD Design

Selected layout: compact center cluster above the hotbar.

Elements:

- A narrow speed bar showing current speed as a fraction of configured max speed.
- Six circular Vigour pips below or near the speed bar.
- Empty pips use a subdued outline.
- Full pips use the active Vigour color.
- The currently recharging pip shows partial fill.

Visibility behavior:

- Fully visible while airborne.
- Fully visible while spending Vigour.
- Fully visible while recharging.
- Fully visible while below maximum Vigour.
- Dim or fade when grounded at full Vigour.

The HUD should not use large labels by default. It can expose a configurable label later, but the prototype should prioritize quick readability over text.

## Data Model

Runtime state belongs on the avatar flight component or a focused companion component:

- current Vigour value, stored as a double so partial recharge can be represented;
- last spend timestamp;
- last recharge mode;
- optional last HUD-significant values for throttled packet/UI updates.

Config belongs under `TwAvatarFlightConfig`, likely in a nested `Vigour` section:

```text
Vigour:
  Enabled: true
  MaxCharges: 6
  UpwardFlapCost: 1
  ForwardBoostCost: 1
  GroundedRechargeSecondsPerCharge: 4.0
  FastFlightRechargeSecondsPerCharge: 8.0
  FastFlightRechargeSpeedRatio: 0.75
  RechargeDelayAfterSpendSeconds: 0.75
  HudEnabled: true
```

Field names can be adjusted to match existing config style during implementation, but the behavior should remain the same.

## Ability Integration

Vigour checks should sit in the avatar flight controller, not in item interaction handlers. Item actions and input capture should request an intent; the controller decides whether the intent can spend Vigour and apply movement.

Upward flap flow:

1. Flightmaster's Talisman primary action queues a flap intent.
2. Controller checks active avatar flight, cooldowns, and Vigour.
3. If at least one charge is available, spend one charge and apply the upward boost.
4. If no charge is available, do not apply the boost.

Forward boost flow:

1. Sprint/shift boost input queues or activates a boost intent.
2. Controller checks active avatar flight, boost timing, and Vigour.
3. If at least one charge is available, spend one charge and apply the forward boost.
4. Repeated boost spending should be edge or pulse based, not every tick while sprint is held.

## UI Delivery

The implementation should first look for existing Hytale/Tamework UI primitives that can render a small custom HUD element. If the base UI path is too costly for the first pass, debug text is acceptable only as a temporary implementation step; the feature target remains the compact visual HUD.

The speed gauge should use the same speed definition as fast-flight recharge unless testing proves that is misleading. If horizontal speed drives recharge, the bar should show horizontal speed over max configured flight speed.

## Testing and Diagnostics

Add focused test coverage for:

- Vigour defaults and config inheritance.
- Ability spend gating at zero charges.
- Grounded recharge rate.
- Fast-flight recharge threshold and rate.
- Recharge delay after spending.
- HUD visibility state selection.

Add temporary debug logging or a `/tw` status field for:

- current Vigour value;
- recharge mode: none, grounded, fast-flight;
- current speed ratio;
- last ability spend result.

Debug output must be throttled or gated behind the avatar flight debug config.

## Accepted Decisions

- Resource name is `Vigour`.
- The HUD uses the compact center cluster direction.
- Airborne recharge is horizontal-speed-only.
- Fast-flight recharge starts at `75%` max speed.
- Grounded recharge is `1 charge / 4s`.
- Fast-flight recharge is `1 charge / 8s`.
- HUD fades contextually instead of staying fully visible at all times.
