# ADR 0013: Paid Revival Authority

- Status: Rejected before release
- Date: 2026-07-23

## Context

An unreleased experiment added configurable item costs, inventory reservations,
feature-specific persistence, recovery, diagnostics, API types, and UI around
command-panel revival. It duplicated lifecycle and operation authority for a
single action and materially increased the persistence surface.

The last public Tamework release predates the experiment. Testers can restore a
released backup or create a new world, so the experiment is not a compatibility
boundary.

## Decision

Delete the paid-revival feature instead of porting it into the replacement
persistence runtime.

Keep the established free companion respawn flow and its persisted death
snapshot/restoration behavior. Keep generic capture-source replacement claims
only for exceptional capture compensation; they are not a revival payment or
refund system.

Do not retain paid-revival config, item-cost codecs, public API capabilities,
events, UI confirmation, operation kinds, schema tables, recovery handlers, or
diagnostics.

## Consequences

- Companion revival remains free and uses the existing respawn policy and
  cooldown fields.
- The replacement schema and operation registry do not include paid revival.
- Documentation and integration specs must not require revival currencies or
  advertise a paid-revival capability.
- This ADR number remains as the historical record of a deliberately rejected,
  unreleased design.
