---
title: "Check Command Link State before Running Feature Recipe"
order: 9
published: true
draft: false
---
# Check Command Link State before Running Feature Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: guard your feature so it only runs when the mob is command-linked the way your plugin expects.

## Pattern
```java
Optional<CommandLinkView> linkOpt = api.commandLinks().getByProfileId(profileId);
if (linkOpt.isEmpty()) {
    return;
}

CommandLinkView link = linkOpt.get();
if (link.toolIds().isEmpty()) {
    return; // not linked to any command tool
}

if (!link.hasHomePosition()) {
    return; // your feature requires a saved home location
}

runFeature(profileId, link.homePosition());
```

## Notes
- For quick checks, `hasHomePosition(profileId)` and `listLinkedToolIds(profileId)` are available.
- Prefer profile-id targeting so UUID remaps do not break your guard logic.

## Related Pages
- [Command Links API Reference](/mod/alecs-tamework/command-links-api-reference)

