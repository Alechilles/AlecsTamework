---
title: "Integrations, Telemetry, and Build Workflow"
order: 12
published: true
draft: false
---
# Integrations, Telemetry, and Build Workflow

Parent: [Tooling and Contribution](/mod/alecs-tamework/tooling-and-contribution) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Integrations
- Tooltip bridge loading under `integration/tooltips/`
- SimpleClaims breeding and damage bridge under `integration/simpleclaims/`
- Creditor is embedded under `integration/creditor/`; Tamework calls Creditor during plugin setup/start and ships `Server/Credits` metadata for the credits page.
- Asset Editor visibility for Tamework's embedded read-only pack through `TameworkAssetEditorPackService`

## Telemetry
- `TameworkHStatsIntegration` boots HStats support
- `TameworkDependencyMetricsReporter` and related metrics classes detect installed mods and forward tracked data
- Server owners can opt out through `hstats-server-uuid.txt`
- Crash and operational telemetry are separate from HStats and are handled by Alec's Telemetry `1.2.3`
- Tamework uses the conventional project descriptor loaded by `CrashTelemetryService`. The embedded Patchwork `1.3.5` runtime contributes a separate logical `patchwork` project through the generic contribution API. Patchwork's contribution is hosted-only in this release.
- The direct Tamework dependency and Patchwork's transitive dependency converge on one host-local Telemetry provider. This is one physical provider, not one shared consent setting: Tamework and Patchwork consent are independent project settings.
- Telemetry settings live in `universe/Tamework/Settings/tamework-settings.json` under `telemetry.enabled` and `telemetry.breadcrumbsEnabled`
- `/tw settings` persists those values and mirrors them into the embedded runtime project override at `Telemetry/Settings/projects/alecs-tamework.json`
- Legacy `crash-telemetry.json` and `tamework-crash-telemetry.txt` files are imported once when no global telemetry values exist, then left in place
- Crash telemetry defaults to enabled and supports runtime opt-out via `/tw settings` or settings-file edits
- Only Tamework-attributed fatal failures are captured:
  - uncaught exceptions (via chained global default uncaught-exception handler)
  - exceptional world removals (`RemoveWorldEvent` with `EXCEPTIONAL`) using world failure details
  - setup/start lifecycle failures (captured then rethrown so existing failure semantics remain unchanged)
- Attribution gate before capture:
  - `PluginIdentifier.identifyThirdPartyPlugin(throwable)` must match Tamework, or
  - throwable stack trace must contain the Tamework package prefix
- Crash and event queues, deduplication, attribution, HTTP delivery, breadcrumbs, and persistence are owned by `alecstelemetry-runtime`
- Upload behavior follows the shared runtime: queued first, flushed asynchronously, and never allowed to throw into runtime/gameplay threads
- Debug command: `/tw debugcrashtelemetry` (status), `/tw debugcrashtelemetry flush` (manual async upload pass), `/tw debugcrashtelemetry simulate` (manual simulation path for privileged users)
- Privacy: payload excludes player-identifying gameplay data by default (stack trace + runtime metadata only)
- Disabling Tamework consent does not disable Patchwork consent. Use the `/telemetry consent` menu to manage each project independently.
- The `1.2.x` contribution contract does not promise live same-ID replacement or failover. If the elected Patchwork project retires or is replaced, restart the server before expecting a new candidate to write.

## Build and packaging
- Maven produces a jar-only plugin artifact
- Resources include Java code plus `src/main/resources`
- Manifest versioning is filtered from Maven properties
- Maven Shade includes Creditor from Cursemaven and filters Creditor's root `manifest.json` so it cannot replace Tamework's plugin manifest.
- Gradle packages Tamework's direct Telemetry edge and Patchwork's transitive edge at the aligned `1.2.3` runtime. `patchwork_version=1.3.5` and `alecstelemetry_version=1.2.3` are the source-of-truth properties.
- `packagingTest` runs an isolated packaged behavior smoke for Patchwork's version and graceful no-host telemetry path; it does not assert raw shaded-jar entry inventories.
- `install-plugin`, `run-server`, and `prerelease` are the main build profiles called out in the repo docs

## Dev workflow notes
- Dev runs reference `src/main/resources` directly for faster iteration
- Asset-pack precedence follows manifest load-order configuration; Tamework does not reorder the engine's pack list at runtime

## Release checklist

The persistence replacement uses the normal Tamework release workflow; it does
not have a separate candidate builder or rehearsal runtime.

1. Update `pom.xml`, filtered manifests, `CHANGELOG.md`, and user-facing docs to
   the same version.
2. Run `./mvnw test`, including the replacement architecture, migration, and
   crash-recovery gates in the normal suite.
3. Build the ordinary release artifact and verify its embedded assets and
   manifest version.
4. Smoke-test a new world, one public v2-v4 import, direct-live coop
   capture/release, filled-spawner capture/release, and exact Death/Lost
   restoration.
5. Confirm an unreleased v5-v9 source is refused unchanged before publishing.

Whole-world backups remain the server operator's responsibility. The importer
never modifies a public source database or tester-only refused source.

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)



