---
title: "API Bootstrap and Capability Checks Recipe"
order: 20
published: true
draft: false
---
# API Bootstrap and Capability Checks Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: safely acquire `TameworkApi`, verify version/capabilities, and fail closed when unavailable.

## Pattern
```java
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.EnumSet;

Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin != null ? plugin.getApi() : null;
if (api == null) {
    return; // Tamework not loaded or API unavailable
}

if (!"0.5.0".equals(api.getApiVersion())) {
    // Optional: warn, then continue with capability-based gating
}

EnumSet<TameworkApiCapability> caps = api.getCapabilities();
if (!caps.contains(TameworkApiCapability.COMMAND_LINKS)) {
    return;
}
```

## Recommendations
- Always null-check both `Tamework.getInstance()` and `getApi()`.
- Gate each feature by capability instead of only version equality.
- Keep integration behavior optional; do not crash if Tamework is missing.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)


