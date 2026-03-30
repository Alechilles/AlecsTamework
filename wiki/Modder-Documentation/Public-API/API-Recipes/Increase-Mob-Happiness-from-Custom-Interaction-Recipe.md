---
title: "Increase Mob Happiness from Custom Interaction Recipe"
order: 2
published: true
draft: false
---
# Increase Mob Happiness from Custom Interaction Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: your plugin runs a custom interaction/event and rewards the companion with happiness.

## Pattern
```java
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;

Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

ProgressionMutationResult result = api.progression().applyHappinessDelta(profileId.get(), 8.0);
if (result.status() == ProgressionMutationStatus.APPLIED) {
    // optional: result.progression().happiness() for updated UI
}
```

## Notes
- `applyHappinessDelta(...)` is usually better for reward events than hard-setting a value.
- Handle `NOT_LOADED` and `UNSUPPORTED` as expected states, not hard failures.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)



