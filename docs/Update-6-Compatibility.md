# Update 6 Compatibility

Last reviewed: 2026-08-11

Branch: `update6`

## Supported baseline

Tamework compiles against Hytale `0.6.0-pre.11` for development compatibility.
The release manifest accepts stable server versions `>=0.5.0 <0.7.0`. It does
not advertise prerelease server builds.

The compatibility target is:

| Patch line | Version | Current evidence |
| --- | --- | --- |
| Update 5 | `0.5.7` | Compatibility adapters and dual NPC callback bases initialize on an isolated Update 5 classpath. |
| Update 6 | `0.6.0-pre.11` | Production compile, manifest validation, and the full Java test suite pass. |

Real server smoke tests are still required before release. The Update 5 probe loads
the compiled compatibility boundary. It does not prove full plugin setup or gameplay.
External Update 5 reflection that enumerates every method on a dual callback base can
also resolve the absent Update 6 `ExecutionSupport` type. The indexed Update 5 NPC
engine does not do this, but third-party instrumentation remains a live-load risk.

## Evidence reviewed

- The official [Update 6 prerelease patch notes](https://hytale.com/news/2026/5/pre-release-patch-notes-update-6),
  including Parts 1 through 11 available on 2026-08-11.
- Hytale Workshop indexes for `0.5.7` and `0.6.0-pre.11`.
- The Workshop code diff: 8,503 entities added, 793 removed, and 8,117 modified.
- The Workshop game-data diff: 566 assets added, 67 removed, and 1,628 modified.
- The Workshop client diff: 16 entities added, 1 removed, and 31 modified.
- Local compile and test results against the resolved `0.6.0-pre.11` Maven artifact.
- Isolated class-loading probes with the resolved `0.5.7` and `0.6.0-pre.11`
  server JARs.

The Workshop documentation corpus does not contain the patch-note page. Patch-note
intent comes from the official page. API findings come from the Workshop code diff
and the resolved server artifacts.

## Compatibility changes made

### NPC execution support

Update 6 removes the public support getters from `Role`. This includes state,
marked entity, world, entity, combat, position-cache, and debug support. Update 6
provides ECS support components and a pooled `ExecutionSupport` object instead.

Tamework now uses focused NPC access adapters and dual callback bases:

- Update 6 callbacks use their supplied `ExecutionSupport` or live entity
  reference and component accessor.
- Update 5 callbacks keep their `Role` overloads.
- Update 5-only methods are bound once with method handles.
- Pooled `ExecutionSupport` objects are cleared after each acquired use and are
  not cached across callbacks or threads.

The asset-facing action, sensor, filter, motion, and builder IDs did not change.

### NPC alarms, names, and marked targets

Update 6 moves engine alarms from `NPCEntity.getAlarmStore()` to the ECS
`AlarmStore` component. It also replaces `EntitySupport.setDisplayName()` with
`DisplayNameSupport` and changes the marked-target write signature.

Tamework now routes these operations through small adapters that select the Update
5 or Update 6 contract. Existing alarm values, target slot names, persistent NPC
names, runtime names, and nameplates keep their prior behavior.

### Builder parameter scope

Update 6 replaces `BuilderSupport.getParentSpawnable()` with `getRootBuilder()`.
Tamework resolves the matching method once for the active patch line and keeps its
existing null fallback.

### Chunk columns and sections

Update 6 stores entity and block references in chunk sections. Update 5 stores
them against chunk columns. Several old column helpers and
`TransformComponent.getChunkRef()` are absent in pre.11.

Tamework now:

- Resolves an Update 6 section to its owning chunk-column reference.
- Keeps the Update 5 direct chunk-column path.
- Uses `saveChunkColumn(...)` on Update 6 and the legacy holder save on Update 5.
- Marks the source `EntitySection` before an Update 6 relocation unload so the
  removed entity reference cannot return after restart.
- Uses stable world-position block-component lookup where both versions support it.
- Resolves block-state world positions through section or column metadata as
  required by the active version.

This keeps capture, release, timed summon, provisioning, revival, relocation, and
other persistence barriers tied to the correct current chunk column.

### Native mounts and movement profiles

In pre.11, `MountedComponent` changes its attachment offset from `Rotation3f` to a
JOML vector. Player movement also changes from a boolean `canFly` value to a
three-state `FlyMode`, and `MovementManager.setDefaultSettings(...)` now accepts a
`MovementConfig`.

Tamework now uses cached adapters for mount construction, attachment-offset reads,
flight permission, and default movement profiles. Update 5 keeps its rotation,
boolean flight, and packet-settings path.

### Relative look rotations

Update 5 exposes `Rotation3f.lookAt(Vector3d)`. Pre.11 replaces that descriptor
with `lookAt(Vector3dc)` and component-based overloads. A direct pre.11 call caused
a `NoSuchMethodError` on the Update 5 world thread when a captured companion was
released.

Tamework now binds the active method shape once and routes captured release,
companion placement, projection placement, capture VFX, breeding, and scarecrow
placement through the same compatibility helper.

The same Update 6 interface migration changed particle and spatial-query method
descriptors from `Vector3d` to `Vector3dc`. Tamework now routes vector-based
particle effects and spatial searches through cached compatibility bindings. Calls
that use the unchanged coordinate overloads stay direct.

Update 6 also adds `ModelParticle.setClearParticlesOnRemove(...)` and the
`CancelParticleSystems` packet. Active command indicators use the model-particle
cleanup flag on an invisible, non-persistent mounted helper and run only on
Update 6. Removing the helper clears its persistent client particle. Update 5
does not register or emit the indicator system because it cannot use that API
safely on a world thread.

### Build and manifest

The branch targets `0.6.0-pre.11` from the prerelease dependency line. The
generated release manifest uses `>=0.5.0 <0.7.0` instead of the old `0.5.x`
range. Hytale's semver rules exclude prereleases from this range, as intended
for stable releases.

Update 6 also changes `DropdownEntryInfo.CODEC` from `BuilderCodec` to
`RecordCodec`. Tamework reads that public field through the stable `Codec`
interface so the same class can link on Update 5, where `RecordCodec` does not
exist.

## Verification completed

- `compileJava` against `0.6.0-pre.11`: passed.
- Manifest validation: passed.
- The manifest range accepts `0.5.7` with the engine's exact `SemverRange`
  implementation: passed.
- Full Java test suite: passed.
- Focused NPC callback, interaction, alarm, movement, and persistence tests: passed.
- Compatibility adapter and dual callback-base initialization with only Update 5
  engine classes: passed.
- Compatibility adapter and dual callback-base initialization with only Update 6
  engine classes: passed.
- Linked-NPC dropdown signature and cache reuse with the real Update 5 and Update 6
  codec implementations: passed.
- Relative look-rotation results with the exact Update 5 and Update 6 server JARs:
  passed.
- Particle and spatial compatibility bindings initialized with the exact Update 5
  and Update 6 server JARs: passed.
- The four production Patchwork vanilla targets for fence sets, container buckets,
  decorative buckets, and capture crates are present in the pre.11 game-data index.
- Tamework command gates use explicit `tamework.*` permission nodes, so the Update 6
  plugin-name space-to-underscore normalization does not change them.
- Removed API call scan: only adapter-owned Update 6 support getters and
  Tamework-owned metadata setters remain.
- ECS player-access safety scan: no new unsafe runtime access was introduced.

## Open prerelease compatibility gates

These items need live or asset-specific evidence before Update 6 prerelease
support is advertised. They do not block a stable-range release.

1. Load the packaged JAR on real `0.5.7` and `0.6.0-pre.11` servers.
2. Confirm NPC builder registration and one action, sensor, filter, and motion path
   on each version.
3. Smoke-test naming, command targets, capture and restore, breeding cooldowns,
   shoulder riding, mounted glide, Avatar Flight, and chunk durability.
4. Validate the feed-trough asset with exact Update 5 and Update 6 asset profiles.
   The patch notes remove authored `IsUsable` flags, while the current trough states
   still contain them.
5. Smoke-test Patchwork generation and application. The indexed production target
   files exist, but file presence does not prove that each patch operation still
   produces the intended effective asset.
6. Review changed mount, movement, interaction, and generated protocol behavior in
   a live client/server session.

The focused NPC support fixture injects support fields for unit isolation. It does
not prove the engine's pooled `ExecutionSupport` lifecycle; the callback smoke test
must cover that contract.

## Deprecation backlog

The pre.11 compiler reports APIs that still exist but are marked for removal. These
are follow-up migrations, not current Update 6 blockers:

- `Entity.getUuid()` to `UUIDComponent`.
- `Player.getPlayerRef()` and legacy player connection access to ECS components.
- Legacy display-name reads to `DisplayNameComponent`.
- Legacy inventory hotbar and item-in-hand access to `InventoryComponent` and
  `InventoryUtils`.
- Deprecated direct chunk rotation reads in feed-trough paths.

These migrations should use focused adapters or live ECS access. They should not be
mixed into the compatibility branch without a behavior reason.

## Improvement opportunities from Update 6

The following options can reduce Tamework overhead, but they need measured behavior
before adoption:

1. Use direct support-component access in hot NPC systems when only one support is
   needed. Use one pooled `ExecutionSupport` per callback when several are needed.
2. Evaluate native mount and flight movement before keeping duplicate velocity or
   input work on Update 6. Keep the Update 5 systems as a fallback.
3. Evaluate `World.scheduleAfter(...)` as a low-allocation replacement for selected
   delayed work through a patch-line adapter.
4. Evaluate `BlockOperations` for cubic-world-safe feed-trough and spawner changes.
5. Use typed `PluginManager.getPlugin(Class)` for stable Update 6 integrations while
   retaining identifier lookup on Update 5.
6. Review Wilderness ECS tracking as a possible replacement for selected home or
   distance scans.
7. Review CompanionBlockSpawner for base-game-first companion spawn flows. Do not
   use it until ownership, persistence, commands, and cleanup match Tamework's
   contracts.
8. Review new NPC actions and sensors before adding new Java behavior. Prefer an
   asset implementation when the base system can express the result reliably.

Do not replace Tamework's durable companion persistence with new engine resources
without a separate migration and recovery design. Autonomous aerial NPC behavior
also remains separate from rider-controlled mount and Avatar Flight behavior.
