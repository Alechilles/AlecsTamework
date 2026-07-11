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
- `/tw debugdb [checkpoint|vacuum]`
- `/tw debugreviveready`
- `/tw debugcrashtelemetry [flush|simulate]`

`/tw npcclean <roleId>` is a destructive, role-scoped cleanup for NPCs proven unowned. It waits until both canonical owner-population and claim-occupancy reconciliation are `READY`; while either index is unavailable it removes nothing. Once ready, live owners, canonical profiles with owners, unresolved canonical profile state, and pending ownership transitions are protected and reported as skipped.

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



