---
title: "Check Population Cap before Spawning or Taming Recipe"
order: 16
published: true
draft: false
---
# Check Population Cap before Spawning or Taming Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: reserve Tamework owner and destination-claim capacity before your plugin creates, tames, restores, or explicitly places a companion.

## Informational Preflight

Use the world-aware V2 query for UI or early feedback:

```java
OwnerPopulationCapDecisionViewV2 cap = api.policies().evaluatePopulationCap(
        new OwnerPopulationCapRequestV2(ownerUuid, worldName, 1)
);
if (!cap.allowed() || !cap.authoritative()) {
    chat.send(ownerUuid, "Population unavailable: " + cap.reason());
    return;
}
```

This is owner-only and does not reserve capacity. Do not use it as the final authorization for a mutation.

## Mutation-Bound Pattern

Prepare an identity- and location-complete request before touching the world:

```java
PopulationAdmissionRequest request = new PopulationAdmissionRequest(
        new PopulationAdmissionIdentity(null, provisionalProfileId, idempotencyKey),
        plannedNpcUuid,
        PopulationAdmissionRequest.NEW_PROFILE_REVISION,
        null,
        ownerUuid,
        null,
        new PopulationAdmissionLocation(worldName, chunkX, chunkZ),
        PopulationAdmissionOperation.NEW_OWNERSHIP,
        1,
        PopulationAdmissionForcePolicy.ENFORCE,
        PopulationCompanionLifecycle.ACTIVE
);

api.policies().populationAdmissions().tryAdmit(request)
        .thenAccept(prepared -> {
            if (prepared.status() != PopulationAdmissionDecision.Status.RESERVED) {
                chat.send(ownerUuid, "Cannot create companion: " + prepared.reason());
                return;
            }

            PopulationAdmissionToken token = prepared.token();
            scheduleOnDestinationWorldThread(() -> {
                PopulationAdmissionDecision applying =
                        api.policies().populationAdmissions().claimForApply(token);
                if (applying.status() != PopulationAdmissionDecision.Status.APPLYING) {
                    api.policies().populationAdmissions().cancel(token);
                    return;
                }

                boolean created = spawnOrTameWithThisExactProfileIdentity();
                if (created) {
                    api.policies().populationAdmissions().commit(token);
                } else {
                    api.policies().populationAdmissions().cancel(token);
                }
            });
        });
```

The persistence stages are asynchronous. Never join their futures on a world thread. Claim immediately before the live mutation, commit only after the mutation is confirmed, and cancel every unused token.

## Notes
- Use a canonical profile ID plus its expected revision for existing companions; use a stable provisional ID/idempotency key for a new one.
- Transfers must describe both owners and source/destination; Tamework reserves the destination before releasing the source.
- `ACTIVE`/`UNLOADED` destinations participate in claim occupancy. Restoring captured, cooped, dead, or lost state to `ACTIVE` is a positive claim admission even at the prior location.
- Use batch `EXACT` or `UP_TO` admissions for explicit breeding children; do not represent several children with `exactSlots > 1`.
- A denied/degraded/unavailable decision is fail-closed. Do not bypass it by spawning and updating persistence afterward.

## Related Pages
- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)
- [Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)



