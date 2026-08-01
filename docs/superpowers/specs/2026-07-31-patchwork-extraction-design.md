# Patchwork Extraction Design

**Date:** 2026-07-31

**Status:** Approved design, pending written-spec review

**Source baseline:** Alec's Tamework commit `5f5e6da68ab9690f9b5470e6be5c1254095df23b`

## Summary

Extract Tamework's asset patcher into Patchwork, a standalone Hytale plugin and
embeddable Java runtime. Patchwork preserves the current patch language and
legacy Tamework patch location while establishing a neutral public identity,
generic JSON-backed conditions, one elected runtime per server process, and a
host-extension boundary for Tamework-specific behavior.

Patchwork follows Alec's Telemetry's coordinator hierarchy:

1. Ignore candidates using an incompatible coordinator protocol.
2. The highest compatible runtime version wins.
3. Standalone wins over embedded only when versions are equal.
4. Provider plugin identifier and source path break remaining ties.

Only the elected runtime scans, generates, publishes, watches, runs commands,
or owns background work.

## Goals

- Ship Patchwork as an independently installable Hytale plugin.
- Publish the same runtime as an embeddable Maven jar.
- Let Tamework retain asset patch support without requiring a separate install.
- Guarantee that multiple standalone and embedded copies elect exactly one
  active coordinator.
- Preserve the current patch schema and both existing patch roots.
- Generalize JSON conditions to inspect another installed Java mod's data
  configuration safely.
- Keep Tamework-specific macros and config reload behavior outside the generic
  runtime.
- Preserve honest hot-reload and restart-required reporting.
- Prevent partial target publication and retain last-known-good outputs when a
  live regeneration fails.

## Non-Goals

- Do not adopt Hytalor as Patchwork's backend or patch schema.
- Do not translate Hytalor patches in the first release.
- Do not add wildcard or regular-expression target selection in the first
  release.
- Do not watch mod data configuration files automatically.
- Do not permit raw absolute filesystem paths or paths outside discovered Java
  plugin data directories.
- Do not retain the unused `TameworkSetting` condition.
- Do not retain `/tw patches` aliases or a Tamework Java compatibility facade.
- Do not promise live reload for target families without a verified safe route.

## Public Identity

- Display name: `Patchwork`
- Plugin identifier: `Alechilles:Patchwork`
- Repository/artifact family: `patchwork`
- Java package: `com.alechilles.patchwork`
- Neutral patch root: `Server/Patchwork/Patches`
- Generated pack identifier: `Alechilles:Patchwork_GeneratedPatches`
- Command root: `/patchwork`
- Administrative permission: `patchwork.admin`

The repository name may use a hosting-specific spelling such as `Patchwork` or
`patchwork`, but Maven artifact identifiers use lowercase hyphenated names.

## Repository And Module Architecture

Patchwork is a new Maven reactor with two published modules.

### `patchwork-runtime`

The plain embeddable jar owns:

- the patch model, parser, condition evaluator, and operation engine;
- target discovery and winning-source resolution;
- generated-pack planning, writing, registration, and reload tracking;
- coordinator election and the cross-classloader bridge;
- host contribution registration;
- `/patchwork` commands and permission checks;
- status and diagnostics models;
- the isolated self-test system;
- the public embedding API.

The Hytale server dependency is provided/compile-only. The runtime jar contains
Maven version metadata and does not contain a Hytale `manifest.json` entrypoint.

### `patchwork-standalone`

The standalone module is a small Hytale `JavaPlugin` that shades
`patchwork-runtime`, supplies Patchwork's `manifest.json`, and bootstraps a
standalone candidate. Its lifecycle is limited to bootstrap, start, and clean
shutdown of its provider handle.

### Tamework

Tamework depends on and shades `patchwork-runtime`. During setup it bootstraps
an embedded candidate and registers a Tamework host contribution. Tamework no
longer compiles or packages its current `assets.patches` implementation, public
service accessors, self-test classes, or `/tw patches` commands.

`TwConfigOverrideManager` currently uses `getAssetPatchService()` to locate the
generated cache and distinguish generated Tamework config overlays. It migrates
to Patchwork's embedding handle/shared-root API before the old accessor is
removed. Regression tests preserve generated-overlay detection and effective
source selection. No external Java callers of Tamework's current patcher package
or service accessors were found in the local mod source repositories, so no
public Java compatibility facade is required.

