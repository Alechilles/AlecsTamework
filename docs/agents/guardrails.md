# Agent Guardrails

This page collects repo-specific checks that should be run before trusting an answer or change. Keep policy in `AGENTS.md`; keep routing and executable commands here.

## Before Editing

- Confirm the requested target is the source repo, runtime copy, packaged artifact, save override, or external wiki path the user named.
- Read the closest existing docs and tests before introducing a new pattern.
- Prefer base-game assets and existing Tamework config before adding Java behavior.
- If a change touches an existing class over 1000 lines, look for an extraction opportunity instead of adding more behavior to the same class.

## Runtime and ECS Safety

Before finalizing changes to runtime systems, tick paths, async callbacks, damage dispatch, or player access, run:

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

If matches are in tick/runtime paths, resolve players from the current world/store or route writes through `CommandBuffer` before merge.

## Agent Documentation Checks

Run this after changing `AGENTS.md`, `docs/agents`, package layout, scripts, tests, or major docs:

```powershell
.\scripts\tools\check-agent-docs.ps1
```

The check verifies that:

- required agent docs exist,
- `AGENTS.md` still links to the agent routing docs,
- the generated agent index is current,
- the safety guard tests still exist,
- the external Lessons Learned path is reachable.

## Artifact Freshness Checks

When behavior differs between source and game:

- Compare source assets under `src/main/resources` against runtime assets in `UserData\Mods`.
- Inspect packaged jar/zip contents instead of assuming a build copied the latest files.
- Check save overrides before changing source assets. A stale full-array override can mask correct source behavior.
- Check generated patch pack ordering when an asset patch appears valid but does not win at runtime.

## Release Checks

Use release skills and scripts for release work. The common entry points are:

```powershell
.\scripts\release\validate-release.ps1 -Version <version>
.\scripts\release\build-package.ps1 -Version <version>
.\scripts\release\publish-prebuilt.ps1 -Version <version>
```

Pass the explicit version from the manifest/build source rather than assuming a default.

