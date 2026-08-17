# Repository-Owned Codex Skills

This directory is the canonical source for Tamework-specific Codex skills.
Do not maintain separate editable copies under `.codex/skills`.

Run this command from the repository root after a clone or skill addition:

```bash
bash scripts/tools/link-codex-skills.sh
```

The installer discovers each immediate child directory that contains a
`SKILL.md`. It creates a Windows junction under the active Codex skills folder.
It is idempotent and stops when a target exists but points elsewhere. It never
deletes or replaces a conflicting target.

For a rename, first move the canonical repository directory. Then explicitly
remove the old `.codex/skills/<old-name>` junction after verifying its exact
target, and run the installer to create the new junction. The installer does
not scan for or remove stale names.

## Skill Boundaries

| Skill | Primary responsibility |
| --- | --- |
| `tamework-modding` | General Tamework registration and Java/asset wiring |
| `tamework-persistence` | Durable companion identity, state, operations, and recovery |
| `tamework-config-authoring` | Complete `Tw*Config` family contracts |
| `tamework-interaction-configurator` | Prompt, sensor, action, state, and cooldown wiring |
| `tamework-command-runtime` | Command items, panels, authority, HUD, and cleanup |
| `tamework-companion-progression` | Progression state, time, save, restore, and coupling |
| `tamework-api-evolution` | Public API compatibility and downstream contracts |
| `tamework-runtime-safety` | ECS, threading, tick cost, cadence, and shutdown |
| `tamework-avatar-flight` | Avatar flight session, input, visuals, and recovery |
| `tamework-test-authoring` | Focused behavior regression tests |

Shared Hytale, integration, documentation, and release skills remain outside
this repository unless Tamework becomes their only owner.
