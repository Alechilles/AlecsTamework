---
title: "Read Saved Home Position and Show a Waypoint Recipe"
order: 8
published: true
draft: false
---
# Read Saved Home Position and Show a Waypoint Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

Goal: read a linked mob's saved home position and pass it into your own waypoint/minimap marker.

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
waypointService.showWaypoint(
        playerUuid,
        "Companion Home",
        position.x(),
        position.y(),
        position.z()
);
```

## Notes
- `hasHomePosition(profileId)` is a cheaper boolean check when coordinates are not needed.
- `getHomePosition(...)` is best-effort and uses live + cached + persisted link state.

## Related Pages
- [Command Links API Reference](/mod/alecs-tamework/command-links-api-reference)
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)

