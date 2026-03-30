---
title: "Enforce Ownership before Custom Command or Effect Recipe"
order: 15
published: true
draft: false
---
# Enforce Ownership before Custom Command or Effect Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: block your plugin command/effect unless the player actually owns the companion.

## Pattern
```java
Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

boolean allowed = api.policies().isOwner(profileId.get(), playerUuid);
if (!allowed) {
    chat.send(playerUuid, "You do not own this companion.");
    return;
}

runCustomEffect(profileId.get(), playerUuid);
```

## Notes
- Ownership checks are fast and are usually enough for player-command gating.
- For claim-aware and damage-aware decisions, use `evaluateClaimAccess(...)` and `evaluateDamage(...)`.

## Related Pages
- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)



