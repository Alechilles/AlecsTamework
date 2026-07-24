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

`LimitPerPlayerOwnedTotal` is a simple live owner cap:

- it counts loaded NPCs with that player as owner;
- `0` disables the cap;
- `PerPlayerLimitScope` selects `PerWorld` or `Global`;
- the shared check is used by positive owner-acquisition paths including
  taming, owner assignment, breeding, NPC spawn, and filled-spawner release;
  and
- it does not count dormant canonical profiles or create durable reservations.

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
may use free restoration. It does not create those lifecycle states and there
is no payment setting.

## Troubleshooting

- If the owner cap appears wrong, count loaded owned NPCs and inspect their live
  owner components.
- If breeding is denied, verify the SimpleClaims claim and configured breeding
  limits.
- SimpleClaims damage integration errors fail open; they do not make a target
  invulnerable.
- Use `/tw debugdb [status|health|integrity|detail|export]` for bounded
  replacement persistence diagnostics. None of these actions repairs or
  mutates saved persistence state; `export` writes only a redacted support ZIP.
