---
title: "Reroll Traits and Show Values Recipe"
order: 6
published: true
draft: false
---
# Reroll Traits and Show Values Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: trigger a trait reroll from your own feature and show players the new values.

## Pattern
```java
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;
import com.alechilles.alecstamework.api.ProgressionView;

ProgressionMutationResult result = api.progression().rerollTraits(profileId);
if (result.status() != ProgressionMutationStatus.APPLIED || result.progression() == null) {
    return;
}

ProgressionView.TraitsView traits = result.progression().traits();
if (traits == null) {
    return;
}

for (ProgressionView.TraitValueView value : traits.values()) {
    chat.send(playerUuid, value.id() + ": " + value.value());
}
```

## Notes
- Use the returned progression snapshot for immediate UI feedback.
- If you need deterministic values, use `setTraits(...)` instead of `rerollTraits(...)`.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)

