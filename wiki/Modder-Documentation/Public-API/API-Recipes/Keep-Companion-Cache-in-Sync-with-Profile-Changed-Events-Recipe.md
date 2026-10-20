---
title: "Keep Companion Cache in Sync with Profile Changed Events Recipe"
order: 14
published: true
draft: false
---
# Keep Companion Cache in Sync with Profile Changed Events Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

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
- `after` is null for a released or culled companion. `changeTypes` then
  contains `RELEASED`.
- `changeTypes` can drive selective updates (for example only re-render UI on name/owner changes).
- `LOCATION` with `oldLocationKind` and `newLocationKind` tells you where the
  companion went, for example `LIVE` to `STORED`.
- The listener runs on the thread that made the change, so use a thread-safe
  cache, as above. Events from different threads have no guaranteed order.
  When order matters, read the profile again with `profiles().getByProfileId`.
- A change that is undone is followed by a second event for the compensating
  change.

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)



