---
title: "Debugging and Debug Commands"
order: 13
published: true
draft: false
---
# Debugging and Debug Commands

Use this page when an asset or integration loads but behaves incorrectly.

## Recommended workflow

- Reproduce on a local server.
- Watch for asset decode, builder-registration, and config-resolution warnings.
- Verify the exact role, item, command item, or coop ID.
- Confirm the runtime jar and assets match the source being tested.

## Useful commands

- `/tw getowner`, `/tw setowner`
- `/tw gettamed`, `/tw settamed`
- `/tw getalarm [AlarmName] [NpcUuid]`
- `/tw config`, `/tw settings`
- `/tw reloadconfig`
- `/tw gethappiness`, `/tw gettraits`, `/tw getlifestage`
- `/tw findnpc <uuid>`
- `/tw npcclean <roleId>`
- `/tw showhitboxes`
- `/tw debugdb [status|health|integrity|detail]`

## Debug toggles

- `/tw debughook`
- `/tw debugprompt`
- `/tw debugspawner`
- `/tw debugspawnerlocation`
- `/tw debugdespawn`
- `/tw debuglag`
- `/tw debugcoop`
- `/tw debugneedsconsume`
- `/tw debugneedsdamage`
- `/tw debugneedsseek`
- `/tw debugneedstelemetry`
- `/tw debugrespawntrace`
- `/tw debugxpevents`

Death and Lost restoration are free gameplay flows. There is no debug-ready
revival mutation or persistence repair command.

For coops, test direct live capture and direct live release. Filled spawner
items release through their ordinary interaction instead of entering a coop.

Command status comes from the canonical lifecycle projection. A relocation
timeout only drops the pending retry; it cannot manufacture `LOST`, and none of
the debug toggles changes that rule.

`/tw debugdb status`, `health`, and `integrity` print the same bounded
replacement-persistence summary. `detail` adds bounded feature, outbox,
operation-phase, incident, quarantine, and circuit counts. None of these
actions retries work or mutates saved state.
