---
title: "In-Game API Self-Test Smoke Recipe"
order: 24
published: true
draft: false
---
# In-Game API Self-Test Smoke Recipe

Parent: [API Recipes](/mod/alecs-tamework/api-recipes-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

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
```

## Notes
- `prepare` and `reset` mutate fixtures; `run` is read-only.
- Each run writes full verbose test details to server logs.
- For deterministic reruns, execute `reset` before `prepare`.

## Related Pages
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)
- [Public API Overview](/mod/alecs-tamework/public-api-overview)

