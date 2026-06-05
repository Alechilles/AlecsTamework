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
- Asset pack ordering through `TameworkAssetPackCoordinator`

## Telemetry
- `TameworkHStatsIntegration` boots HStats support
- `TameworkDependencyMetricsReporter` and related metrics classes detect installed mods and forward tracked data
- Server owners can opt out through `hstats-server-uuid.txt`
- Crash telemetry is separate from HStats and is handled by the embedded Alec's Telemetry runtime
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

## Build and packaging
- Maven produces a jar-only plugin artifact
- Resources include Java code plus `src/main/resources`
- Manifest versioning is filtered from Maven properties
- Maven Shade includes Creditor from Cursemaven and filters Creditor's root `manifest.json` so it cannot replace Tamework's plugin manifest.
- `install-plugin`, `run-server`, and `prerelease` are the main build profiles called out in the repo docs

## Dev workflow notes
- Dev runs reference `src/main/resources` directly for faster iteration
- Runtime ordering removes legacy standalone Tamework asset packs when detected

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)



