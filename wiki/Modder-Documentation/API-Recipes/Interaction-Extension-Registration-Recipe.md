---
title: "Interaction Extension Registration Recipe"
order: 7
published: true
draft: false
---
# Interaction Extension Registration Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: register custom requirement/effect handlers and a preset, then unregister cleanly.

## Pattern
```java
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import java.util.ArrayList;
import java.util.List;

List<AutoCloseable> handles = new ArrayList<>();

handles.add(api.interactionExtensions().registerRequirement("example:is_owner", (ctx, spec) ->
        ctx.playerUuid() != null && spec.values().contains(ctx.playerUuid().toString())));

handles.add(api.interactionExtensions().registerEffect("example:mark", (ctx, spec) -> true));

InteractionPresetDefinition preset = new InteractionPresetDefinition(
        "example:owner_mark",
        List.of(new InteractionRequirementSpec("example:is_owner", null, List.of(), null)),
        List.of(new InteractionEffectSpec("example:mark", null, List.of(), null))
);
handles.add(api.interactionExtensions().registerPreset(preset));

// on shutdown:
for (AutoCloseable handle : handles) {
    try {
        handle.close();
    } catch (Exception ignored) {
    }
}
```

## Notes
- IDs must be nonblank and are normalized to lowercase.
- Closing the `AutoCloseable` unregisters that registration.
- Re-registering the same ID replaces the previous value.

## Related Pages
- [Interaction Extensions API Reference](/mod/alecs-tamework/interaction-extensions-api-reference)

