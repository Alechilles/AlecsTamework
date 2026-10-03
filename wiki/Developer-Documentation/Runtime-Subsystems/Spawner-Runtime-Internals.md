---
title: "Spawner Runtime Internals"
order: 7
published: true
draft: false
---
# Spawner Runtime Internals

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Main orchestrator
`SpawnerFeatureHandler`

## Service split
- Policy and validation: `SpawnerCapturePolicyService`, `SpawnerRolePolicyService`, `SpawnerOwnershipPolicyService`
- Metadata and identity: `SpawnerCaptureMetadataService`, `SpawnerNpcIdentityService`, `SpawnerNpcStateService`, `SpawnerItemStackMetadataService`
- Placement and effects: `SpawnerSpawnPositionService`, `SpawnerEffectService`, `SpawnerPlayerInventoryService`
- Release feedback: `SpawnerReleaseIntentFactory`
- Companion capture and release: `CaptureFlow` and `HytaleCaptureDelivery`
  (capture), `RestoreFlow` (release from an item), and `CaptureItemOwnership`
  (who owns a companion in an item, per the server's ownership mode)
- Attachment and progression carryover: `SpawnerAttachmentService`, `SpawnerNpcProgressionMetadataService`

## Design intent
Spawner behavior is not a single monolithic capture method. Each collaborator owns one part of the policy or state transfer so capture, spawn, tooltip, and link-sync changes stay isolated.

## Important side effects
- Captured Tamework names and progression data travel through item metadata
- Capture takes the full-entity snapshot on the body's world thread, then
  `CaptureFlow` commits the record as `ITEM` with a new generation and waits for
  the owner file to be written. Only then does `HytaleCaptureDelivery` remove the
  body and hand over the item. A failed write leaves the body in the world.
- The filled item carries only the profile ID, the generation and small
  presentation keys for its tooltip, such as the owner line. The snapshot stays in the
  companion store. Profile identity and tool links survive capture and release.
- Release checks that the record is `ITEM` with the item's generation, commits
  `LIVE` with a new generation, then spawns from the snapshot and consumes the
  item. Any other copy of the item becomes an empty capture item.
- A 4.x item releases once at generation 0. A 2.x item that was never rewritten
  is adopted from its own data on first release (`LegacyItemAdoption`).
- A supported managed-coop interaction can take an eligible filled item directly
  into a coop slot; the item is consumed only after the `COOP` record is written.
  Other coops continue to accept live NPCs through their ordinary intake path.
- Capture and release feedback carries only stable IDs and an effect position
  across threads.
- Tooltip bridges may need invalidation on config reload

## Related Pages
- [Companion Store and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)



