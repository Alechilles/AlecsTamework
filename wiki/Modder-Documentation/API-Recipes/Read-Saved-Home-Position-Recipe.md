---
title: "Read Saved Home Position Recipe"
order: 3
published: true
draft: false
---
# Read Saved Home Position Recipe

Parent: [API Recipes Index](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Goal: read the stored home position for a linked NPC from its stable profile id.

## Pattern
```java
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.Vector3View;
import java.util.Optional;
import java.util.UUID;

Optional<String> profileId = api.profiles().resolveProfileId(npcUuid);
if (profileId.isEmpty()) {
    return;
}

CommandLinksApi links = api.commandLinks();
Optional<Vector3View> home = links.getHomePosition(profileId.get());
if (home.isEmpty()) {
    return;
}

Vector3View position = home.get();
double x = position.x();
double y = position.y();
double z = position.z();
```

## Notes
- `hasHomePosition(profileId)` is a cheaper boolean check when coordinates are not needed.
- `getHomePosition(...)` is best-effort and uses live + cached + persisted link state.

## Related Pages
- [Command Links API Reference](/mod/alecs-tamework/command-links-api-reference)
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)

