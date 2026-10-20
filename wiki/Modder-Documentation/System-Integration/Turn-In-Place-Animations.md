---
title: "Turn-in-Place Animations"
order: 13
published: true
draft: false
---
# Turn-in-Place Animations

Parent: [System Integration](/mod/alecs-tamework/system-integration)

Hytale chooses an NPC's movement animation on the client from its movement state, and there is no turning state. An NPC that turns without moving keeps playing its idle animation while its body rotates. From Tamework 5.4.0, Tamework plays a turning animation in that case.

## Opting in

Add two animation sets to the NPC's model asset:

```json
"AnimationSets": {
  "TurnLeft": {
    "Animations": [{ "Animation": "NPC/MyMod/Fox/Animations/Turn_Left.blockyanim" }]
  },
  "TurnRight": {
    "Animations": [{ "Animation": "NPC/MyMod/Fox/Animations/Turn_Right.blockyanim" }]
  }
}
```

Both sets must be present. A model without them is ignored. Child models inherit the sets from their parent model. To add them to a base-game model without replacing it, use a Patchwork `Merge` patch on the model asset.

Each clip should be a loop of the animal stepping in place: the feet lift and set down without travelling, the spine curves into the turn and the head leads. Use the same `Speed` as the model's `Walk` set so the steps match its walk. The base game ships unused turn clips for the Cow, Calf and Pig under `NPC/Livestock/<Animal>/Animations/Default/`.

## When the clips play

- The NPC must be standing: not walking, running, jumping, falling, flying, swimming, climbing, sitting, sleeping or mounted.
- Tamework smooths the body's turn rate over 0.2 seconds. A clip starts when the smoothed rate reaches 15 degrees per second and stops after it stays below 6 degrees per second for 0.4 seconds. Once started, a clip plays for at least 0.6 seconds before it can stop or switch sides.
- `TurnLeft` plays while the body yaw rises, `TurnRight` while it falls.
- A yaw change of more than 90 degrees in one tick counts as a teleport and is ignored.

## Animation slot

The clips play on the `ServerAction` animation slot, which base-game roles and hit reactions do not use. Tamework only starts a clip when that slot is empty or holds its own turn clip, and only clears clips it started, so another animation on that slot is never replaced.
