---
title: "Apply Attachment Preset from Custom UI Recipe"
order: 7
published: true
draft: false
---
# Apply Attachment Preset from Custom UI Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: let players pick a preset in your UI and apply it to Tamework companion attachments.

## Pattern
```java
import java.util.Map;
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;

Map<String, String> preset = Map.of(
        "Collar", "Collar_Red",
        "Harness", "Harness_Leather"
);

ProgressionMutationResult apply = api.progression().setStoredAttachments(profileId, preset);
if (apply.status() != ProgressionMutationStatus.APPLIED) {
    return;
}

api.progression().syncStoredAttachments(profileId);
```

## Notes
- Presets are plugin-defined maps from slot id to attachment id.
- `syncStoredAttachments(...)` is useful when you want an explicit resync after updates.

## Related Pages
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)

