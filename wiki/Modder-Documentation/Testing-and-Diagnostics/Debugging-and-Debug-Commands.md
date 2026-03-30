---
title: "Debugging and Debug Commands"
order: 13
published: true
draft: false
---
# Debugging and Debug Commands

Parent: [Testing and Diagnostics Index](/mod/alecs-tamework/testing-and-diagnostics-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

Use this page when a Tamework integration compiles but behaves incorrectly at runtime.

## Recommended workflow
- Test on a local server first
- Watch server logs for asset decode, builder, and runtime warnings
- Validate config resolution for the exact role, item, or coop id under test

## Common commands
- `/tw getowner`, `/tw setowner`
- `/tw gettamed`, `/tw settamed`
- `/tw getalarm [AlarmName] [NpcUuid]`
- `/tw reloadconfig`
- `/tw gethappiness`, `/tw sethappiness`
- `/tw getneeds`, `/tw setneeds`, `/tw sethunger`, `/tw setthirst`
- `/tw setbreedingready`
- `/tw gettraits`, `/tw settraits`, `/tw addtrait`
- `/tw getlifestage`
- `/tw findnpc <uuid>`
- `/tw getflockdebug`

## Debug toggles
- `/tw debughook [on|off]`
- `/tw debugprompt [on|off]`
- `/tw debugspawner [on|off]`
- `/tw debugdespawn [on|off] [RoleName|all|clear]`
- `/tw debuglag [on|off]`

`TwDebugConfig` can supply default values for those debug toggles, including `DespawnRoleFilter`.

## Log patterns to watch
- Missing builder ids
- Unknown JSON attributes
- `TameworkInteract: no config resolved or config disabled`
- `TameworkInteract: no interactions matched`
- `TwGlobalConfig ... missing required fields`

## Related Pages
- [TwDebugConfig Reference](/mod/alecs-tamework/twdebugconfig-reference)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)

