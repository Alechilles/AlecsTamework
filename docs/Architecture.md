# Architecture Overview

This document is a high‑level map of how **Alec’s Tamework!** is organized and why the major systems exist. It’s intended to help new contributors orient quickly.

## Core concepts
- **Tamework as a framework**: provides reusable actions, sensors, and components that other mods can reference in their NPC templates and items.
- **Two layers**: asset layer (NPC templates/items/particles) + plugin layer (components, actions, sensors, config discovery, runtime behavior).
- **Compatibility focus**: keep changes non‑breaking when possible; use opt‑in configs for behavior shifts.

## Major subsystems
- **NPC Actions & Sensors**
  - Custom actions (capture flows, tamed state, owner setting/denial) are implemented in Java and referenced from templates.
  - Sensors expose readable state for instructions (owner status, tamed status, etc.).

- **Components**
  - Components store persistent state on NPCs (owner/tamed).

- **Config discovery**
  - Item feature configs are discovered from other mods’ `Server/Tamework` folders and can be overridden per‑world.
  - Local save overrides are intended to be additive, not replacements.

- **Patch examples (Hytalor)**
  - Non-destructive JSON patching for adding Tamework behaviors without rewriting base assets.

- **Spawner integration**
  - Capture/spawn flows attach metadata to items, preserve attachments, and enforce owner/tamed rules.

- **Damage filtering**
  - Optional server‑side filter blocks owner damage, all player damage, or all damage if owned (configurable).

## Where to look
- Plugin entrypoint: `src/main/java/.../Tamework.java`
- Actions/Sensors: `src/main/java/.../npc/actions` and `src/main/java/.../npc/sensors`
- Components: `src/main/java/.../npc/components`
- Item/config handling: `src/main/java/.../config`
- Example assets: `src/main/resources/Server/...`
- Hytalor patch: `src/main/resources/Server/Patch/Tamework_Example_Hytalor_Patch.json`

## Versioned docs
Public end‑user docs live in the separate wiki repo. Internal docs live here under `/docs`.
