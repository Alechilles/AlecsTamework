---
title: "Check the Owner Cap before Taming"
order: 8
published: true
draft: false
---
# Check the Owner Cap before Taming

Use the version-two policy preflight for early UI feedback with explicit world
and requested-slot context:

```java
OwnerPopulationCapDecisionViewV2 decision =
        api.policies().evaluatePopulationCap(
                new OwnerPopulationCapRequestV2(ownerUuid, worldName, 1)
        );
if (!decision.allowed()) {
    // Tell the player before they spend food or an item.
}
```

The result counts every companion the owner has in that scope, loaded or not.
It is informational:

- It reserves nothing. API 3.0.0 has no reservation tokens;
  `policies().populationAdmissions()` was removed.
- The request names no role, so population-group limits and admission
  providers are not part of the answer.
- Another change by the same owner can use the slot before yours does.

Tamework runs the binding check itself, in the step that saves the companion,
for every tame, spawn, capture, restore, and bonded provision. Do not wrap
those flows in a check of your own and do not write companion files directly.
Handle the refusal instead: the player sees Tamework's limit message and
nothing changes.

For a group limit, read
`api.populationGroups().getCounts(ownerUuid, groupId, worldName)` and compare
`committedOwned` with `maxOwned` (`0` means no limit).

To add your own rule to the binding check, register an
[admission provider](/mod/alecs-tamework/admission-providers-api-reference).

## Related Pages
- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)
- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
