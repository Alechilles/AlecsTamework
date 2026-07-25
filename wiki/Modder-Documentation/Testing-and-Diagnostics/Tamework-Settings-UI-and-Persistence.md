---
title: "Tamework Settings UI and Persistence"
order: 3
published: true
draft: false
---
# Tamework Settings UI and Persistence

`/tw settings` is the server-facing home for gameplay policy that should not be
duplicated across content packs.

It writes universe-local JSON under
`universe/Tamework/Settings/tamework-settings.json`. That settings file is
separate from the canonical companion database, `tamework-state.sqlite`;
settings do not form a second companion-lifecycle authority.

## Population limit

`LimitPerPlayerOwnedTotal` is a durable canonical owner cap:

- it counts saved profiles with that player as owner, including unloaded,
  captured, cooped, roster-stored, provisioned, dead, and Lost profiles;
- `0` disables the cap;
- `PerPlayerLimitScope` selects `PerWorld` or `Global`;
- the shared check is used by positive owner-acquisition paths including
  taming, owner assignment, breeding, NPC spawn, and filled-spawner release;
- positive acquisitions reserve capacity in their shared persistence operation;
  and
- sealed world evidence reconciles startup observations without treating
  temporary absence as ownership removal.

## SimpleClaims

The supported claims integration is direct SimpleClaims behavior:

- `SimpleClaimsEnabled` enables the bridge;
- breeding can require a claim;
- taming and breeding use configured per-claim-chunk and total-claim live
  limits; and
- `ProtectTamedFromNonMembers` enables SimpleClaims' native tamed-target
  damage policy.

There is no claim-provider dropdown or QuestLines Claims fallback.

## Other settings

The same UI continues to own the established taming, ownership, damage,
capture-owner, spawn-owner, needs-resource, and announcement settings. Apply
changes through the UI so validation and settings-file writes use one path.

Revive enablement controls whether exact `DEAD_REVIVABLE` or `LOST` profiles
may restore. Role-scoped `TwCompanionConfig.Command.Revive` supplies the
gameplay cooldown, exact AND item-cost recipe, and optional insufficient-cost
message for roster-backed revival. Legacy item-linked restoration remains
free.

## Troubleshooting

- If the owner cap appears wrong, inspect canonical lifecycle ownership and
  reconciliation readiness; nearby loaded NPCs are not the complete count.
- If breeding is denied, verify the SimpleClaims claim and configured breeding
  limits.
- SimpleClaims damage integration errors fail open; they do not make a target
  invulnerable.
- Use `/tw debugdb [status|health|integrity|detail|export]` for bounded
  replacement persistence diagnostics. None of these actions repairs or
  mutates saved persistence state; `export` writes only a redacted support ZIP.
