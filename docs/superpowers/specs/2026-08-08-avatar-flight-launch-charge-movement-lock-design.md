# Avatar Flight Launch-Charge Movement Lock

## Goal

Prevent a transformed player from using grounded directional movement while charging an avatar-flight launch. Mouse-look and launch release remain available.

## Design

Reuse `AvatarFlightGroundMovementService`, which already owns the temporary native `MovementSettings.baseSpeed` override for transformed grounded players.

While all of the following are true, the service will synchronize a target base speed of `0`:

- Avatar flight is active.
- The controller is grounded and not in fluid.
- `AvatarFlightInputComponent.isLaunchCharging()` is true.

When the charge is released, cancelled, or otherwise ends, the next movement tick restores the configured avatar-flight grounded movement speed. Leaving the grounded state continues to use the existing restoration path.

The change will not set horizontal velocity to zero. This avoids cancelling knockback, collision expulsion, moving-platform motion, or other physics effects unrelated to directional input.

## Scope

- Keep charge timing, Vigour cost, VFX, audio, HUD, and launch impulses unchanged.
- Keep mouse-look available throughout charging.
- Do not add a configuration field; the movement lock is intrinsic to grounded launch charging.
- Document the player-visible behavior in `docs/Avatar-Flight.md` and `CHANGELOG.md`.

## Verification

Run the existing avatar-flight and repository test suite with `bash ../gradlew :alecstamework:test`. Do not add a source-shape or trivial speed-selection test; the meaningful observable behavior depends on the native client applying synchronized `MovementSettings`, so live gameplay remains the final validation for the movement lock.
