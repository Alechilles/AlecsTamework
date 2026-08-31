---
title: "Coop and Feed Trough Guide"
order: 12
published: true
draft: false
---
# Coop and Feed Trough Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Tamework can add direct live-NPC behavior to configured coops and can also
participate in feed-trough hydration or refill flows.

## Configured coops

- Config asset: `TwCoopConfig`
- Keyed by `CoopId`
- Used for companion capture policy, resident capacity, roam/release timing,
  produce, and state continuity
- Tamework scans loaded configured coops, captures an eligible live NPC into a
  canonical coop slot, and later releases that resident as a live NPC.
- The managed capture-crate interaction and filled spawner interaction can
  submit an eligible canonical captured item directly to an available slot.
  The operation commits one resident and retires the exact source item; a
  denial or unavailable feature leaves the item unchanged.
- Coops without an enabled matching config retain their ordinary behavior.
- Stable profile identity survives the release UUID change, so command links
  follow the profile rather than assuming one permanent entity UUID.

Live and captured-item intake share the same coop operation and canonical
resident ledger. There is no second captured-item ledger, feature-specific
recovery journal, vanilla resident importer, or coop repair command surface.

## Feed-trough support
- Enabled through feature wiring and, optionally, `TwGlobalConfig.AssetSets.FeedTrough`
- Needs runtime can consume trough food or water support instead of only hand-fed resources
- Water support includes staged water variants and bucket refill mappings

## Implementation advice

- Keep Tamework behavior scoped to the exact coop IDs you intend to configure.
- Avoid a second integration that captures or releases the same residents.
- Treat trough support as part of the needs ecosystem rather than a separate isolated feature
- Verify that the target item, particle, and resource assets exist before enabling an asset set gate
- Leave `IdentityRules.PreserveUUID` omitted or `false`; a released live NPC may
  receive a new entity UUID while retaining its stable profile.
- Patch capture-crate behavior to
  `TameworkManagedCoopCaptureCrate` when the vanilla item should participate in
  canonical captured-item intake. Tamework's bundled capture-crate patch is the
  reference wiring.
- Use `/tw debugcoop` for coop-specific runtime logging and `/tw debug
  persistence status` for bounded replacement-persistence status.

## Related Pages
- [TwCoopConfig Reference](/mod/alecs-tamework/twcoopconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)



