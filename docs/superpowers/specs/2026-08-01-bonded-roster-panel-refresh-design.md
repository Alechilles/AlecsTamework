# Bonded Roster Panel Refresh Design

Date: 2026-08-01

## Goal

Make the bonded roster panel feel responsive without weakening lifecycle-action
authority. The panel should populate as soon as cached data is available, avoid
discarding clicks through unnecessary page updates, reflect lifecycle changes
promptly, and show current progression for active companions.

## Scope

This change covers the bonded-companion roster mode of the command selection
page:

- initial roster loading and cache publication;
- refresh scheduling and update suppression;
- countdown presentation;
- lifecycle and action-state changes;
- active companion level, current XP, and available talent points;
- focused decomposition of refresh orchestration from
  `TameworkCommandSelectionPage`.

Generic linked-roster behavior, persistence schemas, progression award rules,
and custom UI protocol behavior are out of scope.

## Current Behavior and Root Causes

### Page-wide input acknowledgement

Hytale custom UI commands can target individual selectors, and Tamework already
uses targeted dynamic-card updates. However, indexed base-game release 0.5.7
shows that every `InteractiveCustomUIPage.sendUpdate(...)` routes through
`PageManager.updateCustomPage(...)`, increments the page's required
acknowledgement count, and causes `PageManager.handleEvent(...)` to reject data
events until the client acknowledgement arrives. The acknowledgement gate is
page-wide even when the update changes only one label and does not request an
interface lock.

Consequently, selector-level rendering reduces visual and payload work but does
not keep the remainder of the page clickable during a custom-page update. The
primary responsiveness control is therefore the number of update packets sent.

### Unconditional one-second refresh

`TameworkCommandSelectionPage` currently schedules a refresh every second. Each
tick rebuilds the panel snapshot and calls `sendUpdate(...)`, including when no
visible value changed. It also reapplies overlays and rebinds stable controls.
These packets repeatedly create short whole-page input acknowledgement windows,
which explains clicks that appear not to register.

### Delayed asynchronous cache publication

`BondedCompanionPanelSnapshotCache.peek(...)` deliberately returns immediately
and loads cold or stale durable data on its worker. Cache completion currently
has no direct path to an open page. The page discovers the result during its
next one-second poll, so a cold open appears empty and lifecycle changes acquire
additional polling latency.

### Stale active progression

Bonded card progression is read from `snapshotPresentationData`. The existing
live profile overlay replaces active names, health, and flight state, but not
leveling or talent data. The durable snapshot is updated by store-like lifecycle
paths, which is why level, current XP, and available talent points remain stale
until dismiss/summon.

## Chosen Approach

Use event-driven dirty signals with reason-specific scheduling, live progression
projection for the exact active entity, and strict no-op suppression. Retain a
slow safety check for sources that do not expose a usable event.

Two alternatives were considered:

1. Keep the one-second poll and merely diff rendered values. This removes some
   packets but continues frequent snapshot work and leaves cache publication
   and progression latency tied to polling.
2. Persist every XP and talent-point change immediately into the bonded
   snapshot. This would reuse durable change notifications but introduces write
   amplification and incorrectly makes presentation freshness a persistence
   responsibility.

The chosen approach keeps live state ephemeral, durable state authoritative for
stored/dead companions, and UI traffic bounded.

## Refresh Architecture

Extract refresh scheduling and dirty-state coordination from the 1,083-line
`TameworkCommandSelectionPage` into a focused collaborator. The page remains
responsible for navigation and event handling; the coordinator decides when a
snapshot should be read and whether an update packet is warranted.

The coordinator accepts dirty reasons with these policies:

| Reason | Scheduling policy |
| --- | --- |
| Cold cache publication | Immediate |
| Capture, summon, dismiss, revive, abandonment, or authoritative revision change | Immediate |
| User action performed within the page | Immediate |
| XP/progression change | Coalesced; at most one progression refresh per open page every five seconds |
| Long countdown | Refresh at ten-second presentation intervals |
| Countdown near expiration | Refresh once per second only for the final short interval |
| Countdown expiration | Exact final refresh |
| Safety check | Every 30 seconds; no packet unless presentation changed |

The progression policy is a throttle, not a trailing-edge debounce. A steady
stream of XP still produces current snapshots every five seconds rather than
postponing indefinitely. All dirty companions are read together and rendered in
one page update, so multiple companions do not multiply packet frequency. If at
least five seconds have elapsed since progression was last rendered, the first
dirty signal may refresh immediately; otherwise it schedules the remaining
delay. Any urgent update that also renders current progression satisfies that
five-second window.

Scheduled work uses page generation/cancellation tokens and stable identifiers.
It resolves the current reference and store on the world thread before reading
ECS components or sending UI changes. Closing, replacing, or navigating away
from the page invalidates pending work and releases subscriptions.

## Cache Publication and Initial Loading

Add a narrow publication notification at the bonded panel cache/lifecycle
boundary. An open bonded roster registers for its owner and roster ID. Successful
publication, retry-state changes, and authoritative invalidation notify the page
coordinator; the notification itself does not touch ECS or UI state.

