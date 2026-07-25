---
title: "Check and Reserve Owner Population before Taming"
order: 8
published: true
draft: false
---
# Check and Reserve Owner Population before Taming

Use the version-two policy preflight for early UI feedback with explicit world
and requested-slot context:

```java
OwnerPopulationCapDecisionViewV2 decision =
        api.policies().evaluatePopulationCap(
                new OwnerPopulationCapRequestV2(ownerUuid, worldName, 1)
        );
```

The result reads the durable canonical owner count, but remains informational.
For a custom mutation, construct the complete role-aware
`PopulationAdmissionRequestV2` and use
`api.policies().populationAdmissions()`:

1. `tryAdmitV2(request)`;
2. immediately before live mutation, `claimForApply(token)`;
3. after successful live mutation, `commit(token)`;
4. on any pre-commit abort, `cancel(token)`.

Complete or cancel every token. Ordinary Tamework tame/spawn/capture flows
already perform this protocol; do not wrap them in a second reservation and do
not write persistence rows directly.
