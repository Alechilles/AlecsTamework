---
title: "Coop and Feed Trough Guide"
order: 12
published: true
draft: false
---
# Coop and Feed Trough Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

Tamework can manage certain coop behaviors and can also participate in feed-trough hydration or refill flows.

## Managed coops
- Config asset: `TwCoopConfig`
- Keyed by `CoopId`
- Used when you want resident continuity, capture and release policy, or coop-specific produce and lifecycle rules

## Feed-trough support
- Enabled through feature wiring and, optionally, `TwGlobalConfig.AssetSets.FeedTrough`
- Needs runtime can consume trough food or water support instead of only hand-fed resources
- Water support includes staged water variants and bucket refill mappings

## Implementation advice
- Keep coop behavior scoped to the exact coop ids you intend to hand over to Tamework
- Treat trough support as part of the needs ecosystem rather than a separate isolated feature
- Verify that the target item, particle, and resource assets exist before enabling an asset set gate

## Related Pages
- [TwCoopConfig Reference](/mod/alecs-tamework/twcoopconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)


