---
title: "Check Population Cap before Spawning or Taming Recipe"
order: 16
published: true
draft: false
---
# Check Population Cap before Spawning or Taming Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: query Tamework's effective ownership cap before your plugin allows a tame/spawn flow.

## Pattern
```java
PopulationCapDecisionView cap = api.policies().evaluatePopulationCap(ownerUuid);
if (!cap.allowed()) {
    chat.send(ownerUuid, "Cap reached: " + cap.currentCount() + "/" + cap.limit());
    return;
}

startSpawnOrTameFlow(ownerUuid);
```

## Notes
- `capEnabled()` tells you whether cap policy is active.
- `remainingHeadroom()` is useful for UI and preflight checks.

## Related Pages
- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)

