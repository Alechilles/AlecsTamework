---
title: "Debugging and Debug Commands"
order: 13
published: true
draft: false
---
# Debugging and Debug Commands

Parent: [Testing and Diagnostics](/mod/alecs-tamework/testing-and-diagnostics) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Use this page when a Tamework integration compiles but behaves incorrectly at runtime.

## Recommended workflow
- Test on a local server first
- Watch server logs for asset decode, builder, and runtime warnings
- Validate config resolution for the exact role, item, or coop id under test

## Common commands
- `/tw getowner`, `/tw setowner`
- `/tw gettamed`, `/tw settamed`
- `/tw getalarm [AlarmName] [NpcUuid]`
- `/tw config`
- `/tw settings`
- `/tw reloadconfig`
- `/tw gethappiness`, `/tw sethappiness`
- `/tw getneeds`, `/tw setneeds`, `/tw sethunger`, `/tw setthirst`
- `/tw setbreedingready`
- `/tw gettraits`, `/tw settraits`, `/tw addtrait`
- `/tw getlifestage`
- `/tw findnpc <uuid>`
- `/tw getflockdebug`
- `/tw npcclean <roleId>`
- `/tw showhitboxes`
- `/tw debugdb [integrity|checkpoint|vacuum]`
- `/tw coop audit`
- `/tw coop import-status`
- `/tw coop reconcile <x> <y> <z> [confirm <auditFingerprint>|cancel]`
- `/tw coop rollback-preflight`
- `/tw debugreviveready`
- `/tw debugcrashtelemetry [flush|simulate]`

## Debug toggles
- `/tw debughook [on|off]`
- `/tw debugprompt [on|off]`
- `/tw debugspawner [on|off]`
- `/tw debugspawnerlocation [on|off]`
- `/tw debugdespawn [on|off] [RoleName|all|clear]`
- `/tw debuglag [on|off]`
- `/tw debugcoop [on|off]`
- `/tw debugneedsconsume [on|off]`
- `/tw debugneedsdamage [on|off]`
- `/tw debugneedsseek [on|off]`
- `/tw debugneedstelemetry [on|off]`
- `/tw debugrespawntrace [on|off]`
- `/tw debugxpevents [on|off]`

`TwDebugConfig` can supply default values for those debug toggles, including `DespawnRoleFilter`.

`/tw showhitboxes` is diagnostic rendering, but it is not part of `TwDebugConfig` startup defaults.

`/tw debugxpevents` subscribes through `TameworkApi.events()` and logs each public
`CompanionXpAwardedEvent` hit, including source, owner UUID, tool ids, XP, and level delta.
It also logs `TameworkHarvestDrop` award attempts while enabled, including rejected attempts that do not
emit a public XP event.

`/tw debugrespawntrace` logs linked companion revive and lost-recovery spawn boundaries, post-restore state,
short delayed probes, first damage correlation, and death-removal correlation for instant-death investigations.

`/tw debugneedstelemetry` emits rate-limited Alec's Telemetry error events for needs seek and consume failures.
Those events use descriptor-approved `details` fields so the telemetry portal can group failures by reason,
resource, role, and bucketed need context without turning on local per-event log spam.
This diagnostics channel is enabled by default for now, but it only records events when Tamework telemetry is
enabled in `/tw settings`. Use `/tw debugneedstelemetry off` as the local kill switch.

## Coop and breeding integrity
- `/tw coop audit` reports active managed authorities plus bounded resident/operation identity details: profile, slot, source/projection UUIDs, operation state, generation, retries, index revisions, and persistence queue lifecycle.
- `/tw coop import-status` focuses on active import sessions, pending legacy sources, sources awaiting exact absence proof, and unresolved conflicts.
- `/tw coop reconcile <x> <y> <z>` is report-only by default. Destructive progress requires the exact current audit fingerprint, the reconcile permission, and `confirm <fingerprint>`. Restart, cancellation, or any evidence/plan change revokes the process-local approval.
- `/tw coop rollback-preflight` reports rollback blockers and available pre-v5 SQLite backup evidence without changing state. Live v5-to-v4 downgrade is unsupported; restore the matching complete pre-v5 save or roll forward.
- `/tw debugdb integrity` runs SQLite and foreign-key checks plus canonical identity, managed-coop lifecycle, and import invariants.
- `/tw gethappiness` includes the active/latest breeding job's id, state, mode, partner, planned/admitted/outstanding/exact-spawned counts, population headroom, terminal reason, and rollback-attempt result.
- Import conflicts intentionally fail closed. Preserve the database/save evidence and investigate the reported source rather than manually spawning or deleting residents.
- Negative world timestamps are valid. Only `0` means unset for breeding cooldown and passive-sweep scheduling.

## Log patterns to watch
- Missing builder ids
- Unknown JSON attributes
- `TameworkInteract: no config resolved or config disabled`
- `TameworkInteract: no interactions matched`
- `TwGlobalConfig ... missing required fields`

## Related Pages
- [TwDebugConfig Reference](/mod/alecs-tamework/twdebugconfig-reference)
- [Tamework Settings UI and Persistence](/mod/alecs-tamework/tamework-settings-ui-and-persistence)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)



