---
title: "Force Breeding Ready from Custom Ritual Recipe"
order: 5
published: true
draft: false
---
# Force Breeding Ready from Custom Ritual Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: your plugin consumes an item/ritual and marks a companion as breeding-ready.

## Pattern
```java
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;

Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

ProgressionMutationResult result = api.progression().setBreedingReady(profileId.get(), true);
if (result.status() == ProgressionMutationStatus.UNSUPPORTED) {
    // this mob/role does not have breeding progression enabled
}
```

## Notes
- This only affects mobs with breeding progression enabled.
- Keep your plugin-side cost/cooldown checks before calling this mutation.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)

