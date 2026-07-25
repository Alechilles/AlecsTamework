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
- `/tw debugdb [status|health|integrity|detail|export]`

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

Death and Lost restoration is a gameplay flow. Roster-backed companions can
use role-configured exact item costs, while legacy item-linked paths remain
free. There is no debug-ready revival mutation or persistence repair command.

For coops, test direct live capture, direct captured-item intake through the
supported managed-coop interaction, and resident release independently.

Command status comes from the canonical lifecycle projection. A relocation
timeout only drops the pending retry; it cannot manufacture `LOST`, and none of
the debug toggles changes that rule.

`/tw debugdb status`, `health`, and `integrity` print the same bounded
replacement-persistence summary. `detail` adds bounded feature, outbox,
operation-phase, incident, quarantine, and circuit counts. `export` writes a
bounded redacted support ZIP under Tamework's universe data directory without
including the database or save. None of these actions retries work or mutates
saved persistence state.