## Coordinator Election

Every candidate publishes a descriptor containing:

- provider ID;
- origin (`STANDALONE` or `EMBEDDED`);
- runtime version;
- coordinator protocol version;
- provider plugin identifier and version;
- normalized source jar path;
- canonical shared data root;
- a reflective provider bridge.

Every Patchwork release uses one protocol-neutral property key in
`System.getProperties()` for process-wide ownership. The election ABI under
that key is an intentionally stable JDK/reflection contract that future runtime
protocols must preserve. Protocol-specific registries are forbidden because
they could elect one owner per protocol and create split brain.

Registry objects and provider bridges cross plugin classloaders through
reflection and JDK-owned types only. Gson types, Patchwork classes, and Hytale
plugin classes never appear in the cross-loader contract. JSON payloads cross
as UTF-8 strings; collections and structured results use JDK strings, maps,
lists, and completion stages.

Runtime versions are read from the runtime artifact's Maven `pom.properties`,
falling back to package implementation version. Candidates that cannot satisfy
the stable election ABI remain visible but ineligible and passive. Runtime and
host-contribution feature protocols negotiate after election and cannot create
another owner. Version comparison and deterministic tie-breaking mirror Alec's
Telemetry.

Registry mutation and ownership transfer are serialized. Each activation gets
a monotonically increasing epoch and an unforgeable registration token. Every
generation, publication, status commit, self-test, observation, and host-adapter
boundary verifies the epoch before creating side effects.

On election change, the registry revokes the old epoch first, stops new work,
cancels pending work, and synchronously drains in-flight generation/publication
through a bounded shutdown path. The replacement activates only after the old
writer is fenced and drained. If replacement activation fails, the registry
reactivates the prior eligible provider with a new epoch or elects the next
eligible candidate. Re-registering the same provider ID replaces its old bridge;
an old handle can unregister only its own registration token and cannot remove
the replacement.

Only the elected coordinator may:

- process the early asset-load callback;
- scan or evaluate patches;
- write or register the generated pack;
- record reload observations;
- invoke host reload adapters;
- run self-tests;
- register `/patchwork` commands;
- own executors, timers, watchers, or pending observations.

Callbacks that Hytale cannot physically unregister retain an ownership lease
and become no-ops immediately after deactivation. The registry owns a single
publication mutex in addition to epoch fencing, preventing an old and new
coordinator from writing the shared pack concurrently. All Patchwork-owned
executors are closed during deactivation and plugin shutdown.

## Shared Runtime State

All candidates resolve one canonical data root independent of the winning host:

```text
<server-or-save-root>/mods/Alechilles_Patchwork/
  GeneratedPatches/
  SelfTest/
  Diagnostics/
```

The active coordinator owns one generated pack with the fixed identifier
`Alechilles:Patchwork_GeneratedPatches`. A provider-specific pack ID is never
used. Candidate changes therefore do not create competing generated packs or
move runtime state between Tamework and standalone directories.

## Host Contributions

Host contributions are registered through the global coordinator registry and
are independent of which Patchwork candidate wins. A passive embedded runtime
may contribute host behavior but may not patch assets itself.

Contributions are keyed by host plugin identifier and expose versioned,
reflection-safe capabilities:

- macro expansion;
- custom target classification;
- custom reload execution;
- custom hot-reload observation forwarding.

The registry replays current contributions whenever ownership changes.
Unloading a host removes its contribution and prevents new adapter calls.

Tamework contributes these stable macro names:

- `TameworkInteractionBridge`
- `TameworkHookInstruction`
- `TameworkStateInstruction`

Tamework also classifies and reloads `Server/Tamework/**` configuration targets
and forwards reload observations for its custom asset classes. Tamework remains
responsible for its internal registries and thread-affinity rules. Patchwork
passes affected target IDs to the adapter and consumes only a structured result.

If a patch requests a macro whose provider is unavailable, generation fails for
that target with a clear diagnostic. Patchwork does not publish the partially
modified target.

## Patch Discovery

