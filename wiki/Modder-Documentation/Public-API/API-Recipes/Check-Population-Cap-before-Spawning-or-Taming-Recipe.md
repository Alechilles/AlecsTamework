---
title: "Check the Live Owner Cap before Taming"
order: 8
published: true
draft: false
---
# Check the Live Owner Cap before Taming

Use the policy preflight for early UI feedback:

```java
PopulationCapDecisionView decision =
        api.policies().evaluatePopulationCap(ownerUuid);
```

The result describes the current live owner-cap decision. It does not reserve a
slot. Run the actual tame or ownership operation through Tamework so the live
cap is checked again at mutation time.

Do not create a separate reservation or write persistence rows directly.
