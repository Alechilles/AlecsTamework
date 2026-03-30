---
title: "Pause Companion Jobs on Death or Lost Event Recipe"
order: 13
published: true
draft: false
---
# Pause Companion Jobs on Death or Lost Event Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: pause your plugin's job/task system when companions die or become lost.

## Pattern
```java
private AutoCloseable deathSubscription;
private AutoCloseable lostSubscription;

public void start(TameworkApi api) {
    deathSubscription = api.events().subscribe(NpcDeathRecordedEvent.class, event -> {
        String profileId = event.profile() != null ? event.profile().profileId() : null;
        if (profileId != null) {
            jobs.pause(profileId, "npc-death");
        }
    });

    lostSubscription = api.events().subscribe(NpcLostRecordedEvent.class, event -> {
        String profileId = event.profile() != null ? event.profile().profileId() : null;
        if (profileId != null) {
            jobs.pause(profileId, "npc-lost");
        }
    });
}
```

## Notes
- Keep your own resume policy explicit (manual resume, timer, or explicit event).
- You can also use `event.homePosition()` and `event.lastKnownPosition()` for diagnostics.

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)

