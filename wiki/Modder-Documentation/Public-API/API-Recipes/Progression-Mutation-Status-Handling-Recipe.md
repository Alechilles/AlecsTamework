---
title: "Progression Mutation Status Handling Recipe"
order: 22
published: true
draft: false
---
# Progression Mutation Status Handling Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: call progression mutations and handle result statuses deterministically.

## Pattern
```java
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;

ProgressionMutationResult result = api.progression().setHappiness(profileId, 80.0);

switch (result.status()) {
    case APPLIED -> {
        // success, optional post-state in result.progression()
    }
    case NOT_LOADED -> {
        // profile exists but NPC is not loaded
    }
    case UNSUPPORTED -> {
        // target NPC does not have this progression system active
    }
    case INVALID_ARGUMENT -> {
        // bad input payload
    }
    case NOT_FOUND, ERROR -> {
        // profile missing or unexpected runtime failure
    }
}
```

## Status Guidance
- `APPLIED`: mutation accepted.
- `NOT_FOUND`: target profile/NPC was not resolved.
- `NOT_LOADED`: target profile exists but no live NPC was resolved.
- `INVALID_ARGUMENT`: input failed validation.
- `UNSUPPORTED`: system not present for this target.
- `ERROR`: unexpected runtime failure.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)