Patchwork scans loaded asset packs at these roots:

1. `Server/Patchwork/Patches/**/*.json` for every installation.
2. `Server/Tamework/Patches/**/*.json` only when
   `Alechilles:Alec's Tamework!` is installed.

Both directory-backed and jar/zip-backed packs are supported. The active
generated pack is excluded from patch and source discovery.

The neutral root is authoritative during migration. Definitions are expanded
from `Targets` before deduplication. The duplicate key is:

```text
source pack identifier + patch ID + expanded target
```

When a neutral and legacy definition share that key, the neutral definition
wins and the legacy definition is reported as shadowed. Identical IDs from
different source packs remain independent. Multiple definitions with the same
key inside one root are an error for that target rather than being applied
twice.

## Patch Schema And Ordering

Patchwork preserves the existing public schema:

- `Id`
- `Target` or `Targets`
- `Priority`
- `Enabled`
- `When`
- `Operations`

It preserves `Add`, `Merge`, `Replace`, `Remove`, and `Insert`, including JSON
pointer paths, anchors, `Before`, `After`, `Find`, `Existing`, and `Required`.
The current macro operation shape remains valid.

Conditions retained in the first Patchwork release are:

- `ModInstalled`
- `AssetExists`
- `AssetMissing`
- `TargetExists`
- `ModVersion`
- `GameVersion` and `ServerVersion`
- `JsonPathExists`
- `JsonPathEquals`
- `All`
- `Any`
- `Not`

`TameworkSetting` is removed. Searches of Animal Husbandry, Alec's Cats, their
history and installed copies found no consumer. The third-party Cats No Defend
pack does use the legacy target-relative `JsonPathEquals` form, which remains
covered by compatibility tests.

Patches apply in ascending `Priority`, then patch ID, with source-pack load
order as the stable final tie-breaker. This preserves current behavior.

## Generalized JSON Conditions

Existing target-relative syntax remains valid:

```json
{
  "JsonPathEquals": {
    "Path": "/Interactions/4/Type",
    "Equals": "ModeCycle"
  }
}
```

The existing top-level `Asset` shortcut also remains valid. A new `Source`
object makes the source explicit and supports other mods' data files:

```json
{
  "JsonPathEquals": {
    "Source": {
      "Type": "ModData",
      "Mod": "OtherAuthor:OtherMod",
      "Path": "config/settings.json"
    },
    "Path": "/features/example/enabled",
    "Equals": true
  }
}
```

Initial source types are:

- `Target`: the current expanded patch target;
- `Asset`: the winning asset-pack file at a specified asset path;
- `ModData`: a JSON file under an installed Java plugin's data directory.

For `ModData`, Patchwork enumerates loaded Java plugins through Hytale's
`PluginManager#getPlugins()`, matches the exact `PluginIdentifier`, and reads
the matched `PluginBase#getDataDirectory()`. Content-only packs have no Java
plugin data root and use `Asset` instead.

The first release treats every installed patch-bearing asset pack as trusted to
query non-secret JSON configuration under discovered Java plugin data roots.
This is an explicit server-owner trust decision: installing a content pack that
contains Patchwork definitions grants it this read-only conditional capability.
Mods must not store credentials or secrets in JSON files intended for cross-mod
conditions. Patchwork does not provide value substitution, return source values
to the patch, or include actual/expected values in logs and status output; only
the boolean result and non-sensitive source/pointer metadata are exposed.

Mod-data paths obey these rules:

- exact plugin identifier required;
- relative paths only;
- absolute paths and `..` traversal rejected;
- normalized and real paths must remain inside the selected plugin data root;
- every path segment is opened without following symbolic links;
- use `SecureDirectoryStream` relative opens when the filesystem supports it;
- otherwise verify no-follow basic/DOS attributes, available file keys,
  reparse-like component rejection, real-path containment, size, and timestamps
  before and after the read, failing closed on every observable change;
- Windows filesystems that expose neither `SecureDirectoryStream` nor stable
  file keys use this practical fallback with a null file key permitted. This
  rejects ordinary symlink, junction, and containment escapes but cannot
  mathematically eliminate a precisely timed, otherwise unobservable parent
  directory swap; that limitation is explicit rather than overstated;
