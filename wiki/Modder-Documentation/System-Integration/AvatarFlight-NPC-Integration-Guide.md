---
title: "AvatarFlight NPC Integration Guide"
order: 10
published: true
draft: false
---
# AvatarFlight NPC Integration Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

AvatarFlight lets an ordinary Tamework NPC start a transformed-player flight
session. The original NPC is not replaced: Tamework parks and hides that same
entity while the rider uses the configured flight model, then restores it on
dismount or recovery. This preserves the NPC's owner, name, health, inventory,
needs, traits, and other supported companion state.

Use this for dragon-style companions and other rideable NPCs whose flight form
should be controlled as the player rather than as a conventional mounted NPC.

## What You Need

- Tamework installed as a dependency for the server and clients.
- A tamed, owner-mountable NPC role that already uses the optimized
  `TameworkInteract` path. See [Interaction Paths and Role Wiring](/mod/alecs-tamework/interaction-paths-and-role-wiring).
- A `TwAvatarFlightConfig` asset under `Server/Tamework/AvatarFlight/`.
- A valid server model asset for the transformed flight form when
  `Model.ApplyModel` is enabled.
- The player's selected hotbar item must be Tamework's
  `Tamework_Flightmasters_Talisman` before mounting. Include a way for players
  to obtain that item in your modpack's normal progression.

## 1. Add the Mount Interaction

AvatarFlight starts from the normal optimized `Mount` interaction; it does not
need a species-specific Java action. Give the role a `TwInteractionConfig` with
a `Mount` entry. This minimal config keeps the usual tamed, owner, mountable,
and crouching gates explicit:

```json
{
  "Enabled": true,
  "RoleIds": ["MyMod_Tamed_Dragon"],
  "Interactions": [
    {
      "Type": "Mount",
      "RequireTamed": true,
      "RequireOwner": true,
      "RequireMountable": true,
      "RequireCrouching": true
    }
  ]
}
```

The role must also run `TameworkInteract` when a player interacts with it.
Starting from Tamework's role template is the simplest route: it already
contains the interaction instruction and prompt wiring. If your role uses a
custom template, add `TameworkInteractPrompt` for prompt updates and invoke
`TameworkInteract` after locking the interaction target.

## 2. Enable AvatarFlight on the NPC Role

On the tamed/rideable role, set these parameters:

```json
"IsMountable": { "Value": true },
"MountMode": { "Value": "TameworkAvatarFlight" },
"AvatarFlightConfig": { "Value": "MyMod_Dragon_AvatarFlight" }
```

`AvatarFlightConfig` is the asset id of the config in the next step. Keep this
id explicit. A blank or unresolved id falls back to Tamework's active profile,
which can make one NPC unintentionally inherit another pack's flight tuning.

The source role must still be a normal mountable Tamework NPC. AvatarFlight
performs its own preflight validation and refuses to start when the resolved
profile is disabled, its configured model is missing, either participant is
dead, or either entity already has a native, ride, mounted-glide, or
AvatarFlight mount session.

## 3. Create a Flight Profile

Create `Server/Tamework/AvatarFlight/MyMod_Dragon_AvatarFlight.json` in your
mod. A child of the shipped default profile is the safest starting point:

```json
{
  "Parent": "Tamework_Avatar_Flight_Default",
  "Enabled": true,
  "Model": {
    "ApplyModel": true,
    "ModelId": "MyMod_Dragon_AvatarFlight",
    "Scale": 1.0
  },
  "Animation": {
    "IdleAnimation": "FlyIdle",
    "FlightAnimation": "Fly",
    "FastFlightAnimation": "FlyFast"
  },
  "RiderVisual": {
    "ShowRider": true,
    "SeatOffsetY": 1.35,
    "SeatOffsetZ": -0.25
  }
}
```

