---
title: "Spawner Runtime Internals"
order: 7
published: true
draft: false
---
# Spawner Runtime Internals

Parent: [Developer Documentation Index](/mod/alecs-tamework/developer-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

## Main orchestrator
`SpawnerFeatureHandler`

## Service split
- Policy and validation: `SpawnerCapturePolicyService`, `SpawnerRolePolicyService`, `SpawnerOwnershipPolicyService`
- Metadata and identity: `SpawnerCaptureMetadataService`, `SpawnerNpcIdentityService`, `SpawnerNpcStateService`, `SpawnerItemStackMetadataService`
- Placement and effects: `SpawnerSpawnPositionService`, `SpawnerEffectService`, `SpawnerPlayerInventoryService`
- Capture finalization and link sync: `SpawnerCaptureFinalizerService`, `SpawnerLinkedNpcSyncService`
- Attachment and progression carryover: `SpawnerAttachmentService`, `SpawnerNpcProgressionMetadataService`

## Design intent
Spawner behavior is not a single monolithic capture method. Each collaborator owns one part of the policy or state transfer so capture, spawn, tooltip, and link-sync changes stay isolated.

## Important side effects
- Captured Tamework names and progression data travel through item metadata
- Command-linked companion records can be synchronized during capture flows
- Tooltip bridges may need invalidation on config reload

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)
