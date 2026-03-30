---
title: "Build Companion Inspector UI Card Recipe"
order: 10
published: true
draft: false
---
# Build Companion Inspector UI Card Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: build one UI card by combining profile, command-link, and progression views.

## Pattern
```java
record CompanionInspectorCard(
        String profileId,
        String displayName,
        boolean tamed,
        boolean hasHome,
        Double happiness,
        Integer hungerPercent,
        Integer thirstPercent
) {}

Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

Optional<NpcProfileView> profile = api.profiles().getByProfileId(profileId.get());
Optional<CommandLinkView> links = api.commandLinks().getByProfileId(profileId.get());
Optional<ProgressionView> progression = api.progression().getByProfileId(profileId.get());

CompanionInspectorCard card = new CompanionInspectorCard(
        profileId.get(),
        profile.map(NpcProfileView::displayName).orElse("Unknown"),
        profile.map(NpcProfileView::tamed).orElse(false),
        links.map(CommandLinkView::hasHomePosition).orElse(false),
        progression.map(ProgressionView::happiness).map(ProgressionView.HappinessView::value).orElse(null),
        progression.map(ProgressionView::needs).map(ProgressionView.NeedsView::hungerPercent).orElse(null),
        progression.map(ProgressionView::needs).map(ProgressionView.NeedsView::thirstPercent).orElse(null)
);
```

## Notes
- Keep each API call optional and render partial cards when one family is unavailable.
- This pattern is also useful for debug overlays and admin dashboards.

## Related Pages
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)
- [Command Links API Reference](/mod/alecs-tamework/command-links-api-reference)
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)

