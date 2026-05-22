---
title: "Register Custom Trait Effect Key Recipe"
order: 18
published: true
draft: false
---
# Register Custom Trait Effect Key Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: register a custom trait effect key and use that key from normal `TwTraitConfig` assets.

## Pattern
```java
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.EnumSet;

private AutoCloseable scalePatternHandle;

public void onEnable(TameworkApi api) {
    EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
    if (!capabilities.contains(TameworkApiCapability.TRAIT_EFFECTS)) {
        return;
    }

    scalePatternHandle = api.traitEffects().registerEffectKey("example.genetics:ScalePattern", context -> {
        double scalePatternValue = context.value();
        if (context.contributions().isEmpty()) {
            geneticsVisualService.clearScalePattern(context.npcRef(), context.store());
            return true;
        }

        geneticsVisualService.applyScalePattern(
                context.npcRef(),
                context.store(),
                context.npcUuid(),
                scalePatternValue
        );
        return true;
    });
}

public void onDisable() {
    if (scalePatternHandle != null) {
        try {
            scalePatternHandle.close();
        } catch (Exception ignored) {
        }
        scalePatternHandle = null;
    }
}
```

## Trait Config
Use the same key in normal trait config authoring:

```json
{
  "Id": "Traits_Example_Genetics",
  "RoleIds": [
    "Mob_Example"
  ],
  "Traits": [
    {
      "Id": "Trait_ScalePatternWide",
      "DisplayName": "Wide Scale Pattern",
      "EffectKey": "example.genetics:ScalePattern",
      "NaturalMin": 1.1,
      "NaturalMax": 1.3,
      "BreedingMin": 0.9,
      "BreedingMax": 1.5,
      "Default": 1.0
    }
  ]
}
```

## Notes
- Effect keys are case-insensitive and listed in normalized lowercase form.
- Handlers are called during Tamework's existing trait-effect resyncs, including spawn/bootstrap, world load, API trait mutation, debug trait commands, respawn/capture restore, leveling/talent resyncs, and breeding offspring initialization.
- Handlers must be idempotent. Tamework may call them repeatedly with the same value.
- Registered handlers add behavior for custom keys. They do not replace built-in Tamework handling for built-in keys.
- Unregistered custom keys remain inert even if they appear in `TwTraitConfig`.

## Related Pages
- [Trait Effects API Reference](/mod/alecs-tamework/trait-effects-api-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)