- read-only access;
- maximum file size of 4 MiB.

Each distinct source document is parsed once per generation pass. Every
condition in that pass observes the same snapshot. The cache is discarded
before the next startup generation or explicit reload.

Missing plugins, files, or JSON pointers evaluate false and produce a specific
skip reason that never includes the resolved value. Invalid source syntax,
unsafe paths, oversized files, read errors, containment changes, or malformed
JSON are evaluation failures for the affected target. JSON equality is
structural and type-sensitive.

Patchwork generation has exactly two triggers: the early startup asset-load
phase and an authorized `/patchwork reload`. Patchwork does not automatically
regenerate when patch files, source assets, or mod data configuration files
change. Generated-pack observers only classify and confirm the output of an
already requested generation. Input changes take effect at the next startup or
explicit reload.

Hytale Workshop evidence for this boundary comes from indexed release 0.5.7:

- `com.hypixel.hytale.server.core.plugin.PluginManager#getPlugins()` and
  `getPlugin(PluginIdentifier)`;
- `com.hypixel.hytale.server.core.plugin.PluginBase#getDataDirectory()`;
- `com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin#load()`;
- `com.hypixel.hytale.server.core.asset.AssetModule#getAssetPacks()`;
- `com.hypixel.hytale.assetstore.AssetPack#getRoot()` and `isImmutable()`.

## Generation And Publication

The active runtime handles the early `LoadAssetEvent` at the current patcher
priority, before broad server JSON validation. The generation pipeline is:

1. Snapshot loaded packs, plugins, host contributions, and JSON sources.
2. Scan neutral and permitted legacy roots.
3. Parse, expand targets, deduplicate, and evaluate conditions.
4. Group applicable definitions by target.
5. Resolve the winning non-generated source asset.
6. Apply operations to an in-memory deep copy.
7. Build a complete publication plan in a staging directory outside the
   registered generated pack.
8. Validate staged files and manifest before mutating live state.

The generated manifest retains explicit ordering relationships needed to load
after source packs. Pack registration and ordering remain stable for the server
process; live reload never unregisters and replaces the pack.

One malformed patch or target does not block unrelated targets or server
startup. Required-operation failure rejects the complete target. Optional
operation failure is reported as skipped.

Startup and live publication use different commit paths because the live pack
is already watched file by file.

At startup, Patchwork never reuses a prior generated target that failed current
generation. It builds a complete new root, omits failed and no-longer-applicable
targets, moves the prior root to a diagnostics quarantine, atomically swaps the
staged root into place where supported, and only then registers the pack. A
failed target therefore falls back to the current underlying asset rather than
silently loading yesterday's generated output. If the root swap or registration
fails, Patchwork restores the prior directory only as recovery evidence and does
not register it as a valid current pack.

Live reload is target-transactional, not whole-pass atomic. Before modifying a
target, Patchwork records its prior bytes/hash and a journal entry. It performs
a same-directory temporary write plus atomic replace or an intentional delete,
then waits for the applicable watcher or host-adapter confirmation tied to the
generation epoch and expected hash. On failure or timeout, it atomically restores
the prior target and waits for rollback confirmation. A confirmed rollback is
reported as `stale`; the last-known-good output remains active. Late events for
the rejected hash are ignored by token and hash. If rollback itself cannot be
confirmed, Patchwork reports `rollback-failed`, preserves backup evidence, and
marks the target restart-required instead of claiming last-known-good safety.

The live manifest is staged and committed before target transactions; a manifest
failure aborts the reload before any target changes. Its dependency set may be a
safe superset when an individual target later rolls back. Intentional removal of
a target uses the same journal, confirmation, and rollback rules and is not
reported as stale when confirmed.

Successful targets from the same reload remain committed if a later independent
target fails. Status therefore describes target-level outcomes and never claims
the entire reload pass was atomic.

## Reload And Observation

Patchwork does not call Hytale's unsafe generic asset-store reload path. The
authorized `/patchwork reload` command starts a generation transaction; input
watchers never start one. After each target commit, Patchwork relies on:

- Hytale's normal watcher for supported built-in asset families;
- Patchwork's NPC, asset-store, particle, and common-asset observations;
- host adapters for custom asset families;
- restart-required classification when no safe verified path exists.

