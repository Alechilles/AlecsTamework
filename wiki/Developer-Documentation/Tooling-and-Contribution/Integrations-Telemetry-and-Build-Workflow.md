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
- Asset pack ordering through `TameworkAssetPackCoordinator`

## Telemetry
- `TameworkHStatsIntegration` boots HStats support
- `TameworkDependencyMetricsReporter` and related metrics classes detect installed mods and forward tracked data
- Server owners can opt out through `hstats-server-uuid.txt`
- Crash telemetry is separate from HStats and uses a store-first, upload-later flow
- Crash telemetry is strict opt-in through `universe/Tamework/Telemetry/tamework-crash-telemetry.txt` (default `enabled=false`)
- Only Tamework-attributed fatal failures are captured:
  - uncaught exceptions (via chained global default uncaught-exception handler)
  - exceptional world removals (`RemoveWorldEvent` with `EXCEPTIONAL`) using world failure details
  - setup/start lifecycle failures (captured then rethrown so existing failure semantics remain unchanged)
- Attribution gate before capture:
  - `PluginIdentifier.identifyThirdPartyPlugin(throwable)` must match Tamework, or
  - throwable stack trace must contain the Tamework package prefix
- Reports are written atomically to `universe/Tamework/Telemetry/crash-reports/pending` and deduplicated by crash fingerprint
- Queue size is bounded by `max_pending_reports` (oldest reports pruned first)
- Upload behavior:
  - async startup flush plus periodic background flush
  - per-pass cap from `max_uploads_per_flush`
  - successful upload deletes the local pending file
  - failed upload keeps the file for retry
  - telemetry never throws into runtime/gameplay threads
- Debug command: `/tw debugcrashtelemetry` (status), `/tw debugcrashtelemetry flush` (manual async upload pass)
- Privacy: payload excludes player-identifying gameplay data by default (stack trace + runtime metadata only)

## Build and packaging
- Maven produces a jar-only plugin artifact
- Resources include Java code plus `src/main/resources`
- Manifest versioning is filtered from Maven properties
- `install-plugin`, `run-server`, and `prerelease` are the main build profiles called out in the repo docs

## Dev workflow notes
- Dev runs reference `src/main/resources` directly for faster iteration
- Runtime ordering removes legacy standalone Tamework asset packs when detected

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)



