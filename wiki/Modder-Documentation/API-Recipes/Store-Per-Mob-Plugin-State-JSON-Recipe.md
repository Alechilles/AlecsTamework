---
title: "Store Per-Mob Plugin State JSON Recipe"
order: 11
published: true
draft: false
---
# Store Per-Mob Plugin State JSON Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: keep plugin-specific state (cooldowns, affinity, quest flags, etc.) per companion profile.

## Write
```java
String namespace = "example.plugin";
String key = "companion_state";
String payload = "{\"schema\":1,\"affinity\":42,\"lastRewardAtMs\":1730500000000}";

boolean ok = api.profileData().put(profileId, namespace, key, payload);
if (!ok) {
    // invalid args, reserved namespace, invalid JSON, or queue rejection
}
```

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
- `Alechilles:Tamework` is reserved.
- `namespace` and `key` must be nonblank.
- Payload must be JSON text.

## Related Pages
- [Profile Data API Reference](/mod/alecs-tamework/profile-data-api-reference)
