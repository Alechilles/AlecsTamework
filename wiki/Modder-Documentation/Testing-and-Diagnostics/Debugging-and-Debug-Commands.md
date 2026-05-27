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
- `/tw debugxpevents [on|off]`

`TwDebugConfig` can supply default values for those debug toggles, including `DespawnRoleFilter`.

`/tw showhitboxes` is diagnostic rendering, but it is not part of `TwDebugConfig` startup defaults.

`/tw debugxpevents` subscribes through `TameworkApi.events()` and logs each public
`CompanionXpAwardedEvent` hit, including source, owner UUID, tool ids, XP, and level delta.

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



