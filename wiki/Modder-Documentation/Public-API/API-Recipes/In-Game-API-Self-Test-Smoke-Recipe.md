---
title: "In-Game API Self-Test Smoke Recipe"
order: 24
published: true
draft: false
---
# In-Game API Self-Test Smoke Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Goal: run the built-in in-game API smoke flow against a live server.

## Smoke Flow
```text
/tw api test prepare
/tw api test run all
/tw api test reset
```

## Targeted Phase 2/3 Suites
```text
/tw api test run progression verbose
/tw api test run interaction-extensions verbose
/tw api test run trait-effects verbose
/tw api test run command-ui verbose
/tw api test run command-hud verbose
```

## Console-safe smoke

The server console can run the fixture-free aggregate without a player:

```text
/tw api test status
/tw api test run all
```

Console `all` runs `core`, `command-hud`, `diagnostics`, and
`hydragon-integrations`. The `command-hud` suite is fixture-free. The
`command-ui` suite needs an in-game player context, while player/world suites
still require an in-game operator and prepared fixtures where applicable.

## Notes
- `prepare` and `reset` mutate fixtures. A player's full `run all` includes
  controlled progression mutations and baseline restoration; the console-safe
  aggregate is read-only.
- Each run writes full verbose test details to server logs.
- For deterministic reruns, execute `reset` before `prepare`.

## Related Pages
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)
- [Public API Overview](/mod/alecs-tamework/public-api-overview)


