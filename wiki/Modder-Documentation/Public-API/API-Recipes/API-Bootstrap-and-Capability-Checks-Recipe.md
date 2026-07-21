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

if (!"0.9.0".equals(api.getApiVersion())) {
    // Optional: warn, then continue with capability-based gating
}

EnumSet<TameworkApiCapability> caps = api.getCapabilities();
if (!caps.contains(TameworkApiCapability.COMMAND_LINKS)) {
    return;
}

// API 0.9 types link against older implementations through fail-closed
// default methods. Require the capability before calling a new authority.
if (!caps.contains(TameworkApiCapability.COMPANION_PROVISIONING)
        || !caps.contains(TameworkApiCapability.POPULATION_GROUPS)) {
    return;
}

// Optional: ask the same read-only gate used by Tamework before beginning
// persistence-backed integration work.
if (caps.contains(TameworkApiCapability.PERSISTENCE_RESILIENCE)) {
    var state = api.diagnostics().getPersistenceResilience();
    if (!"HEALTHY".equals(state.storageState())) {
        return;
    }
}
```

## Recommendations
- Always null-check both `Tamework.getInstance()` and `getApi()`.
- Gate each feature by capability instead of only version equality.
- Treat an API 0.9 type/default method without its capability as deliberately
  unavailable; do not substitute local metadata or a private repository.
- Treat persistence availability as a denial-only preflight. It does not reserve capacity and a later authoritative admission may still reject the operation.
- Keep integration behavior optional; do not crash if Tamework is missing.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)


