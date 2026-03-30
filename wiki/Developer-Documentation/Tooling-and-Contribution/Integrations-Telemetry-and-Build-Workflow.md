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



