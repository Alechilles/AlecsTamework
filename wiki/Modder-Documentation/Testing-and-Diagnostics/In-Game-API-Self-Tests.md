---
title: "In-Game API Self-Tests"
order: 2
published: true
draft: false
---
# In-Game API Self-Tests

Tamework's operator self-tests exercise the public API against prepared
fixtures. Run only suites listed by the installed build and treat an
unadvertised capability as unavailable. Install and enable the optional
Tamework example asset pack before you prepare fixtures or run a suite that
uses them.

## Commands

- `/tw api test prepare`
- `/tw api test status`
- `/tw api test run [suite] [verbose]`
- `/tw api test reset`

Prepared in-world fixtures are required for profile, command-link, config,
progression, extension, trait-effect, and policy suites. From the server
console, `core`, `command-hud`, `diagnostics`, `hydragon-integrations`, and
their read-only `all` aggregate are available.

## Current suites

- `core`: API version, required capability advertisement, global config, and
  diagnostics availability
- `profile`: canonical profile resolution by NPC alias and profile ID
- `command-links`: linked tool IDs and saved home position
- `command-ui`: command-menu renderer and contributor registration, custom
  action dispatch, custom flow creation, and cleanup; this suite needs a
  player context but does not need prepared fixtures
- `command-hud`: target and equipped-tool HUD renderer and contributor
  registration, detached composition, focused refresh, session cleanup, and
  diagnostics; this fixture-free suite also runs from the server console
- `configs`: framework and, when enabled, optional-example interaction,
  companion, progression, spawner, naming, and command-item config reads
- `progression`: controlled mutations plus best-effort restoration of the
  fixture baseline
- `interaction-extensions` and `trait-effects`: registration, lookup,
  unregister, and invalid-ID behavior
- `policies`: ownership, damage/claim decisions, and the owner-cap preflight
- `diagnostics`: companion folder path, health, and queue metrics readability
- `hydragon-integrations`: the capture mechanics fixture plus one check per
  capability HyDragon gates a feature on under API 3.0.0:
  `BONDED_COMPANIONS`, `CAPTURE_POLICY`,
  `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`, `INTERACTION_EXTENSIONS`, `EVENTS`,
  and `DIAGNOSTICS`

The `core` suite reports the API version (`3.0.0`) in its first line.

The runner logs a verbose report even when chat output is summarized. These
checks validate the packaged public API; they do not replace the Gradle test
suite or the companion store live checks. `command-hud` is included in the
console-safe `all` aggregate. `command-ui` remains player-only because its
runtime smoke flow needs a player context.
