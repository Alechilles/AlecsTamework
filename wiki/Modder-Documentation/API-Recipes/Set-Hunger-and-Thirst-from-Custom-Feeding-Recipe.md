---
title: "Set Hunger and Thirst from Custom Feeding Recipe"
order: 4
published: true
draft: false
---
# Set Hunger and Thirst from Custom Feeding Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: map your custom food/water mechanic into Tamework needs values.

## Pattern
```java
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;

Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

double newHunger = 82.0;
double newThirst = 70.0;

ProgressionMutationResult result = api.progression().setNeeds(profileId.get(), newHunger, newThirst);
if (result.status() != ProgressionMutationStatus.APPLIED) {
    return;
}
```

## Notes
- Pass `null` for hunger or thirst when you only want to change one side.
- Use this when your plugin owns the desired final values; use deltas when your plugin owns increments.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)

