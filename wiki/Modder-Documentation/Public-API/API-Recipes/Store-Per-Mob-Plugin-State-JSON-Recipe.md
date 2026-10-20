---
title: "Store Per-Mob Plugin State JSON Recipe"
order: 11
published: true
draft: false
---
# Store Per-Mob Plugin State JSON Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: keep plugin-specific state (cooldowns, affinity, quest flags, etc.) per companion profile.

## Simple write
```java
String namespace = "example.plugin";
String key = "companion_state";
String payload = "{\"schema\":1,\"affinity\":42,\"lastRewardAtMs\":1730500000000}";

boolean ok = api.profileData().put(profileId, namespace, key, payload);
if (!ok) {
    // invalid args, reserved namespace, invalid JSON, or no such companion
}
```

`put(...)` changes the value in memory and returns before the file is
written. When this value coordinates item consumption, a cooldown, an
entitlement, or another durable effect, require `PROFILE_DATA_TRANSACTIONS`
and use `getVersioned(...)` plus `compareAndSet(...)`. Its stage completes
after the file is written, possibly on a thread that is not a world thread.

## Read
```java
String namespace = "example.plugin";
String key = "companion_state";

String json = api.profileData()
        .get(profileId, namespace, key)
        .orElse("{\"schema\":1}");
```

## List + Delete
```java
Map<String, String> all = api.profileData().list(profileId, "example.plugin");
boolean deleted = api.profileData().delete(profileId, "example.plugin", "companion_state");
```

## Rules
- Use your own plugin id for `namespace`.
- `tamework` and `Alechilles:Tamework` are reserved.
- `namespace` and `key` must be nonblank. A namespace may not contain `/`.
- Payload must be JSON text.
- Prefer `compareAndSet(...)` whenever an in-memory change is not a strong
  enough success result.

## Related Pages
- [Profile Data API Reference](/mod/alecs-tamework/profile-data-api-reference)


