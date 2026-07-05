# Dragon Reins Avatar Flight Design

## Purpose

Dragon Reins provide a real, held Tamework item that gives transformed dragon avatar flight an explicit control focus. The item is the player's signal that they intend to control a dragon-like form, while the active avatar flight config remains responsible for species-specific flight tuning and abilities.

This design keeps the current transformed-player avatar flight direction, avoids native client creative flight, and adds a reliable item-input layer for actions that movement packets do not expose well enough.

## Goals

- Add a generic Tamework item named `Dragon Reins`.
- Make Dragon Reins available through the creative inventory.
- Use Dragon Reins as the required held item for active avatar-flight item controls.
- Keep ability behavior config-driven instead of hardcoding NordicDrake actions into the item.
- Support an initial control mapping that can be tested incrementally:
  - Left click: upward flap.
  - Right click: airbrake.
  - Shift: forward boost, if the existing movement input remains reliable.
  - Mouse look and pitch: steering plus speed/altitude tradeoff.
  - Q, E, and R: reserved ability slots until their server-side input path is confirmed.

## Non-Goals

- Do not implement the final real mount interaction flow in this pass.
- Do not force hidden item swapping or automatic equipment management.
- Do not make Dragon Reins contain NordicDrake-specific ability logic.
- Do not depend on native creative/client flight mode for the main controller.
- Do not assume Q, E, or R are usable until a focused input probe proves the packet or interaction path.

## Player Experience

Dragon Reins are a normal item the player can hold. In the prototype flow, the player enters transformed dragon avatar flight through the existing debug command and holds Dragon Reins to access active flight controls.

In the eventual mount flow, the mount or transform interaction should require the player to hold Dragon Reins. That makes the interaction understandable and avoids a hidden control mode.

If the player is transformed but not holding Dragon Reins, avatar flight should remain active enough to avoid corrupting state, but intentional controls should be limited. The controller should fail quietly and predictably: the dragon does not flap or airbrake because the player is not holding the control item.

## Architecture

### Dragon Reins Item

Dragon Reins are a generic Tamework item asset. The item exists to identify that the player is in dragon-control mode. It should not know about NordicDrake, fire breath, fireballs, or any other species-specific behavior.

The item should be configured so Hytale sends usable item interaction events for the inputs we need to capture. Left and right mouse input are the first supported targets because Hytale exposes them through `MouseInteraction` and `PlayerMouseButtonEvent`.

### Input Capture

The avatar-flight input layer should merge two sources:

- Movement input: mouse direction, pitch, and shift/sprint if reliable.
- Reins item input: left click, right click, and later ability slots.

Left and right click should be captured as edge-triggered actions, not held booleans. A single click should queue one flap or one airbrake action. If a held-click path is later required, it should be configured separately from click actions.

The input layer should check both conditions before applying reins controls:

- The player has active avatar flight state.
- The active held item is Dragon Reins.

### Flight Controller

The current avatar flight controller remains the owner of motion. Reins input should feed controller intents rather than applying movement directly from item event handlers.

Initial controller intents:

- `FlapUp`: adds upward velocity subject to cooldown and tuning.
- `Airbrake`: reduces forward velocity and may increase drag for a short duration.
- `ForwardBoost`: uses the existing shift/sprint path if reliable.
- `AbilitySlot`: reserved for later actions once Q, E, and R are validated.

While avatar flight is active, the controller should own transformed-dragon movement animations and suppress player-rig overlay animation slots that do not fit the dragon model. The default config clears Action, Status, and Emote slots at a throttled interval, leaves Face untouched, and clears forced slots again when avatar flight is disabled.

### Ability Configuration

Abilities belong in avatar flight config, not on Dragon Reins. This lets NordicDrake, future dragons, and non-dragon flying forms share the same control item while defining different abilities.

Future config shape should be conceptually similar to:

```text
Abilities:
  Ability1:
    Input: Q
    Type: FireBreath
    CooldownMs: 6000
    Params:
      Range: 18
      ConeDegrees: 30
      DurationMs: 1400
  Ability2:
    Input: E
    Type: Fireball
    CooldownMs: 3500
    Params:
      ProjectileId: configured fireball projectile asset id
```

The exact field names should follow the existing `TwAvatarFlightConfig` style when implemented. Backward compatibility matters: adding abilities must preserve defaults for existing configs.

## Evidence And Assumptions

Confirmed from Hytale Workshop `0.5.6`:

- `GamePacketHandler#handleMouseInteraction` handles client `MouseInteraction` packets.
- `InteractionModule#doMouseInteraction` dispatches `PlayerMouseButtonEvent` and includes the mouse button state and held item.
- Item ability examples exist as root interactions, such as `Root_Weapon_Stick_Fire_Ability1_Entry`, which uses `RequireNewClick: true`.

Assumptions to validate:

- A custom Tamework item can be configured to produce the same left and right mouse interaction events while the player is transformed.
- Q, E, and R are probably item/root-interaction ability paths, but their exact server-side signal is not yet proven.
- Shift/sprint remains available through movement input when not using native client flight.

## Implementation Strategy

1. Add Dragon Reins as a normal creative-available Tamework item.
2. Add focused debug logging for Dragon Reins item interactions:
   - left click,
   - right click,
   - Q,
   - E,
   - R,
   - held item id,
   - active avatar-flight state.
3. Wire only the proven inputs first:
   - left click to `FlapUp`,
   - right click to `Airbrake`.
4. Keep Q, E, and R logged but unbound until test logs show a stable signal.
5. Add ability config fields only after input slots are confirmed.

## Error Handling

- If Dragon Reins are not held, ignore reins-only controls.
- If avatar flight is inactive, item events should not trigger flight behavior.
- If an ability is configured but unavailable, log a throttled debug or warning with config id and input slot.
- Cooldowns should use explicit unset sentinels and must not assume positive world-time timestamps.

## Testing

Unit and architecture tests should cover:

- Dragon Reins item asset exists and uses the expected id.
- The item is registered or discoverable through the same item path as other Tamework items.
- Avatar flight item controls require both active avatar flight and Dragon Reins held.
- Left click maps to one flap intent.
- Right click maps to one airbrake intent.
- Ability fields, when added, preserve default config compatibility.
- Thread-safety guard tests remain clean for any packet/event bridge.

Manual test pass:

- Spawn or obtain Dragon Reins from creative inventory.
- Enter avatar flight debug mode.
- Confirm no item controls fire without Dragon Reins held.
- Hold Dragon Reins and test left click, right click, shift, mouse look, and pitch.
- Run a focused Q/E/R logging pass before assigning abilities.

## Open Questions

- What exact packet or event path do Q, E, and R use for item abilities?
- Does Dragon Reins need a minimal root interaction asset to make Q/E/R visible to the server?
- Should airbrake be a click pulse, a held action, or configurable per flight profile?
- Should the eventual mount interaction consume durability, require ownership, or only require holding Dragon Reins?
