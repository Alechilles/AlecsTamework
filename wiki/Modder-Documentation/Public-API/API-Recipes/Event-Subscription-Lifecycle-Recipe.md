---
title: "Event Subscription Lifecycle Recipe"
order: 21
published: true
draft: false
---
# Event Subscription Lifecycle Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

Goal: subscribe to Tamework events and cleanly unsubscribe to avoid listener leaks.

## Pattern
```java
import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import java.util.ArrayList;
import java.util.List;

private final List<AutoCloseable> subscriptions = new ArrayList<>();

public void start(TameworkApi api) {
    subscriptions.add(api.events().subscribe(NpcProfileChangedEvent.class, event -> {
        // handle immutable event snapshot
    }));
}

public void stop() {
    for (AutoCloseable subscription : subscriptions) {
        try {
            subscription.close();
        } catch (Exception ignored) {
        }
    }
    subscriptions.clear();
}
```

## Notes
- Event callbacks run synchronously on Tamework's emit thread.
- Listener exceptions are isolated by Tamework and logged.
- Always close subscriptions on plugin disable/unload.

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)

