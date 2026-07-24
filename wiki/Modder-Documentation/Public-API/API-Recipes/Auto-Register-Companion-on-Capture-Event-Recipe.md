---
title: "Auto-Register Companion on Capture Event Recipe"
order: 12
published: true
draft: false
---
# Auto-Register Companion on Capture Event Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: when Tamework records a capture, automatically create your plugin-side companion record.

## Pattern
```java
private AutoCloseable captureSubscription;

public void start(TameworkApi api) {
    captureSubscription = api.events().subscribe(NpcCapturedEvent.class, event -> {
        String profileId = event.profile() != null ? event.profile().profileId() : null;
        if (profileId == null || profileId.isBlank()) {
            return;
        }

        companionRegistry.register(
                profileId,
                event.npcUuid(),
                event.ownerUuid(),
                event.displayName()
        );
    });
}
```

## Notes
- `event.profile()` is nullable for API safety. If it is absent, defer the
  registration or resolve the profile again; do not promote the live
  `npcUuid` to a durable cross-mod identity.
- Close the subscription during plugin shutdown.

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)



