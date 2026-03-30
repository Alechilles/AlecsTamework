---
title: "Auto-Register Companion on Capture Event Recipe"
order: 12
published: true
draft: false
---
# Auto-Register Companion on Capture Event Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

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
- `event.profile()` can be `null`; use `npcUuid` as a fallback key if needed.
- Close the subscription during plugin shutdown.

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)


