---
name: tamework-interaction-configurator
description: Use when editing Tamework TwInteractionConfig, command-item modes, prompts, sensors, actions, cooldowns, contextual interactions, command cycles, or interaction-triggered role behavior in Alec's Tamework and downstream mods. Uses exact-profile NPC guidance, inheritance provenance, affected-scope validation, and lifecycle verification.
---

# Tamework Interaction Configurator

Keep interaction prompts and interaction execution perfectly aligned.

## Workflow

1. Read `references/interaction-checklist.md`.
2. Read `references/prompt-state-matrix.md`.
3. Use `$hytale-asset-tools` and lock the exact project profile. Inspect the
   full sensor -> prompt -> action -> state change -> cooldown/reset path with
   declared/effective provenance, references, findings, and actionable
   advisories.
4. Use `$hytale-asset-inheritance-contract` for every `TwInteractionConfig`
   parent/fallback change. Do not flatten inherited values into children.
5. Use `author options` for role action/sensor/filter/state shapes and
   same-profile reviewed examples for orientation. Never author from a static
   type list.
6. Use `hytale-workshop-mcp` when command or interaction behavior depends on
   vanilla Hytale prompt, item interaction, sensor, action, target, cooldown, or
   role semantics not established by repo/profile evidence.
7. Build the config and role wiring as one read-only candidate. Run `author
   validate --scope affected` before materialization.
8. Generate verification for prompt visibility, executable action, denied
   conditions, mode cycle, cooldown, reset, target loss, and ownership where
   applicable. Use logs and live behavior only as additional runtime evidence.

## Output Contract

Return:
- Interaction files changed.
- Prompt/action alignment checks.
- Mode mapping table for touched interactions.
- Project profile, knowledge hash, snapshot/candidate identity, validation
  outcome, and verification gaps.

## Guardrails

1. No prompt without executable action path.
2. No action path without gating sensor conditions.
3. Keep interaction type IDs stable unless explicitly breaking.
4. Distinguish vanilla interaction behavior from Tamework's optimized `TwInteractionConfig` path in reports.
5. Do not invent command modes, prompts, states, cooldown values, component
   interfaces, or inherited overrides.
6. Do not treat a visible prompt as proof that execution, reset, or ownership
   paths work.
7. Keep review-only fallback/inherited-default advisories distinct from
   deterministic wiring blockers.
8. Verify that the cooldown producer and the sensor/transition used for reset
   read the same alarm/state store. Similar names do not prove a shared
   lifecycle channel.
