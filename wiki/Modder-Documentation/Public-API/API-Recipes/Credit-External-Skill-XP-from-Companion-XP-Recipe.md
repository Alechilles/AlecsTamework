---
title: "Credit External Skill XP from Companion XP Recipe"
order: 14
published: true
draft: false
---
# Credit External Skill XP from Companion XP Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: listen for successful companion XP awards and credit the companion owner's external skill system, such as an Animal Husbandry skill in another progression mod.

## Pattern
```java
import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.EnumSet;
import java.util.UUID;

private AutoCloseable companionXpSubscription;

public void start(TameworkApi api) {
    EnumSet<TameworkApiCapability> caps = api.getCapabilities();
    if (!caps.contains(TameworkApiCapability.EVENTS)
            || !caps.contains(TameworkApiCapability.COMPANION_XP_EVENTS)) {
        return;
    }

    companionXpSubscription = api.events().subscribe(CompanionXpAwardedEvent.class, event -> {
        UUID ownerUuid = event.ownerUuid();
        if (ownerUuid == null) {
            return;
        }

        double skillXp = scaleForExternalSkill(event.source(), event.awardedXp());
        if (!(skillXp > 0.0)) {
            return;
        }

        externalSkillApi.addXp(ownerUuid, "animal_husbandry", skillXp);
    });
}

public void stop() throws Exception {
    if (companionXpSubscription != null) {
        companionXpSubscription.close();
        companionXpSubscription = null;
    }
}

private double scaleForExternalSkill(CompanionXpSource source, double companionXp) {
    return switch (source) {
        case FEED, HARVEST, BREEDING -> companionXp;
        case COMBAT_DAMAGE_DEALT, COMBAT_DAMAGE_TAKEN -> companionXp * 0.25;
        case CUSTOM -> 0.0;
    };
}
```

## Notes
- `CompanionXpAwardedEvent` fires only after Tamework accepts the XP award and applies or queues the companion component write.
- `ownerUuid` is the player UUID to credit. If it is null, skip player skill credit.
- `toolIds` may be empty for unlinked companions; do not use command links as the creditability check.
- Automatic storage food, storage water, natural water, and manual feeding use the public `FEED` source bucket.
- Listener failures are isolated by Tamework, but integrations should still avoid heavy work inside the synchronous callback.

## Related Pages
- [Events API Reference](/mod/alecs-tamework/events-api-reference)
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Event Subscription Lifecycle Recipe](/mod/alecs-tamework/event-subscription-lifecycle-recipe)
