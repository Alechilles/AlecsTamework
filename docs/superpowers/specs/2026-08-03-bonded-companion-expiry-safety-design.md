# Bonded Companion Expiry Warnings and Safe Landing Design

Date: 2026-08-03

## Goal

Give owners clear, escalating notice before a finite bonded-companion session
expires, and prevent a forced expiry dismount from causing fall damage.

## Scope

This Tamework-side change applies to every finite active bonded-companion lease:

- owner notifications at 60, 30, and 10 seconds remaining, then every second
  from 5 through 1;
- yellow notifications for the 60/30/10-second warnings and red notifications
  for the final five warnings;
- a bounded fall-damage safety window for a player forcibly dismounted because
  their bonded companion expired.

It does not change roster-policy timer values, lease persistence, public API
contracts, the companion's normal expiration transition, voluntary dismounts,
or protection from non-fall damage.

## Current Behavior and Constraints

`BondedCompanionMaintenanceSystem` invokes Tamework's bonded maintenance once
per second in each active world. The maintenance path reconciles persisted active
leases; `BondedCompanionProjectionService` already recognizes expiry and stores
the projection with the `LEASE_EXPIRED` reason.

`TameworkUiMessageService` sends built-in notifications, and the current
notification styles provide the intended visual mapping: `Warning` is used for
yellow warnings and `Danger` for red danger feedback.

Hytale 0.5.7 processes player movement client-authoritatively. In particular,
`DamageSystems.FallDamagePlayers` reads the player's queued client movement
updates and produces `DamageCause.FALL` when the client reports a landing.
Tamework therefore cannot reliably implement a server-only slow-descent effect.
The safety guarantee belongs at the fall-damage boundary.

## Chosen Approach

Use a once-per-second lease-warning evaluator and a narrowly scoped,
server-authoritative fall-damage protection component.

The evaluator reads only finite active leases in the currently ticking world,
calculates the whole seconds remaining from each signed expiry timestamp, and
emits a notification only when the remaining value is one of 60, 30, 10, 5, 4,
3, 2, or 1. It records the emitted threshold by lease token, so scheduler jitter,
reconciliation retries, and repeated ticks cannot duplicate a warning. The
owner is resolved in the active world/store immediately before notification;
no `Player` component or entity reference crosses a deferred callback.

Each notification is exactly:

`<NPC Name> expires in <#>s`

The NPC name comes from the durable/live bonded snapshot's existing display-name
resolution. If no custom name exists, it uses the existing companion display
fallback rather than a raw profile or lease identifier.

When the expiry cleanup path confirms that it is removing a ridden projection,
it creates/refreshes a player-local `expiry dismount protection` marker. The
marker is valid for at most 60 seconds. A damage filter cancels only
`DamageCause.FALL` while this marker is active. The same world-thread system
removes the marker as soon as the client movement state reports grounded or in
fluid, and removes it unconditionally at the deadline. A voluntary dismount,
mount death, manual store, transfer, logout, ordinary missing-projection cleanup,
or any non-expiry reason must never create the marker.

This approach gives the player the requested safe landing while preserving
Hytale's normal client-controlled descent and without granting temporary
invulnerability.

## Components and Data Flow

1. The world maintenance tick gathers finite active bonded leases and determines
   which warnings are due.
2. It resolves the exact owner from the current world/store and sends one
   `Warning` or `Danger` notification through `TameworkUiMessageService`.
3. At an exact `LEASE_EXPIRED` cleanup, the projection-removal bridge detects an
   attached rider before tearing down the mount link and records temporary
   protection for that rider.
4. The protection system watches the owner's current movement state and deadline.
   It clears on ground/fluid contact or at 60 seconds.
5. The fall-damage filter cancels a `DamageCause.FALL` event only for a player
   whose protection marker remains active.

## Failure Handling and Lifecycle Rules

- Unlimited leases (`expiresAtMs == 0`) never produce warnings.
- Missing/disconnected owners receive no notification; the warning is not
  replayed after reconnect.
- Missing or unresolvable companion display data falls back to the existing
  friendly role/species presentation.
- The warning tracker is in-memory and keyed by lease token; it is discarded
  when the lease stops being active or after its final threshold.
- Protection is idempotent: repeated expiry cleanup refreshes the one marker but
  cannot stack its duration beyond 60 seconds from the latest valid application.
- A stale player reference, world change, or unavailable movement data fails
  closed: the marker expires normally and never affects another player.

## Testing and Verification

Add focused automated tests before production implementation for:

1. the exact warning schedule, colors, expiry-time rounding, unlimited leases,
   and per-lease deduplication;
2. display-name fallback and no notification for an unavailable owner;
3. expiry-only creation of a 60-second rider-protection marker;
4. marker removal on grounded/fluid state and deadline expiry;
5. fall damage is cancelled while protected, while other damage and unprotected
   fall damage remain unchanged.

Run the focused tests during the red-green cycle, then run `./mvnw test` and the
required Tamework player-access grep:

```bash
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Because the implementation touches Hytale engine-facing code, validate changed
Java files against the indexed Hytale Workshop 0.5.7 source before finalizing.
Manual acceptance should verify both warning colors/messages and an airborne
finite-session expiry, including landing safely before the 60-second cap.

## Documentation Impact

Update the bonded-companion acceptance checklist with a finite-session warning
and airborne expiry-dismount pass. Player-facing documentation should state that
finite companions provide warning notifications and that an expiry while mounted
protects the rider from fall damage until landing, up to one minute.

No database migration, public API change, or downstream asset change is
required.