Each target is reported as one of:

- generated;
- removed;
- hot-reloaded;
- adapter-reloaded;
- restart-required;
- stale;
- rollback-failed;
- skipped;
- failed.

The active coordinator alone owns pending observations and timeouts.
Ownership loss revokes their epoch, cancels them, and drains their publication
work before another candidate activates.

## Commands And Diagnostics

The active runtime registers:

- `/patchwork status`
- `/patchwork reload`
- `/patchwork selftest`

Administrative operations require `patchwork.admin` with operator/admin default
groups. Tamework removes `/tw patches`; no alias remains.

`status` reports:

- active runtime version, origin, provider, protocol, and source jar;
- passive candidates and election reasons;
- registered host contributions;
- scanned neutral and legacy roots;
- the last generation summary;
- generated, removed, hot-reloaded, adapter-reloaded, restart-required, stale,
  rollback-failed, skipped, and failed targets;
- condition-source and unavailable-extension diagnostics.

The self-test uses an isolated Patchwork-owned fixture pack. It waits only for
supported reload confirmations, labels unsupported claims honestly, and always
attempts cleanup. Coordinator deactivation cancels an active self-test.

Logging follows the host policy: admin-relevant summaries at `INFO`, actionable
recoverable problems at `WARN`, and verbose per-definition detail at a gated
diagnostic level. No per-tick logging or allocation is introduced.

## Failure And Shutdown Guarantees

- Patch errors are non-boot-blocking.
- No target is published partially.
- No failed startup target reuses an unverified prior generated output.
- No failed live target silently replaces its last-known-good output; rollback
  failure is reported explicitly and never mislabeled as safe.
- No passive candidate registers commands or owns workers.
- One protocol-neutral registry and publication mutex prevent split brain.
- Active replacement revokes and drains the old epoch before the new winner
  starts.
- Failed replacement activation reactivates the prior eligible provider or
  elects the next candidate.
- Every executor, timer, pending observation, and self-test handle is closed or
  cancelled on deactivation.
- Reflection bridge failures produce diagnostics and safe false/default results
  rather than leaving two active coordinators.
- An unavailable host extension fails only patches or reloads that require it.

## Migration Plan

### Stage 1: Extract The Core

- Create the Patchwork Maven reactor.
- Move patcher and self-test code into `patchwork-runtime`.
- Move and rename tests to the Patchwork package.
- Preserve existing behavior before adding new features.
- Add neutral discovery, deduplication, generalized conditions, and
  last-known-good publication behind focused tests.

### Stage 2: Add Election And Standalone Packaging

- Implement the coordinator protocol and provider bridge test-first.
- Add cross-classloader ownership replacement tests.
- Add standalone manifest, lifecycle, commands, and shaded packaging.
- Publish the plain runtime and standalone artifacts together at version
  `1.0.0`.

### Stage 3: Integrate Tamework

- Pin and shade Patchwork runtime `1.0.0`.
- Bootstrap an embedded candidate.
- Add the Tamework host contribution.
- Migrate `TwConfigOverrideManager` generated-overlay lookup to Patchwork's
  embedding handle/shared generated-root API and preserve effective-source
  behavior with regression tests.
- Remove Tamework patcher sources, tests, accessors, and command classes.
- Move Tamework's bundled patches to `Server/Patchwork/Patches`.
- Retain legacy-root scanning for downstream packages.

### Stage 4: Documentation And Release

- Publish Patchwork standalone and embedding guides.
- Move generic patch documentation from the Tamework wiki to Patchwork.
- Keep a focused Tamework page for macros and legacy compatibility.
- Add a Tamework changelog entry for `/patchwork` command migration.
- Release Patchwork before the Tamework version that embeds it.

Existing Animal Husbandry, Cats, and third-party patch files do not require an
immediate path migration. Authors may move to the neutral root in later releases.

## Verification Matrix

### Unit And Contract Tests

- Existing parser, operation, condition, target, archive, publisher, tracker,
  reload, and self-test coverage.
- Golden generated JSON for current Tamework, Animal Husbandry, and Cats
  compatibility patches.
