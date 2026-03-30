---
title: "Decrease Mob Happiness from Negative Event Recipe"
order: 3
published: true
draft: false
---
# Decrease Mob Happiness from Negative Event Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: your plugin detects a negative event (storm hit, punishment, failed task) and reduces companion happiness.

## Pattern
```java
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;

Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

ProgressionMutationResult result = api.progression().applyHappinessDelta(profileId.get(), -12.5);
if (result.status() == ProgressionMutationStatus.NOT_LOADED) {
    // queue your own retry, or skip until the mob is loaded again
}
```

## Notes
- Use bounded negative deltas so penalties are predictable.
- Keep your penalty values centralized in your plugin config.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)