Opening behavior is:

1. Render a warmed trusted snapshot immediately when present.
2. If the cache is cold, render an explicit localized loading state rather than
   an unexplained empty roster.
3. Start or reuse the asynchronous load.
4. On publication, dispatch an immediate world-thread refresh instead of
   waiting for a poll.

A routine refresh failure retains the last visible trusted cards. A cold failure
shows a localized unavailable/retrying state. Event-driven revision invalidation
retains the visible cards but marks revision-sensitive actions as updating until
the replacement snapshot restores trust.

## Active Progression Projection

Extend the live bonded presentation overlay to read one progression projection
from the exact entity named by the active lease, provided that:

- the profile state is `ACTIVE`;
- the active lease belongs to the player's current world;
- the entity reference is valid in the current store; and
- the leveling/talent components and effective configs can be resolved.

The projection supplies the presentation attributes used by the bonded card for:

- level;
- current XP;
- XP required for the next level and maximum-level state;
- available talent points and total earned points needed by the existing
  progress presentation.

The implementation should reuse the current progression snapshot and talent
resolution services rather than reimplementing formulas. It must not mutate the
profile or write live values to persistence.

If live progression cannot be resolved, preserve the durable attributes. Never
replace a valid durable value with a synthetic zero. `STORED` and `DEAD`
profiles always remain durable-snapshot based.

`CompanionXpAwardedEvent` marks an applicable open page's progression dirty.
Events are matched to the page through owner and current active projection
identity, then coalesced by the five-second throttle. Level changes and newly
earned talent points are included in the same refresh. Talent purchases made
through the talent page are visible when that flow reopens the roster; any
future in-place talent mutation signal can use the same progression-dirty seam.

## Rendering and Input Binding

Build a visible panel model and compare it with the last rendered model before
calling `sendUpdate(...)`.

- Do not send a packet when no command, property, structure, action state, or
  event binding changed.
- Progression-only and countdown-only updates set only affected presentation
  selectors.
- Stable option, close, panel-control, and card action bindings are not rebound
  for label-only updates.
- Rebuild card structure and bindings when rows are added, removed, reordered,
  or when an action's binding/state actually changes.
- Overlay renderers participate in change detection instead of emitting their
  full command set on every refresh attempt.

The renderer must preserve the existing revision checks and action authority.
Reducing UI updates must not allow an action created for an untrusted or obsolete
profile revision to execute.

## Countdown Behavior

Countdown display remains useful without maintaining a permanent one-second
heartbeat. Long-running countdowns update on ten-second presentation boundaries.
During the final ten seconds they may update once per second, accepting a brief
increase in acknowledgement traffic near completion. An exact scheduled
refresh at expiration removes the countdown and enables or changes the action
without waiting for the safety check.

The scheduler derives its next wake from the rendered countdown deadline. It
does not run a per-card timer; all due countdown changes are coalesced into one
page refresh.

## Threading and Lifecycle Safety

- Cache and event-bus listeners only record dirty state and request dispatch.
- ECS reads occur on the current world thread after resolving the live entity
  from stable UUIDs.
- No `Player` component or entity reference is captured across asynchronous
  callbacks.
- Page generation checks prevent a delayed callback from updating a newer page.
- Page close/navigation unregisters listeners and invalidates scheduled work.
- Existing bonded action trust and revision fencing remain authoritative.

## Failure Handling

- Preserve the last trusted roster during stale refresh or retry.
- Show an explicit loading/unavailable state only when no trusted roster has
  ever been published.
- Show an updating state while authoritative revision invalidation makes actions
  unsafe.
- Fall back to durable progression when the live projection is missing or
  unreadable.
- Isolate listener failures so an XP event or cache publication cannot break
  progression or persistence.
- Retain bounded cache retry/backoff behavior; notification does not introduce
  an independent retry loop.

## Verification

Use focused automated tests for behavior with meaningful regression risk:

1. Active live progression overlays level, current XP, next-level values, and
   available talent points.
2. Stored/dead profiles and missing live components retain durable values.
3. Repeated XP events for one or many companions produce at most one progression
   refresh per five-second window and use the latest values.
4. Lifecycle and successful cache-publication signals bypass the progression
   throttle.
5. Long, near-expiration, and expired countdowns choose the expected next wake.
6. An unchanged visible model produces no `sendUpdate(...)` call.
7. A progression-only update does not rebind stable controls.
8. Cold publication wakes the page immediately, while retry retains previously
   trusted cards.
9. Page close/replacement prevents delayed work from sending updates.

Run the relevant bonded panel/cache/navigation tests during development, then
run `./mvnw test`. If runtime system or tick paths are touched, also run the
thread-safety grep and ECS safety guard tests required by `AGENTS.md`.

## Documentation Impact

Update `docs/Command-Items.md`, the corresponding wiki command-item guidance,
and `CHANGELOG.md` to state that active bonded progression is live-projected and
coalesced, while stored/dead progression remains durable. Player-facing notes
should describe the faster roster loading and more reliable controls without
exposing custom-page acknowledgement internals.

No schema migration or compatibility note is required because this change does
not alter stored profile data or public asset fields.
