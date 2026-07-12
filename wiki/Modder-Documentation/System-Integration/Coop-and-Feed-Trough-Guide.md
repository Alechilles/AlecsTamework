---
title: "Coop and Feed Trough Guide"
order: 12
published: true
draft: false
---
# Coop and Feed Trough Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Tamework can manage certain coop behaviors and can also participate in feed-trough hydration or refill flows.

## Managed coops
- Config asset: `TwCoopConfig`
- Keyed by `CoopId`
- Used when you want resident continuity, capture and release policy, or coop-specific produce and lifecycle rules
- An enabled/configured coop is wholly Tamework-authoritative for occupancy and lifecycle; an unmanaged coop remains wholly vanilla.
- The matching vanilla `FarmingCoopAsset` must explicitly disable `CaptureWildNPCsInRange`; Tamework checks the exact coop id and flag before it accepts authority.
- Do not add a vanilla-resident observer or second resident ledger beside a managed coop. The current model intentionally avoids the old v2.5 hybrid that caused projection remaps and state drift.
- Existing vanilla residents are imported through a durable audit journal. Exact matches move into managed slots without a replacement spawn; ambiguous evidence is quarantined and visible through `/tw coop import-status`.
- Stable profile identity survives projection UUID changes, so command links should follow the profile rather than assuming one permanent entity UUID.

## Feed-trough support
- Enabled through feature wiring and, optionally, `TwGlobalConfig.AssetSets.FeedTrough`
- Needs runtime can consume trough food or water support instead of only hand-fed resources
- Water support includes staged water variants and bucket refill mappings

## Implementation advice
- Keep coop behavior scoped to the exact coop ids you intend to hand over to Tamework
- Keep vanilla automatic wild intake disabled on those exact base coop assets; use the Tamework lifecycle setting for managed automatic capture.
- Treat trough support as part of the needs ecosystem rather than a separate isolated feature
- Verify that the target item, particle, and resource assets exist before enabling an asset set gate
- Leave `IdentityRules.PreserveUUID` omitted or `false`; `true` invalidates an enabled managed overlay.
- Use `/tw coop audit` and `/tw debugdb integrity` after enabling management on an established save.

## Related Pages
- [TwCoopConfig Reference](/mod/alecs-tamework/twcoopconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)



