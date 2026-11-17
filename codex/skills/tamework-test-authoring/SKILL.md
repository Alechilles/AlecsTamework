---
name: tamework-test-authoring
description: Use when a Tamework change has passed the test-value gate and needs a focused Java behavior regression, config parser or resolver test, ECS behavior test, bug reproduction, or narrow static guard for a documented high-impact invariant. Distinguishes Java tests from asset and live evidence.
---

# Tamework Test Authoring

- For an ECS system's real tick (query, command-buffer writes, Dirty marks), use
  `src/test/java/com/alechilles/alecstamework/testsupport/EcsFixture.java`: a
  real engine registry and store with no server. Its Javadoc lists what needs a
  live rig instead (World, chunks, assets, players, vanilla system groups).
- Resolve builder and type IDs from current registration; never encode a
  remembered inventory in a test.
- Java unit success is not live NPC behavior proof, and it does not replace
  asset validation (`hytale-asset-tools`).
- Report the tests changed, the behavior covered, and the command with its result.
