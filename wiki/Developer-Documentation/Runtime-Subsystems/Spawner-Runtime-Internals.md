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
- Intent and live finalization: `SpawnerCaptureIntentFactory`,
  `SpawnerReleaseIntentFactory`, `SpawnerCaptureFinalizerService`
- Durable capture and release: `SpawnerCaptureAuthor`,
  `SpawnerCapturedArtifactReleaseAuthor`
- Attachment and progression carryover: `SpawnerAttachmentService`, `SpawnerNpcProgressionMetadataService`

## Design intent
Spawner behavior is not a single monolithic capture method. Each collaborator owns one part of the policy or state transfer so capture, spawn, tooltip, and link-sync changes stay isolated.

## Important side effects
- Captured Tamework names and progression data travel through item metadata
- Capture and release preserve canonical profile identity and tool-link state
  through the replacement full-state snapshot.
- A filled item is the exact source artifact for release. Coop intake accepts a
  live NPC instead and never consumes a filled spawner item.
- Tooltip bridges may need invalidation on config reload

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)



