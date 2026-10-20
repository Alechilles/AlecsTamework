# Persistence Change Recipes

Choose one primary recipe. Copy the nearest existing flow instead of building
a new mechanism.

## Diagnose Runtime State

1. Use `docs/agents/runtime-vs-source-checklist.md` to confirm the loaded jar
   and the world folder.
2. Read the server log. Store load and flow failures log WARN or SEVERE with
   the profile id. A failed background write is not logged; it shows only as
   the writer's last failure and pending count in diagnostics.
3. Read `diagnostics().getPersistenceDiagnostics()`: records by location,
   pending writes, last flush time, last failure, unreadable records.
4. Read the files in `universe/Tamework/Companions` (plain JSON): the owner
   file for the record, the snapshot file, `meta.json` for an import or
   fresh-start receipt, and any `*.unreadable-<timestamp>` files.
5. For an import problem, read the report in the Tamework data folder.
6. Classify before editing: record change, file write, fence decision, live
   effect, snapshot content, admission, importer, or stale runtime.

There is no `/tw debug persistence` command. `/tw persistence start-fresh` only
creates an empty store while old saves block the world.

## Add a Record Field

1. Add it to `CompanionRecord` and its `Builder`.
2. Map it in `CompanionRecordBson`: a new field name, read with a default when
   missing. Never rename or reuse a field name. Old files need no migration.
3. If the panel needs it for an unloaded companion, add it to
   `CompanionSummary` and fill it in `CompanionSummaries` instead.
4. If it is world time, keep the sign and use `0` as unset.
5. Test the round trip and the default for an old document in
   `CompanionRecordBsonTest`.

## Add or Change a Holder-Changing Flow

Use `CaptureFlow`, `StoreFlow`, `RestoreFlow` or `CoopIntakeFlow` as the
pattern.

1. On the body's world thread, take the snapshot first if a body is leaving
   a world (`CompanionSnapshots.capture`, or `HytaleStoreCapture` for a
   store). If it fails, abort and change nothing.
2. Ask a managed role's admission provider before taking the index lock
   (`ProviderAdmission.evaluate`).
3. Under `index.atomically`: re-check the record (revision or generation),
   check caps with `CompanionAdmissionGate`, apply a `CompanionTransitions`
   change (generation + 1), and unregister the loaded body.
4. Queue the snapshot so the writer writes it no later than the owner file
   that points at it (see `CompanionWriter.queueSnapshot`).
5. Release the lock, then write the owner files with `flushNow`
   (`OwnerFileFlush.flushOwners` writes the new owner first when the owner
   changes).
6. On failure, put the record back (`RestoreFlow.revertHolder` or
   `CompanionIndex.revert`) and re-register the body. Only revert before any
   holder carries the new generation.
7. On success, hop to the world thread, confirm the record still holds the
   committed holder (`RestoreFlow.sameHolder`), then remove the old body, spawn,
   or change the item or slot. A spawn stamps the committed generation, uses
   `AddReason.LOAD`, then calls `CompanionSaves.markChanged`.
8. Return a result enum and show a localized message for refusals.

These changes do not wait for the write: tame and adoption, death, loss,
unload refresh, owner release, Forget and a destroyed capture item. They
update the index and the writer saves on its next flush (spec 8.1, 8.6, 8.7,
8.12 accept the crash window).

## Make a Live Component Change Reach Disk

1. A codec component on a companion body saves only when the body is marked
   dirty. `CompanionChangeDetectorSystem` checks fingerprints every 2 s
   (discrete tier) and 60 s (drift tier).
2. For a new saved component that changes in place, add it to the right tier
   in `CompanionFingerprints.production()`.
3. For new flow code that needs an immediate save, call
   `CompanionSaves.markChanged(accessor, ref)` on the world thread with the
   current Store or CommandBuffer.
4. Do not mirror the value into the record. Only the UI `summary` copies
   presentation values, refreshed on snapshot and unload.

## Change Snapshot Content or Respawn

1. Capture: `CompanionSnapshots` (format 1). Envelope: `SnapshotEnvelope`.
2. What a respawn strips or resets: `CompanionRespawn.Types.production`,
   `CompanionRespawn.stripDocument` and `CompanionRespawn.prepare`.
3. Edits before deserializing (alarm re-basing to the destination world's game
   time, revive changes): `SnapshotPatch`. Keep each method pure.
4. Keep the format 0 restore path in `HytaleCompanionSpawner`: imported
   companions use it until their first restore.
5. Test the document edit in `CompanionRespawnTest` or `SnapshotPatchTest`.
   Engine behavior of the spawned body needs live evidence.

## Profile and Extension Data

Extension values live on the record (`ExtensionEntries`) and are written with
the owner file. `IndexProfileDataApi.compareAndSet` completes after the owner
file is written and undoes its change if the write fails; `put` and `delete`
do not wait. Bonded extension data goes through `IndexBondedCompanionApi`.
Releasing a companion clears its extensions.

## Admission and Caps

Built-in owned, deployed and group caps are checked under the index lock in
the step that changes the record (`CompanionAdmission`,
`CompanionAdmissionGate`). Providers are asked before the lock; synchronous
sites use `ProviderDecisionCache`, which refuses while a decision is fetched.
Counts walk one owner's records; do not add counters.

## Change the Importer or Legacy Handling

1. Read `companion/migrate/package-info.java`. Keep importer-only classes
   separate from the lazy classes that stay while imported worlds exist.
2. Reading: `LegacySource` (read-only, never writes beside the original) and
   `LegacyReader`. Mapping: `LegacyMapper` (pure). Running and publishing:
   `CompanionImporter` (all or nothing through `Companions.importing`).
3. Keep signed world-time values and `0` as unset.
4. Add or update a SQL fixture under `src/test/resources/import-fixtures` for
   each affected schema and test the mapped records.

## Change a Settings or Data-Path Store

Keep the change in its `settings` class. These stores hold no companion state
and need no index, generation or flow. Preserve atomic file writes, graceful
read failure and current caller behavior.
