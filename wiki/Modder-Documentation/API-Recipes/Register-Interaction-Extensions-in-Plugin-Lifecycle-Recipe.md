---
title: "Register Interaction Extensions in Plugin Lifecycle Recipe"
order: 17
published: true
draft: false
---
# Register Interaction Extensions in Plugin Lifecycle Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: register custom interaction requirement/effect handlers when your plugin starts and always clean them up on shutdown.

## Pattern
```java
private final List<AutoCloseable> extensionHandles = new ArrayList<>();

public void onEnable(TameworkApi api) {
    extensionHandles.add(api.interactionExtensions().registerRequirement("example:has_badge", (ctx, spec) -> {
        return playerBadgeService.hasBadge(ctx.playerUuid(), "companion_master");
    }));

    extensionHandles.add(api.interactionExtensions().registerEffect("example:grant_buff", (ctx, spec) -> {
        return buffService.applyCompanionBuff(ctx.npcRef(), ctx.playerUuid(), "bonded");
    }));
}

public void onDisable() {
    for (AutoCloseable handle : extensionHandles) {
        try {
            handle.close();
        } catch (Exception ignored) {
        }
    }
    extensionHandles.clear();
}
```

## Notes
- IDs are normalized to lowercase and must be nonblank.
- Use one local handle list per plugin/module to avoid leaks.

## Related Pages
- [Interaction Extensions API Reference](/mod/alecs-tamework/interaction-extensions-api-reference)

