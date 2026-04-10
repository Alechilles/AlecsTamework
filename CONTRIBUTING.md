# Contributing to Alec's Tamework!

Thanks for your interest in contributing to **Alec's Tamework!**. This project is both a plugin and a framework for other mods. My goal with this framework is for less technical modders to be able to easily add Alec's Tamework! as a dependency and get all the functionality they need to make their barebones NPCs highly interactive and interesting. I'm also trying to keep everything as modular as possible, so modders can choose exactly what features they want to add to their NPCs.

## What we need help with
- Bug fixes (especially edge cases in interaction flows, capture/spawn, and config loading)
- Documentation improvements (wiki pages, examples, troubleshooting)
- NPC behavior components for anything from routines to run when idle to make them seem more lifelike to advanced combat routines
- Example content (templates and example NPCs that demonstrate component usage)

If you’re unsure where to start, open an issue describing what you want to work on.

## Project structure
- `src/main/java` — plugin code
- `src/main/resources` — assets and example NPCs
- `wiki` — canonical public and contributor documentation
- `docs` — legacy source notes that have been absorbed into the canonical wiki

## How to contribute
1. **Discuss first**
   - Open an issue or start a discussion if the change is more than a small fix.
2. **Fork and branch**
   - Use a short, descriptive branch name like `fix-owner-message` or `docs-capture-flow`.
3. **Make changes**
   - Keep changes focused and scoped.
   - Avoid mixing refactors with behavior changes.
4. **Test**
   - Validate on a local test server if possible.
   - If the change affects configs, confirm default and override behavior.
5. **Open a PR**
   - Summarize what changed and how it was tested.
   - Include any relevant logs or screenshots.

## Style & conventions
- **Java**: keep changes minimal and readable. Favor explicit names over cleverness.
- **Resources**: match existing file naming conventions and directory structure.
- **Docs**: keep language clear and actionable. Prefer examples.

## ECS and thread-safety gates (required)
- In runtime system classes (`*System.java`), do not call `store.putComponent/removeComponent/tryRemoveComponent/addComponent` directly.
- Use `CommandBuffer` for ECS writes inside system callbacks.
- In runtime system/tick paths, avoid immediate `DamageSystems.executeDamage(..., store, ...)` calls; defer via `commandBuffer.run(...)` when running inside chunk/system processing.
- If work is deferred (`CompletableFuture`, delayed executors, schedulers), capture stable IDs (`UUID`) and resolve live refs/components inside `world.execute(...)`.
- Do not access player-affine APIs (`PlayerRef.getComponent(Player)`, `Universe.getPlayers()` scans for live player mutation) from async/deferred code.
- In system/tick/event processing paths, use `DamageSystems.executeDamage(..., commandBuffer, ...)` instead of the store overload.
- Startup registration for optional integrations/dependencies must degrade gracefully (warn + skip) instead of crashing plugin setup.
- Guard tests must pass for system/runtime changes:
  - `EcsWriteSafetyGuardTest`
  - `AsyncThreadSafetyGuardTest`
  - `DamageExecutionWriteSafetyGuardTest`
  - `StartupResilienceGuardTest`
  - `NeedsDamageDispatchGuardTest`
  - `SqliteStartupResilienceGuardTest`

## Compatibility goals
- Prefer changes that don’t break existing mod integrations.
- If a change is breaking, it should be explicit and documented.
- As far as I know, Hytale/CurseForge do not have a way to enforce specific version dependencies at the moment, so ensuring backwards/forwards compatability is extremely important.

## Licensing
By submitting a contribution, you agree that your contribution is licensed under the project’s existing license.

---

Questions? Open an issue or ask in the discussion threads.
## Internal developer docs
See `/wiki/Developer-Documentation` for the canonical contributor docs. `/docs` remains as legacy source material.