The filename becomes the config id, so the example above resolves as
`MyMod_Dragon_AvatarFlight`. Omitted sections and nested fields inherit from
`Tamework_Avatar_Flight_Default`; author only the model, animation, movement,
Vigour, launch, sound, VFX, trail, rider, or dismount settings that need to
differ.

`ModelId` must resolve to a server model asset. The transformed model needs the
animation ids selected in `Animation` (the defaults are `FlyIdle`, `Fly`, and
`FlyFast`). Set `Model.ApplyModel` to `false` only when intentionally retaining
the ordinary player model; non-player models have client-side compatibility
requirements and should be tested before release.

For the complete setting descriptions and default balance, see the repository's
[Avatar Flight reference](https://github.com/Alechilles/AlecsTamework/blob/main/docs/Avatar-Flight.md).

## 4. Prepare a Custom Model and Rider

If `RiderVisual.ShowRider` is `false`, you can use a suitable flight model
directly. If it is `true`, namespace the model nodes that overlap the player
rig so the reconstructed rider's skin, cosmetics, and equipment are not driven
by the dragon's animations.

Run the generator from the Tamework repository, substituting your mod root and
source server-model id:

```text
python scripts/tools/avatarflight_namespace_assets.py --mod-root "C:\Path\To\MyMod" --model-id MyMod_Dragon
```

It creates a `MyMod_Dragon_AvatarFlight` server-model variant and matching
model/animation copies. By default, it preserves `MountAnchor`, changes the
transformed root to `AF_Origin`, and namespaces rider-colliding nodes. Point
`Model.ModelId` at the generated model id.

Before packaging, verify that the generated model has the animation ids named
by your profile. Do not author nonempty `FootstepIntervals` arrays on a
transformed-player model animation: the current client can retain an invalid
footstep index when its model changes.

## Player Controls and Lifecycle

Players select Flightmaster's Talisman, crouch-interact with their companion,
and then use its flight controls:

- Hold and release crouch on the ground to charge and launch.
- Left-click to flap, right-click to airbrake, and Q to boost.
- Press F to dismount immediately, including while airborne.

The companion remains the same entity throughout the session. Tamework
restores it on normal dismount and also handles disconnect, death, world
transfer, source removal, disabled/missing config, stale saved sessions, and
orphaned rider recovery. Cross-world mounted travel is not supported; a world
transfer ends the session.

## Validation Checklist

1. Confirm the NPC shows the normal mount prompt only when its interaction
   requirements pass.
2. With Flightmaster's Talisman selected, mount the NPC, launch, flap,
   airbrake, boost, and dismount on both ground and in the air.
3. After dismounting, verify that the original NPC—not a newly spawned copy—is
   back with its owner, name, health, inventory, and companion data intact.
4. Repeat after a player reconnect and after deliberately making the configured
   model unavailable; no NPC should remain parked or hidden.
5. Test with the intended client model, especially rider visuals and grounded
   locomotion animations.

## Common Problems

| Symptom | Check |
| --- | --- |
| The NPC does not offer a mount prompt. | Confirm `TameworkInteractPrompt`, the matching `Mount` config, `IsMountable`, and the interaction requirements. |
| Mounting is refused. | Select `Tamework_Flightmasters_Talisman`, then confirm an enabled resolved profile, a valid model id, and no existing mount state. |
| The NPC uses the wrong flight profile. | Check the exact `AvatarFlightConfig` id. A blank or unresolved id intentionally falls back to the active profile. |
| The player transforms but has no expected flight animation. | Confirm the transformed server model declares the exact `IdleAnimation`, `FlightAnimation`, and `FastFlightAnimation` ids. |
| The fake rider deforms or animates with the dragon. | Generate and use the namespaced AvatarFlight model variant, keeping `MountAnchor` unchanged. |
| A player cannot remain mounted through a world change. | This is expected; AvatarFlight intentionally ends the session on cross-world travel. |

## Related Pages

- [Interaction Paths and Role Wiring](/mod/alecs-tamework/interaction-paths-and-role-wiring)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)