- Neutral/legacy discovery and neutral-wins deduplication.
- Legacy root disabled without Tamework.
- Duplicate-key rejection within one root.
- Legacy `JsonPathEquals` and `JsonPathExists` forms.
- `Target`, `Asset`, and `ModData` source forms.
- Missing source, malformed JSON, 4 MiB limit, traversal, absolute path, and
  symlink and symlink-swap escape behavior.
- Cross-mod data-root trust behavior and non-sensitive diagnostics that never
  reveal actual or expected values.
- Per-generation source snapshot consistency.
- Required-operation target rollback and optional-operation skip behavior.
- Startup quarantine with a prior generated file plus removed source pack,
  malformed patch, and changed game version.
- Live last-known-good preservation, intentional removal, confirmation timeout,
  adapter failure, rollback confirmation, and rollback-failure distinction.
- Staging, journal recovery, target atomic replacement, prune rollback, and
  manifest-aborts-before-target publication.
- Negative trigger tests proving patch, source-asset, and mod-data file edits do
  not regenerate until startup or authorized `/patchwork reload`.

### Coordinator Tests

- Highest compatible runtime version wins.
- Equal-version standalone wins over embedded.
- Newer embedded wins over older standalone.
- All candidates contend through one protocol-neutral ownership key.
- An election-ABI-ineligible candidate remains visible and passive without
  creating another registry or owner.
- Provider and path tie-breakers are deterministic.
- Same-provider replacement tokens prevent an old handle from unregistering the
  new bridge.
- Foreign classloader candidates and contributions communicate through JDK
  types only.
- Takeover during reload and self-test fences and drains the old epoch.
- Replacement activation failure restores the prior eligible owner or elects
  the next candidate.
- Ownership switches leave one command tree, one generated pack, one observer
  set, and one executor set.

### Packaging And Architecture Tests

- Runtime jar has Maven version metadata and no plugin entrypoint manifest.
- Standalone jar shades exactly one runtime and has the Patchwork entrypoint.
- Tamework jar embeds the pinned runtime.
- Tamework no longer packages its old patcher classes or `/tw patches` command.
- Tamework adapter names and macro IDs remain stable.
- Patchwork runtime has no imports from Tamework packages.

### Live Matrix

- Patchwork standalone only.
- Tamework embedded only.
- Equal-version standalone plus Tamework embedded.
- Newer standalone plus older embedded.
- Older standalone plus newer embedded.
- Mixed election-ABI eligibility without split brain.
- Host macro present and absent.
- Startup generation before validation.
- Restart with prior generated outputs and a now-failing source/patch.
- Ownership takeover during an active reload.
- `/patchwork reload` for built-in, Tamework, common, and restart-required
  targets.
- `/patchwork selftest` completion and cleanup.

Run full Maven tests in Patchwork and Tamework. Run Tamework's agent-doc checks
after package, test, script, or major documentation layout changes. Validate
affected Tamework asset consumers with the exact locked Hytale profile before
release.

## Compatibility Changes

Intentional compatibility changes are limited to:

- `/tw patches` is removed in favor of `/patchwork`.
- `TameworkSetting` is removed because no known consumer exists.
- Tamework's Java patch-service accessors and package classes are removed; no
  external Java consumers were found.

Preserved compatibility includes:

- all existing operation shapes;
- current priority and ID ordering;
- target-relative and asset-relative JSON conditions;
- Tamework macro names when Tamework is installed;
- `Server/Tamework/Patches` when Tamework is installed;
- current generated-target reload classifications unless a safer verified route
  is introduced and covered by tests.

## Alternatives Considered

### Hard-Code Tamework Compatibility In Patchwork

Rejected because it would leave the standalone runtime permanently coupled to
Tamework and make future host-specific target families require Patchwork core
changes.

### Use Hytalor As The Backend

Rejected for the first release because Hytalor uses a different schema,
ordering and query model, and GPL-3.0 licensing. Patchwork would still need its
own conditions, embedding, election, diagnostics, compatibility roots, and
self-test layer, making behavioral compatibility harder than extracting the
current implementation.

### Require Standalone Installation

Rejected because Tamework must retain patch support without a second download,
and the agreed runtime hierarchy explicitly includes embedded candidates.
