---
title: "Keep Companion Cache in Sync with Profile Changed Events Recipe"
order: 14
published: true
draft: false
---
# Keep Companion Cache in Sync with Profile Changed Events Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

Goal: keep a plugin-side cache fresh without polling profile data.

## Pattern
```java
private final Map<String, NpcProfileView> cacheByProfileId = new ConcurrentHashMap<>();

private AutoCloseable profileChangedSubscription;

public void start(TameworkApi api) {
    profileChangedSubscription = api.events().subscribe(NpcProfileChangedEvent.class, event -> {
        NpcProfileView after = event.after();
        if (after == null) {
            cacheByProfileId.remove(event.profileId());
            return;
        }
        cacheByProfileId.put(event.profileId(), after);
    });
}
```

## Notes
- `before` and `after` are immutable snapshots; keep whichever side your cache model needs.
- `changeTypes` can drive selective updates (for example only re-render UI on name/owner changes).

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)


