---
title: "Profile Data JSON Storage Recipe"
order: 4
published: true
draft: false
---
# Profile Data JSON Storage Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: store and retrieve plugin-specific JSON data scoped to a Tamework profile.

## Write
```java
String namespace = "example.plugin";
String key = "state";
String payload = "{\"mode\":\"follow\",\"priority\":2}";

boolean ok = api.profileData().put(profileId, namespace, key, payload);
if (!ok) {
    // invalid args, reserved namespace, invalid JSON, or write rejection
}
```

## Read
```java
String namespace = "example.plugin";
String key = "state";

String json = api.profileData()
        .get(profileId, namespace, key)
        .orElse("{}");
```

## List + Delete
```java
Map<String, String> all = api.profileData().list(profileId, "example.plugin");
boolean deleted = api.profileData().delete(profileId, "example.plugin", "state");
```

## Rules
- Use your own plugin id for `namespace`.
- `Alechilles:Tamework` is reserved.
- `namespace` and `key` must be nonblank.
- Payload must be JSON text.

## Related Pages
- [Profile Data API Reference](/mod/alecs-tamework/profile-data-api-reference)

