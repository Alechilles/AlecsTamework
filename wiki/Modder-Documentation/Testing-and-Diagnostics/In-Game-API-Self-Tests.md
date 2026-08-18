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
console, `core`, `diagnostics`, `hydragon-integrations`, and their read-only
`all` aggregate are available.

## Current suites

- `core`: API version, required capability advertisement, global config, and
  diagnostics availability
- `profile`: canonical profile resolution by NPC alias and profile ID
- `command-links`: linked tool IDs and saved home position
- `configs`: framework and, when enabled, optional-example interaction,
  companion, progression, spawner, naming, and command-item config reads
- `progression`: controlled mutations plus best-effort restoration of the
  fixture baseline
- `interaction-extensions` and `trait-effects`: registration, lookup,
  unregister, and invalid-ID behavior
- `policies`: ownership, damage/claim decisions, and the durable owner-cap
  preflight
- `diagnostics`: persistence path, health, and metrics readability
- `hydragon-integrations`: capture mechanics plus independent readiness for
  capture policy, transactional profile data, persistence resilience,
  population groups, provisioning, command-family rosters, timed summoning,
  paid revival, resolved-attempt consumption, and tame/link capture

The runner logs a verbose report even when chat output is summarized. These
checks validate the packaged public API; they do not replace the Maven suite or
the replacement-persistence live smoke tests.
