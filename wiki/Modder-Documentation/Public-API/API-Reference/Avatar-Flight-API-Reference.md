---
title: "Avatar Flight API Reference"
order: 26
published: true
draft: false
---
# Avatar Flight API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference)

Part of API `3.0.0`: `TameworkApi.avatarFlight()`. Check `AVATAR_FLIGHT_LOANS`
and `available()` before lending. Older or degraded implementations return an
unavailable facade.

Loans are available only while Tamework's avatar-flight runtime is active,
because its systems are what end a loan. It is active when the server has an
enabled `TwAvatarFlightConfig` other than Tamework's default, so shipping your
own config is enough. Without one, `lend` returns `UNAVAILABLE`.

This API lends avatar flight. Your plugin turns a player into a flyer from a
`TwAvatarFlightConfig`, with no companion and no held item, and gets them back
on the ground at a point you choose. An event that lends everyone the same race
mount is the intended use.

## Threading

Call every method on the player's world thread, with that world's store, and not
from inside an ECS system or interaction callback: schedule it with
`world.execute`. `onLoanEnded` listeners are called on the world thread too,
after the player has been put back.

## Lending

`lend(store, player, AvatarFlightLoanRequest)` starts a loan. The request names:

- `lenderId`: your plugin or feature. Only that lender can end the loan or add
  Vigour to it.
- `configId`: an enabled `TwAvatarFlightConfig` id. A config is a Tamework asset
  type under `Server/Tamework/AvatarFlight`, so it can ship in your own jar; the
  server only reads that folder when Tamework is installed.
- the return point: position and yaw in the player's current world.

The result's `status` is `STARTED`, or one of `UNAVAILABLE`, `INVALID_PLAYER`,
`CONFIG_UNAVAILABLE`, `BUSY` (the player is dead, already flying, lent or on
their own companion, or riding a mount) and `FAILED`. `detail` is an English diagnostic for logs, not player text.

The flight is the same as on a companion: model, HUD, Vigour and controls. No
item needs to be held. No companion flight XP is awarded.

## Ending

Every ending puts the player at the return point, except `DEATH` (respawn
places them) and `WORLD_CHANGE` (the point belongs to the old world). Then your
listeners get an `AvatarFlightLoanEnded`: the loan and its `Reason`.

| Reason | When |
| --- | --- |
| `LENDER` | you called `endLoan(store, player, lenderId)` |
| `DISMOUNT` | the player pressed F, or held back + crouch on the ground |
| `DEATH` | the player died |
| `DISCONNECT` | the player left, or rejoined after a disconnect that could not end the loan |
| `WORLD_CHANGE` | the player changed worlds |
| `CONFIG_UNAVAILABLE` | the config was removed or disabled |
| `SERVER_RESTART` | a loan saved before a restart, ended when the player loads |

A loan never resumes after a restart, so you do not need to save anything about
it. `endLoan` returns `ENDED`, or `NO_LOAN` when the player has no loan from
that lender.

`onLoanEnded(listener)` returns an `AutoCloseable`; close it when your plugin
stops. A listener that throws is logged and does not stop the others.

## Reading and Vigour

`loan(store, player)` returns the player's current loan from any lender, or
null.

`addVigour(store, player, lenderId, charges)` adds Vigour charges to your loan
up to the config's maximum, and returns the charges actually added. It returns 0
when there is no such loan, the flight is full, or the server has unlimited
flight Vigour on. The recharge delay after a spend is left as it was.
