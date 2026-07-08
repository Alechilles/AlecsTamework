# Avatar Flight Launch Charge HUD Spec

## Goal

Add immediate UI feedback for the crouch-hold avatar-flight launch charge so players can tell how long crouch has been held and when a full launch charge is ready. This is a temporary UI-first feedback path; future particle and sound feedback can build on the same charge state.

## Approved Design

- Use a dedicated amber launch-charge bar.
- Place it above the existing pitch indicator, speed bar, and Vigour pips.
- Show it only while the player is grounded and actively charging launch.
- Fill from 0% at hold start to 100% at `Launch.MaxChargeMs`.
- Add a small threshold marker at `Launch.MinChargeMs / Launch.MaxChargeMs` so the player can see the earliest valid release point.
- Keep the existing normal-flight HUD unchanged when no grounded launch charge is active.

The default values are currently `MinChargeMs = 500` and `MaxChargeMs = 3000`, so the minimum valid marker should render at roughly 17% of the bar.

## Architecture

Extend the existing avatar-flight HUD path instead of adding a separate HUD.

- `AvatarFlightInputComponent` remains the source of launch charge state.
- `AvatarFlightHudSystem` should read the active input component alongside `AvatarFlightComponent` and `Player`.
- `AvatarFlightHudViewModel` should gain launch-charge render fields:
  - `launchChargeVisible`
  - `launchChargeRatio`
  - `launchMinChargeRatio`
- `AvatarFlightHudBinder` should bind a new `#LaunchChargeGroup`, `#LaunchChargeFill`, and `#LaunchMinChargeMarker`.
- `TameworkAvatarFlightHud.ui` should add the launch group above `#PitchLabel` and move existing HUD rows downward inside the same root.

Keep the root transparent and use only color-backed groups. Do not introduce image assets for this bar.

## Data Flow

1. Packet input capture begins launch charge via `AvatarFlightInputComponent.beginLaunchCharge(now)`.
2. HUD tick runs after movement/input state has been updated.
3. HUD model computes:
   - visible when `input.isLaunchCharging()` and `input.isOnGround()`
   - charge ratio as `clamp((now - input.getLaunchChargeStartedAtMs()) / maxChargeMs, 0, 1)`
   - min marker ratio as `clamp(minChargeMs / maxChargeMs, 0, 1)`
4. Binder updates bar visibility, fill width, and marker position.
5. If launch charge is cancelled, released, airborne, disabled, or missing input state, the launch group hides.

## UI Layout

Current stack:

- pitch label
- speed bar and target marker
- Vigour pips

New charging stack:

- launch-charge label/bar
- pitch label
- speed bar and target marker
- Vigour pips

The root should grow upward by increasing its height while keeping the same bottom anchor, so the HUD does not move downward into the hotbar.

Suggested sizing:

- root height: about 68-72 px
- launch bar width: match or slightly exceed speed bar width
- launch fill height: 8-10 px
- min marker: 2 px wide, slightly taller than the bar
- label: short, such as `Launch`, optional if space feels too busy

## Edge Cases

- If `Launch.MaxChargeMs <= 0`, hide the launch bar.
- If `Launch.MinChargeMs > Launch.MaxChargeMs`, clamp the threshold marker to 100%.
- If the player leaves the ground during a charge, hide the launch bar even if the release path may still be able to apply a charge that began on the ground.
- If launch is disabled in config, hide the launch bar.
- If the HUD is disabled through Vigour HUD settings, do not show a separate launch HUD.

## Testing

Add or update tests for:

- hidden view models clamp launch fields to zero/false
- visible view models clamp launch charge and min marker ratios
- HUD system passes active grounded charge state into the view model
- binder sets launch group visibility based on `launchChargeVisible`
- binder sets launch fill width from `launchChargeRatio`
- binder sets marker anchor from `launchMinChargeRatio`
- UI asset contains no image-backed launch elements

Run:

```powershell
.\mvnw test
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
.\mvnw package -DskipTests -Pinstall-plugin
```

## Out Of Scope

- Particle feedback.
- Sound feedback.
- Changing launch charge math or costs.
- Adding new launch inputs.
- Showing a failed/insufficient-Vigour state.

