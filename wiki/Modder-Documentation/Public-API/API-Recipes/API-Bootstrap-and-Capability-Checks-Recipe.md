---
title: "API Bootstrap and Capability Checks"
order: 1
published: true
draft: false
---
# API Bootstrap and Capability Checks

Resolve `TameworkApi`, then test every capability needed by your feature.

```java
Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin == null ? null : plugin.getApi();
if (api == null) {
    return;
}

EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
if (!capabilities.contains(TameworkApiCapability.PROFILE_DATA_TRANSACTIONS)) {
    return;
}
```

`getApiVersion()` is useful for logs and compatibility diagnostics, but it is
not a capability check. Do not infer an optional feature from the Tamework
version or from DTO classes being present.

For durable integration state, resolve a canonical ID through `profiles()` and
store namespaced data through `profileData()`. Never write Tamework persistence
directly.
